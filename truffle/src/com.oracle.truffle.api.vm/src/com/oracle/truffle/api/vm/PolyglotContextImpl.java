/*
 * Copyright (c) 2017, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.truffle.api.vm;

import static com.oracle.truffle.api.vm.PolyglotImpl.isGuestInteropValue;
import static com.oracle.truffle.api.vm.PolyglotImpl.wrapGuestException;
import static com.oracle.truffle.api.vm.VMAccessor.INSTRUMENT;
import static com.oracle.truffle.api.vm.VMAccessor.LANGUAGE;
import static com.oracle.truffle.api.vm.VMAccessor.NODES;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractContextImpl;
import org.graalvm.polyglot.impl.AbstractPolyglotImpl.AbstractLanguageImpl;

import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.Env;
import com.oracle.truffle.api.vm.PolyglotImpl.VMObject;

class PolyglotContextImpl extends AbstractContextImpl implements VMObject {

    private static final PolyglotContextProfile CURRENT_CONTEXT = new PolyglotContextProfile();

    final AtomicReference<Thread> boundThread = new AtomicReference<>(null);
    volatile boolean closed;
    volatile CountDownLatch closingLatch;
    final AtomicInteger enteredCount = new AtomicInteger();
    final PolyglotEngineImpl engine;
    @CompilationFinal(dimensions = 1) final PolyglotLanguageContextImpl[] contexts;

    final OutputStream out;
    final OutputStream err;
    final InputStream in;
    final Map<String, String> options;
    final Map<String, Value> polyglotScope = new HashMap<>();
    final Predicate<String> classFilter;
    final boolean hostAccessAllowed;

    // map from class to language index
    private final FinalIntMap languageIndexMap = new FinalIntMap();

    final Map<Object, CallTarget> javaInteropCache;
    final Set<String> allowedPublicLanguages;
    final Map<String, String[]> applicationArguments;

    PolyglotContextImpl(PolyglotEngineImpl engine, final OutputStream out,
                    OutputStream err,
                    InputStream in,
                    boolean hostAccessAllowed,
                    Predicate<String> classFilter,
                    Map<String, String> options,
                    Map<String, String[]> applicationArguments,
                    Set<String> allowedPublicLanguages) {
        super(engine.impl);
        this.hostAccessAllowed = hostAccessAllowed;
        this.applicationArguments = applicationArguments;
        this.classFilter = classFilter;

        if (out == null || out == INSTRUMENT.getOut(engine.out)) {
            this.out = engine.out;
        } else {
            this.out = INSTRUMENT.createDelegatingOutput(out, engine.out);
        }
        if (err == null || err == INSTRUMENT.getOut(engine.err)) {
            this.err = engine.err;
        } else {
            this.err = INSTRUMENT.createDelegatingOutput(err, engine.err);
        }
        this.in = in == null ? engine.in : in;
        this.options = options;
        this.allowedPublicLanguages = allowedPublicLanguages;
        this.engine = engine;
        this.javaInteropCache = new HashMap<>();
        Collection<PolyglotLanguageImpl> languages = engine.idToLanguage.values();
        this.contexts = new PolyglotLanguageContextImpl[languages.size() + 1];
        this.contexts[PolyglotEngineImpl.HOST_LANGUAGE_INDEX] = new PolyglotLanguageContextImpl(this, engine.hostLanguage, null, applicationArguments.get(PolyglotEngineImpl.HOST_LANGUAGE_ID));

        for (PolyglotLanguageImpl language : languages) {
            OptionValuesImpl values = language.getOptionValues().copy();
            values.putAll(options);

            PolyglotLanguageContextImpl languageContext = new PolyglotLanguageContextImpl(this, language, values, applicationArguments.get(language.getId()));
            this.contexts[language.index] = languageContext;
        }
    }

    Predicate<String> getClassFilter() {
        return classFilter;
    }

    static PolyglotContextImpl current() {
        return CURRENT_CONTEXT.get();
    }

    static PolyglotContextImpl requireContext() {
        PolyglotContextImpl context = CURRENT_CONTEXT.get();
        if (context == null) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("No current context found.");
        }
        return context;
    }

    Object enter() {
        enterThread();
        engine.checkState();
        if (closed) {
            CompilerDirectives.transferToInterpreter();
            throw new IllegalStateException("Language context is already closed.");
        }
        enteredCount.incrementAndGet();
        return CURRENT_CONTEXT.enter(this);
    }

    void leave(Object prev) {
        assert boundThread.get() == Thread.currentThread();
        int result = enteredCount.decrementAndGet();
        if (result <= 0) {
            boundThread.set(null);
            if (closingLatch != null) {
                close(false);
            }
        }
        CURRENT_CONTEXT.leave((PolyglotContextImpl) prev);
    }

    private void enterThread() {
        Thread current = Thread.currentThread();
        if (boundThread.get() != current) {
            if (!boundThread.compareAndSet(null, current)) {
                throw new IllegalStateException(
                                String.format("The context was accessed from thread %s but is currently accessed form thread %s. " +
                                                "The context cannot be accessed from multiple threads at the same time. ",
                                                boundThread.get(), Thread.currentThread()));
            }
        }
    }

    Object importSymbolFromLanguage(String symbolName) {
        Value symbol = polyglotScope.get(symbolName);
        if (symbol == null) {
            return findLegacyExportedSymbol(symbolName);
        } else {
            return getAPIAccess().getReceiver(symbol);
        }
    }

    private Object findLegacyExportedSymbol(String symbolName) {
        Object legacySymbol = findLegacyExportedSymbol(symbolName, true);
        if (legacySymbol != null) {
            return legacySymbol;
        }
        return findLegacyExportedSymbol(symbolName, false);
    }

    private Object findLegacyExportedSymbol(String name, boolean onlyExplicit) {
        for (PolyglotLanguageContextImpl languageContext : contexts) {
            Env env = languageContext.env;
            if (env != null) {
                return LANGUAGE.findExportedSymbol(env, name, onlyExplicit);
            }
        }
        return null;
    }

    void exportSymbolFromLanguage(PolyglotLanguageContextImpl languageConext, String symbolName, Object value) {
        if (value == null) {
            polyglotScope.remove(symbolName);
        } else if (!isGuestInteropValue(value)) {
            throw new IllegalArgumentException(String.format("Invalid exported symbol value %s. Only interop and primitive values can be exported.", value.getClass().getName()));
        } else {
            polyglotScope.put(symbolName, languageConext.toHostValue(value));
        }
    }

    @Override
    public void exportSymbol(String symbolName, Object value) {
        Object prev = enter();
        try {
            Value resolvedValue;
            if (value instanceof Value) {
                resolvedValue = (Value) value;
            } else {
                PolyglotLanguageContextImpl hostContext = getHostContext();
                resolvedValue = hostContext.toHostValue(hostContext.toGuestValue(value));
            }
            polyglotScope.put(symbolName, resolvedValue);
        } finally {
            leave(prev);
        }
    }

    @Override
    public Value importSymbol(String symbolName) {
        Object prev = enter();
        try {
            Value value = polyglotScope.get(symbolName);
            if (value == null) {
                Object legacySymbol = findLegacyExportedSymbol(symbolName);
                if (legacySymbol == null) {
                    value = null;
                } else {
                    value = getHostContext().toHostValue(legacySymbol);
                }
            }
            return value;
        } catch (Throwable e) {
            throw wrapGuestException(getHostContext(), e);
        } finally {
            leave(prev);
        }
    }

    PolyglotLanguageContextImpl getHostContext() {
        return contexts[PolyglotEngineImpl.HOST_LANGUAGE_INDEX];
    }

    PolyglotLanguageContextImpl findLanguageContext(String mimeTypeOrId, boolean failIfNotFound) {
        for (PolyglotLanguageContextImpl language : contexts) {
            LanguageCache cache = language.language.cache;
            if (cache.getId().equals(mimeTypeOrId) || language.language.cache.getMimeTypes().contains(mimeTypeOrId)) {
                return language;
            }
        }
        if (failIfNotFound) {
            Set<String> mimeTypes = new LinkedHashSet<>();
            for (PolyglotLanguageContextImpl language : contexts) {
                mimeTypes.add(language.language.cache.getId());
            }
            throw new IllegalStateException("No language for id " + mimeTypeOrId + " found. Supported languages are: " + mimeTypes);
        } else {
            return null;
        }
    }

    @SuppressWarnings("rawtypes")
    PolyglotLanguageContextImpl findLanguageContext(Class<? extends TruffleLanguage> languageClazz, boolean failIfNotFound) {
        for (PolyglotLanguageContextImpl lang : contexts) {
            Env env = lang.env;
            if (env != null) {
                TruffleLanguage<?> spi = NODES.getLanguageSpi(lang.language.info);
                if (languageClazz != TruffleLanguage.class && languageClazz.isInstance(spi)) {
                    return lang;
                }
            }
        }
        if (failIfNotFound) {
            Set<String> languageNames = new HashSet<>();
            for (PolyglotLanguageContextImpl lang : contexts) {
                if (lang.env == null) {
                    continue;
                }
                languageNames.add(lang.language.cache.getClassName());
            }
            throw new IllegalStateException("Cannot find language " + languageClazz + " among " + languageNames);
        } else {
            return null;
        }
    }

    @Override
    public PolyglotEngineImpl getEngine() {
        return engine;
    }

    /*
     * Special version for getLanguageContext for the fast-path.
     */
    PolyglotLanguageContextImpl getLanguageContext(Class<? extends TruffleLanguage<?>> languageClass) {
        if (CompilerDirectives.isPartialEvaluationConstant(this)) {
            return getLanguageContextImpl(languageClass);
        } else {
            return getLanguageContextBoundary(languageClass);
        }
    }

    @TruffleBoundary
    private PolyglotLanguageContextImpl getLanguageContextBoundary(Class<? extends TruffleLanguage<?>> languageClass) {
        return getLanguageContextImpl(languageClass);
    }

    private PolyglotLanguageContextImpl getLanguageContextImpl(Class<? extends TruffleLanguage<?>> languageClass) {
        assert boundThread.get() == null || boundThread.get() == Thread.currentThread() : "not designed for thread-safety";
        int indexValue = languageIndexMap.get(languageClass);
        if (indexValue == -1) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            PolyglotLanguageContextImpl context = findLanguageContext(languageClass, false);
            if (context == null) {
                throw new IllegalArgumentException(String.format("Illegal or unregistered language class provided %s.", languageClass.getName()));
            }
            indexValue = context.language.index;
            languageIndexMap.put(languageClass, indexValue);
        }
        return contexts[indexValue];
    }

    @Override
    public boolean initializeLanguage(AbstractLanguageImpl languageImpl) {
        PolyglotLanguageImpl language = (PolyglotLanguageImpl) languageImpl;
        PolyglotLanguageContextImpl languageContext = this.contexts[language.index];
        languageContext.checkAccess();
        Object prev = enter();
        try {
            return languageContext.ensureInitialized();
        } catch (Throwable t) {
            throw wrapGuestException(languageContext, t);
        } finally {
            leave(prev);
        }
    }

    @Override
    public Value eval(String languageId, Object sourceImpl) {
        PolyglotLanguageImpl language = engine.idToLanguage.get(languageId);
        if (language == null) {
            engine.getLanguage(languageId); // will trigger the error
            assert false;
            return null;
        }
        Object prev = enter();
        PolyglotLanguageContextImpl languageContext = contexts[language.index];
        try {
            com.oracle.truffle.api.source.Source source = (com.oracle.truffle.api.source.Source) sourceImpl;
            CallTarget target = languageContext.sourceCache.get(source);
            if (target == null) {
                languageContext.ensureInitialized();
                target = LANGUAGE.parse(languageContext.env, source, null);
                if (target == null) {
                    throw new IllegalStateException(String.format("Parsing resulted in a null CallTarget for %s.", source));
                }
                languageContext.sourceCache.put(source, target);
            }
            Object result = target.call(PolyglotImpl.EMPTY_ARGS);

            if (source.isInteractive()) {
                printResult(languageContext, result);
            }

            return languageContext.toHostValue(result);
        } catch (Throwable e) {
            throw PolyglotImpl.wrapGuestException(languageContext, e);
        } finally {
            leave(prev);
        }
    }

    @TruffleBoundary
    private static void printResult(PolyglotLanguageContextImpl languageContext, Object result) {
        String stringResult = LANGUAGE.toStringIfVisible(languageContext.env, result, true);
        if (stringResult != null) {
            try {
                OutputStream out = languageContext.context.out;
                out.write(stringResult.getBytes(StandardCharsets.UTF_8));
                out.write(System.getProperty("line.separator").getBytes(StandardCharsets.UTF_8));
            } catch (IOException ioex) {
                // out stream has problems.
                throw new IllegalStateException(ioex);
            }
        }
    }

    @Override
    public Engine getEngineImpl() {
        return engine.api;
    }

    @Override
    public void close(boolean cancelIfExecuting) {
        closeImpl(cancelIfExecuting);
        if (cancelIfExecuting) {
            engine.getCancelHandler().waitForClosing(this);
        }
        if (engine.boundEngine) {
            engine.ensureClosed(cancelIfExecuting, false);
        }
    }

    void waitForClose() {
        assert boundThread.get() == null || boundThread.get() != Thread.currentThread() : "cannot wait on current thread";
        while (!closed) {
            CountDownLatch closing = closingLatch;
            if (closing == null) {
                return;
            }
            try {
                if (closing.await(100, TimeUnit.MILLISECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
            }
        }
    }

    synchronized void closeImpl(boolean cancelIfExecuting) {
        if (!closed) {
            Thread thread = boundThread.get();
            if (cancelIfExecuting) {
                if (thread != null && Thread.currentThread() != thread) {
                    if (closingLatch == null) {
                        closingLatch = new CountDownLatch(1);

                        // account for race condition when we already left the execution in
                        // the meantime. in such a case #leave(Object) will not have called
                        // close(false). Closing close(false) twice is fine.
                        if (enteredCount.get() <= 0) {
                            closeImpl(false);
                        }
                    }
                    return;
                }
            }

            Object prev = enter();
            try {
                for (PolyglotLanguageContextImpl context : contexts) {
                    try {
                        context.dispose();
                    } catch (Exception | Error ex) {
                        try {
                            err.write(String.format("Error closing language %s: %n", context.language.cache.getId()).getBytes());
                        } catch (IOException e) {
                            ex.addSuppressed(e);
                        }
                        ex.printStackTrace(new PrintStream(err));
                    }
                }
                engine.removeContext(this);
                closed = true;

                if (closingLatch != null) {
                    closingLatch.countDown();
                    closingLatch = null;
                }

            } finally {
                leave(prev);
            }
        }
    }

    @Override
    public Value lookup(Object languageImpl, String symbolName) {
        Object prev = enter();
        PolyglotLanguageContextImpl languageContext = this.contexts[((PolyglotLanguageImpl) languageImpl).index];
        try {
            return languageContext.lookupHost(symbolName);
        } catch (Throwable e) {
            throw PolyglotImpl.wrapGuestException(languageContext, e);
        } finally {
            leave(prev);
        }
    }

}

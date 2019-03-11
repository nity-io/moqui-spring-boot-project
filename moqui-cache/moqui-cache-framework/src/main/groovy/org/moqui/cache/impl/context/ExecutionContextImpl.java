/*
 * This software is in the public domain under CC0 1.0 Universal plus a
 * Grant of Patent License.
 *
 * To the extent possible under law, the author(s) have dedicated all
 * copyright and related and neighboring rights to this software to the
 * public domain worldwide. This software is distributed without any
 * warranty.
 *
 * You should have received a copy of the CC0 Public Domain Dedication
 * along with this software (see the LICENSE.md file). If not, see
 * <http://creativecommons.org/publicdomain/zero/1.0/>.
 */
package org.moqui.cache.impl.context;

import groovy.lang.Closure;
import org.moqui.cache.context.*;
import org.moqui.util.ContextBinding;
import org.moqui.util.ContextStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.cache.Cache;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.sql.Timestamp;
import java.util.*;

public class ExecutionContextImpl implements ExecutionContext {
    private static final Logger loggerDirect = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class);

    public final ExecutionContextFactoryImpl ecfi;
    public final ContextStack contextStack = new ContextStack();
    public final ContextBinding contextBindingInternal = new ContextBinding(contextStack);

    // local references to ECFI fields
    public final CacheFacadeImpl cacheFacade;

    private Boolean skipStats = null;
    private Cache<String, String> l10nMessageCache;
    private Cache<String, ArrayList> tarpitHitCache;

    public final String forThreadName;
    public final long forThreadId;
    // public final Exception createLoc;

    public ExecutionContextImpl(ExecutionContextFactoryImpl ecfi, Thread forThread) {
        this.ecfi = ecfi;
        // NOTE: no WebFacade init here, wait for call in to do that
        // put reference to this in the context root
        contextStack.put("ec", this);
        forThreadName = forThread.getName();
        forThreadId = forThread.getId();
        // createLoc = new BaseException("ec create");

        cacheFacade = ecfi.cacheFacade;

        if (cacheFacade == null) throw new IllegalStateException("cacheFacade was null");

        initCaches();

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl initialized");
    }

    @SuppressWarnings("unchecked")
    private void initCaches() {
        tarpitHitCache = cacheFacade.getCache("artifact.tarpit.hits");
        l10nMessageCache = cacheFacade.getCache("l10n.message");
    }
    Cache<String, String> getL10nMessageCache() { return l10nMessageCache; }
    public Cache<String, ArrayList> getTarpitHitCache() { return tarpitHitCache; }

    @Override public @Nonnull ExecutionContextFactory getFactory() { return ecfi; }

    @Override public @Nonnull ContextStack getContext() { return contextStack; }
    @Override public @Nonnull Map<String, Object> getContextRoot() { return contextStack.getRootMap(); }
    @Override public @Nonnull ContextBinding getContextBinding() { return contextBindingInternal; }

    @Override
    public <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters) {
        return ecfi.getTool(toolName, instanceClass, parameters);
    }

    @Override public @Nonnull CacheFacade getCache() { return cacheFacade; }

    @Override
    public void destroy() {

        // clear out the ECFI's reference to this as well
        //ecfi.activeContext.remove();
        //ecfi.activeContextMap.remove(Thread.currentThread().getId());

        MDC.remove("moqui_userId");
        MDC.remove("moqui_visitorId");

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl destroyed");
    }

    @Override public String toString() { return "ExecutionContext"; }

    public Timestamp getNowTimestamp() {
        // NOTE: review Timestamp and nowTimestamp use, have things use this by default (except audit/etc where actual date/time is needed
        return new Timestamp(System.currentTimeMillis());
    }
}

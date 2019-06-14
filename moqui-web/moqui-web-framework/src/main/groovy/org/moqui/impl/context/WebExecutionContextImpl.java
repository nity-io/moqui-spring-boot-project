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
package org.moqui.impl.context;

import groovy.lang.Closure;
import org.moqui.context.*;
import org.moqui.entity.EntityFacade;
import org.moqui.entity.EntityFind;
import org.moqui.entity.EntityList;
import org.moqui.entity.EntityValue;
import org.moqui.impl.entity.EntityFacadeImpl;
import org.moqui.impl.screen.ScreenFacadeImpl;
import org.moqui.impl.service.ServiceFacadeImpl;
import org.moqui.screen.ScreenFacade;
import org.moqui.service.ServiceFacade;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebExecutionContextImpl implements WebExecutionContext {
    private static final Logger loggerDirect = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class);

    public final WebExecutionContextFactory ecfi;
    public final ExecutionContext ec;

    private WebFacade webFacade = (WebFacade) null;
    private WebFacadeImpl webFacadeImpl = (WebFacadeImpl) null;

    public WebExecutionContextImpl(WebExecutionContextFactory ecfi, ExecutionContext ec) {
        this.ecfi = ecfi;
        this.ec = ec;
        // NOTE: no WebFacade init here, wait for call in to do that
        // put reference to this in the context root
        ec.getContext().put("ec", this);
        // createLoc = new BaseException("ec create");

        initCaches();

        if (loggerDirect.isTraceEnabled()){
            loggerDirect.trace("ExecutionContextImpl initialized");
        }
    }

    @SuppressWarnings("unchecked")
    private void initCaches() {

    }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public <T extends ExecutionContextFactory> T getFactory() {
        ExecutionContextFactory executionContextFactory = ecfi;
        return (T) executionContextFactory;
    }

    @Override public @Nonnull
    ContextStack getContext() { return ec.getContext(); }
    @Override public @Nonnull Map<String, Object> getContextRoot() { return ec.getContext().getRootMap(); }
    @Override public @Nonnull
    ContextBinding getContextBinding() { return ec.getContextBinding(); }

    @Override
    public <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters) {
        return ecfi.getTool(toolName, instanceClass, parameters);
    }

    @Override
    @Nullable
    @SuppressWarnings("unchecked")
    public  <T> T getWeb() { return (T) webFacade; }
    public @Nullable
    WebFacadeImpl getWebImpl() { return webFacadeImpl; }

    @Override public @Nonnull
    UserFacade getUser() { return ec.getUser(); }
    @Override public @Nonnull
    MessageFacade getMessage() { return ec.getMessage(); }
    @Override public @Nonnull
    ArtifactExecutionFacade getArtifactExecution() { return ec.getArtifactExecution(); }
    @Override public @Nonnull
    L10nFacade getL10n() { return ec.getL10n(); }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public <T> T getResource() { return (T) ecfi.getResource(); }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public <T> T getLogger() { return (T) ecfi.getLogger(); }
    @Override public @Nonnull
    CacheFacade getCache() { return ecfi.getCache(); }
    @Override public @Nonnull
    TransactionFacade getTransaction() { return ecfi.getTransaction(); }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public <T> T getEntity() { return (T) ecfi.getEntity(); }

    @Override
    @Nonnull
    @SuppressWarnings("unchecked")
    public <T> T getService() { return (T) ecfi.getService(); }
    @Override public @Nonnull
    ScreenFacade getScreen() { return ecfi.getScreen(); }

    @Override public @Nonnull
    NotificationMessage makeNotificationMessage() { return new NotificationMessageImpl(ecfi); }

    @Override
    public @Nonnull List<NotificationMessage> getNotificationMessages(@Nullable String topic) {
        EntityFacade entityFacade = getEntity();
        String userId = getUser().getUserId();
        if (userId == null || userId.isEmpty()){
            return new ArrayList<>();
        }

        List<NotificationMessage> nmList = new ArrayList<>();
        boolean alreadyDisabled = getArtifactExecution().disableAuthz();
        try {
            EntityFind nmbuFind = entityFacade.find("moqui.security.user.NotificationMessageByUser").condition("userId", userId);
            if (topic != null && !topic.isEmpty()){
                nmbuFind.condition("topic", topic);
            }
            EntityList nmbuList = nmbuFind.list();
            for (EntityValue nmbu : nmbuList) {
                NotificationMessageImpl nmi = new NotificationMessageImpl(ecfi);
                nmi.populateFromValue(nmbu);
                nmList.add(nmi);
            }
        } finally {
            if (!alreadyDisabled){
                getArtifactExecution().enableAuthz();
            }
        }

        return nmList;
    }

    @Override
    public void initWebFacade(@Nonnull String webappMoquiName, @Nonnull HttpServletRequest request, @Nonnull HttpServletResponse response) {
        WebFacadeImpl wfi = new WebFacadeImpl(webappMoquiName, request, response, this);
        webFacade = wfi;
        webFacadeImpl = wfi;

        // now that we have the webFacade in place we can do init UserFacade
        this.getUser().initFromHttpRequest(request, response);
        // for convenience (and more consistent code in screen actions, services, etc) add all requestParameters to the context
        this.getContext().putAll(webFacade.getRequestParameters());
        // this is the beginning of a request, so trigger before-request actions
        wfi.runBeforeRequestActions();

        String userId = this.getUser().getUserId();
        if (userId != null && !userId.isEmpty()){
            MDC.put("moqui_userId", userId);
        }
        String visitorId = this.getUser().getVisitorId();
        if (visitorId != null && !visitorId.isEmpty()){
            MDC.put("moqui_visitorId", visitorId);
        }

        if (loggerDirect.isTraceEnabled()){
            loggerDirect.trace("ExecutionContextImpl WebFacade initialized");
        }
    }

    /** Meant to be used to set a test stub that implements the WebFacade interface */
    @Override
    public void setWebFacade(WebFacade wf) {
        webFacade = wf;
        if (wf instanceof WebFacadeImpl){
            webFacadeImpl = (WebFacadeImpl) wf;
        }
        this.getContext().putAll(webFacade.getRequestParameters());
    }

    @Override
    public void runAsync(@Nonnull Closure closure) {
        ec.runAsync(closure);
    }

    @Override
    public boolean getSkipStats() {
        return ec.getSkipStats();
    }

    /** Uses the ECFI constructor for ThreadPoolRunnable so does NOT use the current ECI in the separate thread */
    @Override
    public void runInWorkerThread(@Nonnull Closure closure) {
        ec.runInWorkerThread(closure);
    }

    @Override
    public void destroy() {
        // if webFacade exists this is the end of a request, so trigger after-request actions
        if (webFacadeImpl != null){
            webFacadeImpl.runAfterRequestActions();
        }

        ec.destroy();

        WebExecutionContextFactoryImpl.activeWebContext.remove();
        WebExecutionContextFactoryImpl.activeWebContextMap.remove(Thread.currentThread().getId());

        if (loggerDirect.isTraceEnabled()){
            loggerDirect.trace("WebExecutionContextImpl destroyed");
        }
    }

    @Override
    public String getForThreadName() {
        return ec.getForThreadName();
    }

    @Override
    public long getForThreadId() {
        return ec.getForThreadId();
    }

    @Override public String toString() { return "ExecutionContext"; }

    public static class ThreadPoolRunnable implements Runnable {
        private ExecutionContextImpl threadEci;
        private ExecutionContextFactoryImpl ecfi;
        private Closure closure;
        /** With this constructor (passing ECI) the ECI is used in the separate thread */
        public ThreadPoolRunnable(ExecutionContextImpl eci, Closure closure) {
            threadEci = eci;
            ecfi = eci.ecfi;
            this.closure = closure;
        }

        /** With this constructor (passing ECFI) a new ECI is created for the separate thread */
        public ThreadPoolRunnable(ExecutionContextFactoryImpl ecfi, Closure closure) {
            this.ecfi = ecfi;
            threadEci = null;
            this.closure = closure;
        }

        @Override
        public void run() {
            if (threadEci != null){
                ecfi.useExecutionContextInThread(threadEci);
            }
            try {
                closure.call();
            } catch (Throwable t) {
                loggerDirect.error("Error in EC worker Runnable", t);
            } finally {
                if (threadEci == null){
                    ecfi.destroyActiveExecutionContext();
                }
            }
        }

        public ExecutionContextFactoryImpl getEcfi() { return ecfi; }
        public void setEcfi(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }
        public Closure getClosure() { return closure; }
        public void setClosure(Closure closure) { this.closure = closure; }
    }
}

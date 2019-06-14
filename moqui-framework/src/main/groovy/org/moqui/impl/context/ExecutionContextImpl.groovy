package org.moqui.impl.context

import org.moqui.context.*
import org.moqui.util.ContextBinding
import org.moqui.util.ContextStack
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

import javax.annotation.Nonnull
import java.lang.reflect.Method

class ExecutionContextImpl implements ExecutionContext{
    private static final Logger loggerDirect = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class);

    public final ExecutionContextFactoryImpl ecfi

    public final UserFacade userFacade;
    public final MessageFacadeImpl messageFacade;
    public final ArtifactExecutionFacadeImpl artifactExecutionFacade;
    public final L10nFacadeImpl l10nFacade;

    public final ContextStack contextStack = new ContextStack()
    public final ContextBinding contextBindingInternal = new ContextBinding(contextStack)

//    private Cache<String, String> l10nMessageCache;
//    private Cache<String, ArrayList> tarpitHitCache;

    private Boolean skipStats = false;
    public final String forThreadName;
    public final long forThreadId;

    private volatile Object entityFacade
    private volatile Object serviceFacade
    private volatile Object resourceFacade
    private volatile Object loggerFacade
    private volatile Object webFacade

    ExecutionContextImpl(ExecutionContextFactory executionContextFactory, Thread forThread) {
        this.ecfi = executionContextFactory;
        // NOTE: no WebFacade init here, wait for call in to do that
        // put reference to this in the context root
        contextStack.put("ec", this);
        forThreadName = forThread.getName();
        forThreadId = forThread.getId();
        // createLoc = new BaseException("ec create");

        userFacade = createSampleUserFacadeImpl();
        messageFacade = new MessageFacadeImpl();
        artifactExecutionFacade = new ArtifactExecutionFacadeImpl(this);
        l10nFacade = new L10nFacadeImpl(this);

        initCaches();

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl initialized");
    }

    private UserFacade createSampleUserFacadeImpl(){
        String userFacadeImplClassName = this.ecfi.confXmlRoot.first("user-facade").attribute("class") ?: "org.moqui.impl.context.UserFacadeImpl"

        Class<UserFacade> userFacadeClass = Thread.currentThread().getContextClassLoader().loadClass(userFacadeImplClassName)
        UserFacade userFacade = userFacadeClass.newInstance()
        return userFacade
    }

    @SuppressWarnings("unchecked")
    private void initCaches() {
//        tarpitHitCache = cacheFacade.getCache("artifact.tarpit.hits");
//        l10nMessageCache = cacheFacade.getCache("l10n.message");
    }

//    Cache<String, String> getL10nMessageCache() { return l10nMessageCache; }
//    Cache<String, ArrayList> getTarpitHitCache() { return tarpitHitCache; }

    @Override @Nonnull
    public <T extends ExecutionContextFactory> T getFactory() { return (T)ecfi; }

    @Override public @Nonnull ContextStack getContext() { return contextStack; }
    @Override public @Nonnull Map<String, Object> getContextRoot() { return contextStack.getRootMap(); }

    @Override
    def <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters) {
        return ecfi.getTool(toolName, instanceClass, parameters)
    }

    @Override public @Nonnull ContextBinding getContextBinding() { return contextBindingInternal; }

    @Override public @Nonnull UserFacade getUser() { return userFacade; }
    @Override public @Nonnull MessageFacade getMessage() { return messageFacade; }
    @Override public @Nonnull ArtifactExecutionFacade getArtifactExecution() { return artifactExecutionFacade; }
    @Override public @Nonnull L10nFacade getL10n() { return l10nFacade; }

    @Override
    void runAsync(@Nonnull Closure closure) {
        ThreadPoolRunnable runnable = new ThreadPoolRunnable(this, closure);
        ecfi.workerPool.submit(runnable);
    }

    @Override
    boolean getSkipStats() {
        //todo
        return skipStats
    }

    /** Uses the ECFI constructor for ThreadPoolRunnable so does NOT use the current ECI in the separate thread */
    @Override
    void runInWorkerThread(@Nonnull Closure closure) {
        ThreadPoolRunnable runnable = new ThreadPoolRunnable(ecfi, closure);
        ecfi.workerPool.submit(runnable);
    }

    @Override
    void destroy() {
        // make sure there are no transactions open, if any commit them all now
        ecfi.transactionFacade.destroyAllInThread();
        // clean up resources, like JCR session
        ecfi.resourceFacade.destroyAllInThread();
        // clear out the ECFI's reference to this as well
        ExecutionContextThreadHolder.activeContext.remove();
        ExecutionContextThreadHolder.activeContextMap.remove(Thread.currentThread().getId());

        MDC.remove("moqui_userId");
        MDC.remove("moqui_visitorId");

        if (loggerDirect.isTraceEnabled()) loggerDirect.trace("ExecutionContextImpl destroyed");
    }

    static class ThreadPoolRunnable implements Runnable {
        private ExecutionContext threadEci;
        private ExecutionContextFactory ecfi;
        private Closure closure;
        /** With this constructor (passing ECI) the ECI is used in the separate thread */
        public ThreadPoolRunnable(ExecutionContext eci, Closure closure) {
            threadEci = eci;
            ecfi = eci.ecfi;
            this.closure = closure;
        }

        /** With this constructor (passing ECFI) a new ECI is created for the separate thread */
        public ThreadPoolRunnable(ExecutionContextFactory ecfi, Closure closure) {
            this.ecfi = ecfi;
            threadEci = null;
            this.closure = closure;
        }

        @Override
        public void run() {
            if (threadEci != null) ecfi.useExecutionContextInThread(threadEci);
            try {
                closure.call();
            } catch (Throwable t) {
                loggerDirect.error("Error in EC worker Runnable", t);
            } finally {
                if (threadEci == null) ecfi.destroyActiveExecutionContext();
            }
        }

        public ExecutionContextFactoryImpl getEcfi() { return (ExecutionContextFactoryImpl) ecfi; }
        public void setEcfi(ExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi; }
        public Closure getClosure() { return closure; }
        public void setClosure(Closure closure) { this.closure = closure; }
    }

    @Override
    String getForThreadName() {
        return forThreadName
    }

    @Override
    long getForThreadId() {
        return forThreadId
    }

    @Override
    <T> T getEntity(){
        if(entityFacade == null) {
            synchronized (ExecutionContextImpl.class) {
                if (entityFacade == null) {
                    def name = "org.moqui.MoquiEntity"

                    Class<?> moquiEntityClass = Thread.currentThread().getContextClassLoader().loadClass(name)
                    Method entityMethod = moquiEntityClass.getMethod("getEntity")
                    entityFacade = entityMethod.invoke(null)
                }
            }
        }

        return (T) entityFacade
    }

    @Override
    <T> T getService(){
        if(serviceFacade == null) {
            synchronized (ExecutionContextImpl.class) {
                if (serviceFacade == null) {
                    def name = "org.moqui.MoquiService"

                    Class<?> moquiServiceClass = Thread.currentThread().getContextClassLoader().loadClass(name)
                    Method serviceMethod = moquiServiceClass.getMethod("getService")
                    serviceFacade = serviceMethod.invoke(null)
                }
            }
        }

        return (T) serviceFacade
    }

    @Override
    <T> T getResource(){
        if(resourceFacade == null) {
            synchronized (ExecutionContextImpl.class) {
                if (resourceFacade == null) {
                    def name = "org.moqui.MoquiResource"

                    Class<?> moquiResourceClass = Thread.currentThread().getContextClassLoader().loadClass(name)
                    Method resourceMethod = moquiResourceClass.getMethod("getResource")
                    resourceFacade = resourceMethod.invoke(null)
                }
            }
        }

        return (T) resourceFacade
    }

    @Override
    <T> T getLogger(){
        if(loggerFacade == null) {
            synchronized (ExecutionContextImpl.class) {
                if (loggerFacade == null) {
                    def name = "org.moqui.MoquiLogger"

                    Class<?> moquiLoggerClass = Thread.currentThread().getContextClassLoader().loadClass(name)
                    Method loggerMethod = moquiLoggerClass.getMethod("getLogger")
                    loggerFacade = loggerMethod.invoke(null)
                }
            }
        }

        return (T) loggerFacade
    }

    @Override
    <T> T getWeb(){
        if(webFacade == null) {
            synchronized (ExecutionContextImpl.class) {
                if (webFacade == null) {
                    def name = "org.moqui.MoquiWeb"

                    Class<?> moquiWebClass = Thread.currentThread().getContextClassLoader().loadClass(name)
                    Method loggerMethod = moquiWebClass.getMethod("getWeb")
                    webFacade = loggerMethod.invoke(null)
                }
            }
        }

        return (T) webFacade
    }


}

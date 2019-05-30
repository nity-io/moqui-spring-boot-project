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
package org.moqui.impl.context

import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import org.apache.shiro.SecurityUtils
import org.apache.shiro.authc.credential.CredentialsMatcher
import org.apache.shiro.authc.credential.HashedCredentialsMatcher
import org.apache.shiro.config.IniSecurityManagerFactory
import org.apache.shiro.crypto.hash.SimpleHash
import org.codehaus.groovy.control.CompilerConfiguration
import org.moqui.BaseException
import org.moqui.MoquiService
import org.moqui.MoquiWeb
import org.moqui.context.*
import org.moqui.context.ArtifactExecutionInfo.ArtifactType
import org.moqui.entity.EntityDataLoader
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.actions.XmlAction
import org.moqui.impl.screen.ScreenFacadeImpl
import org.moqui.impl.webapp.NotificationWebSocketListener
import org.moqui.resource.ResourceReference
import org.moqui.screen.ScreenFacade
import org.moqui.service.ServiceFacade
import org.moqui.util.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.annotation.Nonnull
import javax.servlet.ServletContext
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse
import javax.websocket.server.ServerContainer
import java.lang.management.ManagementFactory
import java.sql.Timestamp
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest

@CompileStatic
class WebExecutionContextFactoryImpl extends ExecutionContextFactoryImpl implements WebExecutionContextFactory {
    protected final static Logger logger = LoggerFactory.getLogger(WebExecutionContextFactoryImpl.class)

    protected final Map<String, WebappInfo> webappInfoMap = new HashMap<>()
    protected final List<NotificationMessageListener> registeredNotificationMessageListeners = []

    public static final ThreadLocal<WebExecutionContext> activeWebContext = new ThreadLocal<>()
    public static final Map<Long, WebExecutionContext> activeWebContextMap = new HashMap<>()

    /** The SecurityManager for Apache Shiro */
    protected org.apache.shiro.mgt.SecurityManager internalSecurityManager
    /** The ServletContext, if Moqui was initialized in a webapp (generally through MoquiContextListener) */
    protected ServletContext internalServletContext = null
    /** The WebSocket ServerContainer, if found in 'javax.websocket.server.ServerContainer' ServletContext attribute */
    protected ServerContainer internalServerContainer = null

    /** Notification Message Topic (for distributed notifications) */
    private SimpleTopic<NotificationMessageImpl> notificationMessageTopic = null
    private NotificationWebSocketListener notificationWebSocketListener = new NotificationWebSocketListener()

    // ======== Permanent Delegated Facades ========
    @SuppressWarnings("GrFinalVariableAccess") public final CacheFacade cacheFacade
    @SuppressWarnings("GrFinalVariableAccess") public final LoggerFacade loggerFacade
    @SuppressWarnings("GrFinalVariableAccess") public final ResourceFacade resourceFacade
    @SuppressWarnings("GrFinalVariableAccess") public final TransactionFacade transactionFacade
    @SuppressWarnings("GrFinalVariableAccess") public final EntityFacade entityFacade
    @SuppressWarnings("GrFinalVariableAccess") public final ServiceFacade serviceFacade
    @SuppressWarnings("GrFinalVariableAccess") public final ScreenFacade screenFacade

    /**
     * This constructor gets runtime directory and conf file location from a properties file on the classpath so that
     * it can initialize on its own. This is the constructor to be used by the ServiceLoader in the Moqui.java file,
     * or by init methods in a servlet or context filter or OSGi component or Spring component or whatever.
     */
    WebExecutionContextFactoryImpl() {
        super("MoquiWebConf.xml")
        long initStartTime = System.currentTimeMillis()

        preFacadeInit()

        ServiceFacade serviceFacade = MoquiService.getExecutionContextFactory().getService()

        ServiceExecutionContextFactory secf = serviceFacade.getFactory()

        // this init order is important as some facades will use others
        this.cacheFacade = secf.getCache()
        logger.info("Cache Facade initialized")
        this.loggerFacade = secf.getLogger()
        // logger.info("Logger Facade initialized")
        this.resourceFacade = secf.getResource()
        logger.info("Resource Facade initialized")

        transactionFacade = secf.getTransaction()
        logger.info("Transaction Facade initialized")
        entityFacade = secf.getEntity()
        logger.info("Entity Facade initialized")
        this.serviceFacade = serviceFacade
        logger.info("Service Facade initialized")
        screenFacade = new ScreenFacadeImpl(this)
        logger.info("Screen Facade initialized")

        postFacadeInit()

        logger.info("Execution Context Factory initialized in ${(System.currentTimeMillis() - initStartTime)/1000} seconds")
    }

    private void preFacadeInit() {
        if ("dev".equals(System.getProperty("instance_purpose")) || "test".equals(System.getProperty("instance_purpose"))) {
            // log the current configuration debugging/reference
            logger.info("Actual Conf:\n" + confXmlRoot.toString())
        }

        // get localhost address for ongoing use
        try {
            localhostAddress = InetAddress.getLocalHost()
        } catch (UnknownHostException e) {
            logger.warn("Could not get localhost address", new BaseException("Could not get localhost address", e))
        }

        // init ClassLoader early so that classpath:// resources and framework interface impls will work
        initClassLoader()

        // do these after initComponents as that may override configuration
        setServerStatsNode(confXmlRoot.first('server-stats'))
    }

    private void postFacadeInit() {
        // all config loaded, save memory by clearing the parsed MNode cache, especially for production mode
        MNode.clearParsedNodeCache()
        // bunch of junk in memory, trigger gc (to happen soon, when JVM decides, not immediate)
        System.gc()
    }

    void warmCache() {

    }

    @Override void destroy() {
        if (destroyed.getAndSet(true)) {
            logger.warn("Not destroying ExecutionContextFactory, already destroyed (or destroying)")
            return
        }

        // stop NotificationMessageListeners
        for (NotificationMessageListener nml in registeredNotificationMessageListeners) nml.destroy()

        // this destroy order is important as some use others so must be destroyed first
        if (this.serviceFacade != null) this.serviceFacade.destroy()
        if (this.entityFacade != null) this.entityFacade.destroy()
        if (this.transactionFacade != null) this.transactionFacade.destroy()
        if (this.cacheFacade != null) this.cacheFacade.destroy()
        logger.info("Facades destroyed")
        System.out.println("Facades destroyed")

        activeWebContext.remove()

        // use System.out directly for this as logger may already be stopped
        System.out.println("Moqui WebExecutionContextFactory Destroyed")
    }
    @Override boolean isDestroyed() { return destroyed }

    @Override void finalize() throws Throwable {
        try {
            if (!this.destroyed) {
                this.destroy()
                logger.warn("ExecutionContextFactoryImpl not destroyed, caught in finalize.")
            }
        } catch (Exception e) {
            logger.warn("Error in destroy, called in finalize of ExecutionContextFactoryImpl", e)
        }
        super.finalize()
    }

    /** Trigger ECF destroy and re-init in another thread, after short wait */
    void triggerDynamicReInit() {
        Thread.start("EcfiReInit", {
            sleep(2000) // wait 2 seconds
            MoquiWeb.dynamicReInit(WebExecutionContextFactoryImpl.class, internalServletContext)
        })
    }

    @Override void registerNotificationMessageListener(@Nonnull NotificationMessageListener nml) {
        nml.init(this)
        registeredNotificationMessageListeners.add(nml)
    }

    /** Called by NotificationMessageImpl.send(), send to topic (possibly distributed) */
    @Override
    void sendNotificationMessageToTopic(NotificationMessageImpl nmi) {
        if (notificationMessageTopic != null) {
            // send it to the topic, this will call notifyNotificationMessageListeners(nmi)
            notificationMessageTopic.publish(nmi)
            // logger.warn("Sent nmi to distributed topic, topic=${nmi.topic}")
        } else {
            // run it locally
            notifyNotificationMessageListeners(nmi)
        }
    }
    /** This is called when message received from topic (possibly distributed) */
    void notifyNotificationMessageListeners(NotificationMessageImpl nmi) {
        // process notifications in the worker thread pool
        ExecutionContextImpl.ThreadPoolRunnable runnable = new ExecutionContextImpl.ThreadPoolRunnable(this, {
            int nmlSize = registeredNotificationMessageListeners.size()
            for (int i = 0; i < nmlSize; i++) {
                NotificationMessageListener nml = (NotificationMessageListener) registeredNotificationMessageListeners.get(i)
                nml.onMessage(nmi)
            }
        })
        workerPool.execute(runnable)
    }
    @Override
    NotificationWebSocketListener getNotificationWebSocketListener() { return notificationWebSocketListener }

    org.apache.shiro.mgt.SecurityManager getSecurityManager() {
        if (internalSecurityManager != null) return internalSecurityManager

        // init Apache Shiro; NOTE: init must be done here so that ecfi will be fully initialized and in the static context
        org.apache.shiro.util.Factory<org.apache.shiro.mgt.SecurityManager> factory =
                new IniSecurityManagerFactory("classpath:shiro.ini")
        internalSecurityManager = factory.getInstance()
        // NOTE: setting this statically just in case something uses it, but for Moqui we'll be getting the SecurityManager from the ecfi
        SecurityUtils.setSecurityManager(internalSecurityManager)

        return internalSecurityManager
    }
    @Override
    CredentialsMatcher getCredentialsMatcher(String hashType, boolean isBase64) {
        HashedCredentialsMatcher hcm = new HashedCredentialsMatcher()
        if (hashType) {
            hcm.setHashAlgorithmName(hashType)
        } else {
            hcm.setHashAlgorithmName(getPasswordHashType())
        }
        // in Shiro this defaults to true, which is the default unless UserAccount.passwordBase64 = 'Y'
        hcm.setStoredCredentialsHexEncoded(!isBase64)
        return hcm
    }
    // NOTE: may not be used
    static String getRandomSalt() { return StringUtilities.getRandomString(8) }
    String getPasswordHashType() {
        MNode passwordNode = confXmlRoot.first("user-facade").first("password")
        return passwordNode.attribute("encrypt-hash-type") ?: "SHA-256"
    }
    // NOTE: used in UserServices.xml
    String getSimpleHash(String source, String salt) { return getSimpleHash(source, salt, getPasswordHashType(), false) }
    String getSimpleHash(String source, String salt, String hashType, boolean isBase64) {
        SimpleHash simple = new SimpleHash(hashType ?: getPasswordHashType(), source, salt)
        return isBase64 ? simple.toBase64() : simple.toHex()
    }

    String getLoginKeyHashType() {
        MNode loginKeyNode = confXmlRoot.first("user-facade").first("login-key")
        return loginKeyNode.attribute("encrypt-hash-type") ?: "SHA-256"
    }
    int getLoginKeyExpireHours() {
        MNode loginKeyNode = confXmlRoot.first("user-facade").first("login-key")
        return (loginKeyNode.attribute("expire-hours") ?: "144") as int
    }

    // ====================================================
    // ========== Main Interface Implementations ==========
    // ====================================================

    @Override
    WebExecutionContext getEci() {
        WebExecutionContext wec = (WebExecutionContext) activeWebContext.get()
        if(wec != null) return wec

        ExecutionContext ec =  (ExecutionContext) super.getEci()

        Thread currentThread = Thread.currentThread()
        if (logger.isTraceEnabled()) logger.trace("Creating new WebExecutionContext in thread [${currentThread.id}:${currentThread.name}]")

        if (!currentThread.getContextClassLoader().is(groovyClassLoader)) currentThread.setContextClassLoader(groovyClassLoader)
        wec = new WebExecutionContextImpl(this, ec)

        this.activeWebContext.set(wec)
        this.activeWebContextMap.put(currentThread.id, wec)
        return wec
    }

    void destroyActiveExecutionContext() {
        super.destroyActiveExecutionContext()

        WebExecutionContext wec = this.activeWebContext.get()
        if (wec != null) {
            wec.destroy()
            this.activeWebContext.remove()
            this.activeWebContextMap.remove(Thread.currentThread().id)
        }
    }

    @Override @Nonnull L10nFacade getL10n() { getEci().getL10n() }
    @Override @Nonnull ResourceFacade getResource() { resourceFacade }
    @Override @Nonnull LoggerFacade getLogger() { loggerFacade }
    @Override @Nonnull CacheFacade getCache() { cacheFacade }
    @Override @Nonnull TransactionFacade getTransaction() { transactionFacade }
    @Override @Nonnull EntityFacade getEntity() { entityFacade }
    @Override @Nonnull ServiceFacade getService() { serviceFacade }
    @Override @Nonnull ScreenFacade getScreen() { screenFacade }

    @Override @Nonnull ServletContext getServletContext() { internalServletContext }
    @Override @Nonnull ServerContainer getServerContainer() { internalServerContainer }
    @Override void initServletContext(ServletContext sc) {
        internalServletContext = sc
        internalServerContainer = (ServerContainer) sc.getAttribute("javax.websocket.server.ServerContainer")
    }

    Map<String, Object> getStatusMap() {
        def memoryMXBean = ManagementFactory.getMemoryMXBean()
        def heapMemoryUsage = memoryMXBean.getHeapMemoryUsage()
        def nonHeapMemoryUsage = memoryMXBean.getNonHeapMemoryUsage()

        def runtimeFile = new File(".")

        def osMXBean = ManagementFactory.getOperatingSystemMXBean()
        def runtimeMXBean = ManagementFactory.getRuntimeMXBean()
        def uptimeHours = runtimeMXBean.getUptime() / (1000*60*60)
        def startTimestamp = new Timestamp(runtimeMXBean.getStartTime())

        def gcMXBeans = ManagementFactory.getGarbageCollectorMXBeans()
        def gcCount = 0
        def gcTime = 0
        for (gcMXBean in gcMXBeans) {
            gcCount += gcMXBean.getCollectionCount()
            gcTime += gcMXBean.getCollectionTime()
        }
        def jitMXBean = ManagementFactory.getCompilationMXBean()
        def classMXBean = ManagementFactory.getClassLoadingMXBean()

        def threadMXBean = ManagementFactory.getThreadMXBean()

        BigDecimal loadAvg = new BigDecimal(osMXBean.getSystemLoadAverage()).setScale(2, BigDecimal.ROUND_HALF_UP)
        int processors = osMXBean.getAvailableProcessors()
        BigDecimal loadPercent = ((loadAvg / processors) * 100.0).setScale(2, BigDecimal.ROUND_HALF_UP)

        long heapUsed = heapMemoryUsage.getUsed()
        long heapMax = heapMemoryUsage.getMax()
        BigDecimal heapPercent = ((heapUsed / heapMax) * 100.0).setScale(2, BigDecimal.ROUND_HALF_UP)

        long diskFreeSpace = runtimeFile.getFreeSpace()
        long diskTotalSpace = runtimeFile.getTotalSpace()
        BigDecimal diskPercent = (((diskTotalSpace - diskFreeSpace) / diskTotalSpace) * 100.0).setScale(2, BigDecimal.ROUND_HALF_UP)

        HttpServletRequest request = getEci().getWeb()?.getRequest()
        Map<String, Object> statusMap = [ MoquiFramework:moquiVersion,
            Utilization: [LoadPercent:loadPercent, HeapPercent:heapPercent, DiskPercent:diskPercent],
            Web: [ LocalAddr:request?.getLocalAddr(), LocalPort:request?.getLocalPort(), LocalName:request?.getLocalName(),
                     ServerName:request?.getServerName(), ServerPort:request?.getServerPort() ],
            Heap: [ Used:(heapUsed/(1024*1024)).setScale(3, BigDecimal.ROUND_HALF_UP),
                      Committed:(heapMemoryUsage.getCommitted()/(1024*1024)).setScale(3, BigDecimal.ROUND_HALF_UP),
                      Max:(heapMax/(1024*1024)).setScale(3, BigDecimal.ROUND_HALF_UP) ],
            NonHeap: [ Used:(nonHeapMemoryUsage.getUsed()/(1024*1024)).setScale(3, BigDecimal.ROUND_HALF_UP),
                         Committed:(nonHeapMemoryUsage.getCommitted()/(1024*1024)).setScale(3, BigDecimal.ROUND_HALF_UP) ],
            Disk: [ Free:(diskFreeSpace/(1024*1024)).setScale(3, BigDecimal.ROUND_HALF_UP),
                      Usable:(runtimeFile.getUsableSpace()/(1024*1024)).setScale(3, BigDecimal.ROUND_HALF_UP),
                      Total:(diskTotalSpace/(1024*1024)).setScale(3, BigDecimal.ROUND_HALF_UP) ],
            System: [ Load:loadAvg, Processors:processors, CPU:osMXBean.getArch(),
                        OsName:osMXBean.getName(), OsVersion:osMXBean.getVersion() ],
            JavaRuntime: [ SpecVersion:runtimeMXBean.getSpecVersion(), VmVendor:runtimeMXBean.getVmVendor(),
                             VmVersion:runtimeMXBean.getVmVersion(), Start:startTimestamp, UptimeHours:uptimeHours ],
            JavaStats: [ GcCount:gcCount, GcTimeSeconds:gcTime/1000, JIT:jitMXBean.getName(), CompileTimeSeconds:jitMXBean.getTotalCompilationTime()/1000,
                           ClassesLoaded:classMXBean.getLoadedClassCount(), ClassesTotalLoaded:classMXBean.getTotalLoadedClassCount(),
                           ClassesUnloaded:classMXBean.getUnloadedClassCount(), ThreadCount:threadMXBean.getThreadCount(),
                           PeakThreadCount:threadMXBean.getPeakThreadCount() ] as Map<String, Object>,
//            DataSources: entityFacade.getDataSourcesInfo()
        ]
        return statusMap
    }

    // ==========================================
    // ========== Component Management ==========
    // ==========================================

    // called in System dashboard
    List<Map<String, Object>> getComponentInfoList() {
        List<Map<String, Object>> infoList = new ArrayList<>(componentInfoMap.size())
        for (ComponentInfo ci in componentInfoMap.values())
            infoList.add([name:ci.name, location:ci.location, version:ci.version, versionMap:ci.versionMap, dependsOnNames:ci.dependsOnNames] as Map<String, Object>)
        return infoList
    }

    protected void checkSortDependentComponents() {
        // we have an issue here where not all dependencies are declared, most are implied by component load order
        // because of this not doing a full topological sort, just a single pass with dependencies inserted as needed

        ArrayList<String> sortedNames = []
        for (ComponentInfo componentInfo in componentInfoMap.values()) {
            // for each dependsOn make sure component is valid, add to the list if not already there
            // given a close starting sort order this should get us to a pretty good list
            for (String dependsOnName in componentInfo.getRecursiveDependencies())
                if (!sortedNames.contains(dependsOnName)) sortedNames.add(dependsOnName)

            if (!sortedNames.contains(componentInfo.name)) sortedNames.add(componentInfo.name)
        }

        logger.info("Components after depends-on sort: ${sortedNames}")

        // see if all dependencies are met
        List<String> messages = []
        for (int i = 0; i < sortedNames.size(); i++) {
            String name = sortedNames.get(i)
            ComponentInfo componentInfo = componentInfoMap.get(name)
            for (String dependsOnName in componentInfo.dependsOnNames) {
                int dependsOnIndex = sortedNames.indexOf(dependsOnName)
                if (dependsOnIndex > i)
                    messages.add("Broken dependency order after initial pass: [${dependsOnName}] is after [${name}]".toString())
            }
        }

        if (messages) {
            StringBuilder sb = new StringBuilder()
            for (String message in messages) {
                logger.error(message)
                sb.append(message).append(" ")
            }
            throw new IllegalArgumentException(sb.toString())
        }

        // now create a new Map and replace the original
        LinkedHashMap<String, ComponentInfo> newMap = new LinkedHashMap<String, ComponentInfo>()
        for (String sortedName in sortedNames) newMap.put(sortedName, componentInfoMap.get(sortedName))
        componentInfoMap = newMap
    }

    protected void addComponent(ComponentInfo componentInfo) {
        if (componentInfoMap.containsKey(componentInfo.name))
            logger.warn("Overriding component [${componentInfo.name}] at [${componentInfoMap.get(componentInfo.name).location}] with location [${componentInfo.location}] because another component of the same name was initialized")
        // components registered later override those registered earlier by replacing the Map entry
        componentInfoMap.put(componentInfo.name, componentInfo)
        logger.info("Added component ${componentInfo.name.padRight(18)} at ${componentInfo.location}")
    }

    protected void addComponentDir(String location) {
        ResourceReference componentRr = getResourceReference(location)
        // if directory doesn't exist skip it, runtime doesn't always have an component directory
        if (componentRr.getExists() && componentRr.isDirectory()) {
            // see if there is a components.xml file, if so load according to it instead of all sub-directories
            ResourceReference cxmlRr = getResourceReference(location + "/components.xml")

            if (cxmlRr.getExists()) {
                MNode componentList = MNode.parse(cxmlRr)
                for (MNode childNode in componentList.children) {
                    if (childNode.name == 'component') {
                        ComponentInfo componentInfo = new ComponentInfo(location, childNode, this)
                        addComponent(componentInfo)
                    } else if (childNode.name == 'component-dir') {
                        String locAttr = childNode.attribute("location")
                        addComponentDir(location + "/" + locAttr)
                    }
                }
            } else {
                // get all files in the directory
                TreeMap<String, ResourceReference> componentDirEntries = new TreeMap<String, ResourceReference>()
                for (ResourceReference componentSubRr in componentRr.getDirectoryEntries()) {
                    // if it's a directory and doesn't start with a "." then add it as a component dir
                    String subRrName = componentSubRr.getFileName()
                    if ((!componentSubRr.isDirectory() && !subRrName.endsWith(".zip")) || subRrName.startsWith(".")) continue
                    componentDirEntries.put(componentSubRr.getFileName(), componentSubRr)
                }
                for (Map.Entry<String, ResourceReference> componentDirEntry in componentDirEntries.entrySet()) {
                    String compName = componentDirEntry.value.getFileName()
                    // skip zip files that already have a matching directory
                    if (compName.endsWith(".zip")) {
                        String compNameNoZip = stripVersionFromName(compName.substring(0, compName.length() - 4))
                        if (componentDirEntries.containsKey(compNameNoZip)) continue
                    }
                    ComponentInfo componentInfo = new ComponentInfo(componentDirEntry.value.location, this)
                    this.addComponent(componentInfo)
                }
            }
        }
    }

    /*
    @Deprecated
    void initComponent(String location) {
        ComponentInfo componentInfo = new ComponentInfo(location, this)
        // check dependencies
        if (componentInfo.dependsOnNames) for (String dependsOnName in componentInfo.dependsOnNames) {
            if (!componentInfoMap.containsKey(dependsOnName))
                throw new IllegalArgumentException("Component [${componentInfo.name}] depends on component [${dependsOnName}] which is not initialized")
        }
        addComponent(componentInfo)
    }
    void destroyComponent(String componentName) throws BaseException { componentInfoMap.remove(componentName) }
    */


    // ==========================================
    // ========== Server Stat Tracking ==========
    // ==========================================

    protected MNode getArtifactStatsNode(String artifactType, String artifactSubType) {
        // find artifact-stats node by type AND sub-type, if not found find by just the type
        MNode artifactStats = null
        if (artifactSubType != null)
            artifactStats = confXmlRoot.first("server-stats").first({ MNode it -> it.name == "artifact-stats" &&
                it.attribute("type") == artifactType && it.attribute("sub-type") == artifactSubType })
        if (artifactStats == null)
            artifactStats = confXmlRoot.first("server-stats")
                    .first({ MNode it -> it.name == "artifact-stats" && it.attribute('type') == artifactType })
        return artifactStats
    }


    // ========================================================
    // ========== Configuration File Merging Methods ==========
    // ========================================================

    @Override
    MNode getWebappNode(String webappName) { return confXmlRoot.first("webapp-list")
            .first({ MNode it -> it.name == "webapp" && it.attribute("name") == webappName }) }

    @Override
    WebappInfo getWebappInfo(String webappName) {
        WebappInfo wi = webappInfoMap.get(webappName)
        if (wi != null) return wi
        return makeWebappInfo(webappName)
    }
    protected synchronized WebappInfo makeWebappInfo(String webappName) {
        if (webappName == null || webappName.isEmpty()) return null
        WebappInfo wi = new WebappInfo(webappName, this)
        webappInfoMap.put(webappName, wi)
        return wi
    }

    static class WebappInfo {
        String webappName
        MNode webappNode
        XmlAction firstHitInVisitActions = null
        XmlAction beforeRequestActions = null
        XmlAction afterRequestActions = null
        XmlAction afterLoginActions = null
        XmlAction beforeLogoutActions = null
        XmlAction afterStartupActions = null
        XmlAction beforeShutdownActions = null
        ArrayList<MNode> responseHeaderList

        Integer sessionTimeoutSeconds = null
        String httpPort, httpHost, httpsPort, httpsHost
        boolean httpsEnabled
        boolean requireSessionToken

        WebappInfo(String webappName, WebExecutionContextFactoryImpl ecfi) {
            this.webappName = webappName
            webappNode = ecfi.confXmlRoot.first("webapp-list").first({ MNode it -> it.name == "webapp" && it.attribute("name") == webappName })
            if (webappNode == null) throw new BaseException("Could not find webapp element for name ${webappName}")

            webappNode.setSystemExpandAttributes(true)
            httpPort = webappNode.attribute("http-port") ?: null
            httpHost = webappNode.attribute("http-host") ?: null
            httpsPort = webappNode.attribute("https-port") ?: null
            httpsHost = webappNode.attribute("https-host") ?: httpHost ?: null
            httpsEnabled = "true".equals(webappNode.attribute("https-enabled"))
            requireSessionToken = !"false".equals(webappNode.attribute("require-session-token"))

            logger.info("Initializing webapp ${webappName} http://${httpHost}:${httpPort} https://${httpsHost}:${httpsPort} https enabled? ${httpsEnabled}")

            // prep actions
            if (webappNode.hasChild("first-hit-in-visit"))
                firstHitInVisitActions = new XmlAction(ecfi.getResource().getFactory(), webappNode.first("first-hit-in-visit").first("actions"),
                        "webapp_${webappName}.first_hit_in_visit.actions")

            if (webappNode.hasChild("before-request"))
                beforeRequestActions = new XmlAction(ecfi.getResource().getFactory(), webappNode.first("before-request").first("actions"),
                        "webapp_${webappName}.before_request.actions")
            if (webappNode.hasChild("after-request"))
                afterRequestActions = new XmlAction(ecfi.getResource().getFactory(), webappNode.first("after-request").first("actions"),
                        "webapp_${webappName}.after_request.actions")

            if (webappNode.hasChild("after-login"))
                afterLoginActions = new XmlAction(ecfi.getResource().getFactory(), webappNode.first("after-login").first("actions"),
                        "webapp_${webappName}.after_login.actions")
            if (webappNode.hasChild("before-logout"))
                beforeLogoutActions = new XmlAction(ecfi.getResource().getFactory(), webappNode.first("before-logout").first("actions"),
                        "webapp_${webappName}.before_logout.actions")

            if (webappNode.hasChild("after-startup"))
                afterStartupActions = new XmlAction(ecfi.getResource().getFactory(), webappNode.first("after-startup").first("actions"),
                        "webapp_${webappName}.after_startup.actions")
            if (webappNode.hasChild("before-shutdown"))
                beforeShutdownActions = new XmlAction(ecfi.getResource().getFactory(), webappNode.first("before-shutdown").first("actions"),
                        "webapp_${webappName}.before_shutdown.actions")

            responseHeaderList = webappNode.children("response-header")

            MNode sessionConfigNode = webappNode.first("session-config")
            if (sessionConfigNode != null && sessionConfigNode.attribute("timeout")) {
                sessionTimeoutSeconds = (sessionConfigNode.attribute("timeout") as int) * 60
            }
        }

        MNode getErrorScreenNode(String error) {
            return webappNode.first({ MNode it -> it.name == "error-screen" && it.attribute("error") == error })
        }

        void addHeaders(String type, HttpServletResponse response) {
            if (type == null || response == null) return
            int responseHeaderListSize = responseHeaderList.size()
            for (int i = 0; i < responseHeaderListSize; i++) {
                MNode responseHeader = (MNode) responseHeaderList.get(i)
                if (!type.equals(responseHeader.attribute("type"))) continue
                String headerValue = responseHeader.attribute("value")
                if (headerValue == null || headerValue.isEmpty()) continue
                response.addHeader(responseHeader.attribute("name"), headerValue)
                // logger.warn("Added header ${responseHeader.attribute("name")} value ${headerValue} type ${type}")
            }
        }
    }

}

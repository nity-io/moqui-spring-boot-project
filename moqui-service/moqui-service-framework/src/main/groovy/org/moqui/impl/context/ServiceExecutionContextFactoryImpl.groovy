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

import groovy.transform.CompileStatic
import org.codehaus.groovy.control.CompilerConfiguration
import org.moqui.BaseException
import org.moqui.MoquiEntity
import org.moqui.MoquiService
import org.moqui.context.*
import org.moqui.context.ArtifactExecutionInfo.ArtifactType
import org.moqui.entity.EntityFacade
import org.moqui.entity.EntityList
import org.moqui.entity.EntityValue
import org.moqui.impl.context.ContextJavaUtilService.ArtifactBinInfo
import org.moqui.impl.context.ContextJavaUtilService.ArtifactHitInfo
import org.moqui.impl.context.ContextJavaUtilService.ArtifactStatsInfo
import org.moqui.impl.entity.EntityFacadeImpl
import org.moqui.impl.service.ServiceFacadeImpl
import org.moqui.resource.ResourceReference
import org.moqui.service.ServiceFacade
import org.moqui.util.CollectionUtilities
import org.moqui.util.MClassLoader
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired

import javax.annotation.Nonnull
import javax.servlet.ServletContext
import javax.websocket.server.ServerContainer
import java.lang.management.ManagementFactory
import java.sql.Timestamp
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile

@CompileStatic
class ServiceExecutionContextFactoryImpl extends ExecutionContextFactoryImpl implements ServiceExecutionContextFactory {
    protected final static Logger logger = LoggerFactory.getLogger(ServiceExecutionContextFactoryImpl.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

    @Autowired
    private EntityExecutionContextFactoryImpl entityExecutionContextFactoryImpl

    protected final Map<String, ArtifactStatsInfo> artifactStatsInfoByType = new HashMap<>()
    public final Map<ArtifactType, Boolean> artifactTypeAuthzEnabled = new EnumMap<>(ArtifactType.class)
    public final Map<ArtifactType, Boolean> artifactTypeTarpitEnabled = new EnumMap<>(ArtifactType.class)

    protected String skipStatsCond
    protected long hitBinLengthMillis = 900000 // 15 minute default
    private final EnumMap<ArtifactType, Boolean> artifactPersistHitByTypeEnum = new EnumMap<>(ArtifactType.class)
    private final EnumMap<ArtifactType, Boolean> artifactPersistBinByTypeEnum = new EnumMap<>(ArtifactType.class)
    final ConcurrentLinkedQueue<ArtifactHitInfo> deferredHitInfoQueue = new ConcurrentLinkedQueue<ArtifactHitInfo>()

    /** The ServletContext, if Moqui was initialized in a webapp (generally through MoquiContextListener) */
    protected ServletContext internalServletContext = null
    /** The WebSocket ServerContainer, if found in 'javax.websocket.server.ServerContainer' ServletContext attribute */
    protected ServerContainer internalServerContainer = null

    // ======== Permanent Delegated Facades ========
    @SuppressWarnings("GrFinalVariableAccess") public final CacheFacadeImpl cacheFacade
    @SuppressWarnings("GrFinalVariableAccess") public final LoggerFacadeImpl loggerFacade
    @SuppressWarnings("GrFinalVariableAccess") public final ResourceFacadeImpl resourceFacade
    @SuppressWarnings("GrFinalVariableAccess") public final TransactionFacadeImpl transactionFacade
    @SuppressWarnings("GrFinalVariableAccess") public final EntityFacadeImpl entityFacade
    @SuppressWarnings("GrFinalVariableAccess") public final ServiceFacadeImpl serviceFacade

    /**
     * This constructor gets runtime directory and conf file location from a properties file on the classpath so that
     * it can initialize on its own. This is the constructor to be used by the ServiceLoader in the Moqui.java file,
     * or by init methods in a servlet or context filter or OSGi component or Spring component or whatever.
     */
    ServiceExecutionContextFactoryImpl() {
        super("MoquiServiceConf.xml")
        long initStartTime = System.currentTimeMillis()

        preFacadeInit()

        // this init order is important as some facades will use others
        cacheFacade = eecfi.cacheFacade
        logger.info("Cache Facade initialized")
        loggerFacade = eecfi.loggerFacade
        // logger.info("Logger Facade initialized")
        resourceFacade = eecfi.resourceFacade
        logger.info("Resource Facade initialized")

        transactionFacade = eecfi.transactionFacade
        logger.info("Transaction Facade initialized")
        entityFacade = eecfi.entityFacade
        logger.info("Entity Facade initialized")
        serviceFacade = new ServiceFacadeImpl(this)
        logger.info("Service Facade initialized")
        logger.info("Screen Facade initialized")

        postFacadeInit()

        MoquiService.dynamicInit(this)
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
        serverStatsNode = confXmlRoot.first('server-stats')
        skipStatsCond = serverStatsNode.attribute("stats-skip-condition")
        String binLengthAttr = serverStatsNode.attribute("bin-length-seconds")
        if (binLengthAttr != null && !binLengthAttr.isEmpty()) hitBinLengthMillis = (binLengthAttr as long)*1000
        // populate ArtifactType configurations
        for (ArtifactType at in ArtifactType.values()) {
            MNode artifactStats = getArtifactStatsNode(at.name(), null)
            if (artifactStats == null) {
                artifactPersistHitByTypeEnum.put(at, Boolean.FALSE)
                artifactPersistBinByTypeEnum.put(at, Boolean.FALSE)
            } else {
                artifactPersistHitByTypeEnum.put(at, "true".equals(artifactStats.attribute("persist-hit")))
                artifactPersistBinByTypeEnum.put(at, "true".equals(artifactStats.attribute("persist-bin")))
            }
            MNode aeNode = getArtifactExecutionNode(at.name())
            if (aeNode == null) {
                artifactTypeAuthzEnabled.put(at, true)
                artifactTypeTarpitEnabled.put(at, true)
            } else {
                artifactTypeAuthzEnabled.put(at, !"false".equals(aeNode.attribute("authz-enabled")))
                artifactTypeTarpitEnabled.put(at, !"false".equals(aeNode.attribute("tarpit-enabled")))
            }
        }

        // Load ToolFactory implementations from tools.tool-factory elements, run preFacadeInit() methods
        ArrayList<Map<String, String>> toolFactoryAttrsList = new ArrayList<>()
        for (MNode toolFactoryNode in confXmlRoot.first("tools").children("tool-factory")) {
            if (toolFactoryNode.attribute("disabled") == "true") {
                logger.info("Not loading disabled ToolFactory with class: ${toolFactoryNode.attribute("class")}")
                continue
            }
            toolFactoryAttrsList.add(toolFactoryNode.getAttributes())
        }
        CollectionUtilities.orderMapList(toolFactoryAttrsList as List<Map>, ["init-priority", "class"])
        for (Map<String, String> toolFactoryAttrs in toolFactoryAttrsList) {
            String tfClass = toolFactoryAttrs.get("class")
            logger.info("Loading ToolFactory with class: ${tfClass}")
            try {
                ToolFactory tf = (ToolFactory) Thread.currentThread().getContextClassLoader().loadClass(tfClass).newInstance()
                tf.preFacadeInit()
                toolFactoryMap.put(tf.getName(), tf)
            } catch (Throwable t) {
                logger.error("Error loading ToolFactory with class ${tfClass}", t)
            }
        }
    }

    private void postFacadeInit() {
        entityFacade.postFacadeInit()
        serviceFacade.postFacadeInit()

        // Run init() in ToolFactory implementations from tools.tool-factory elements
        for (ToolFactory tf in toolFactoryMap.values()) {
            logger.info("Initializing ToolFactory: ${tf.getName()}")
            try {
                tf.init()
            } catch (Throwable t) {
                logger.error("Error initializing ToolFactory ${tf.getName()}", t)
            }
        }

        // schedule DeferredHitInfoFlush (every 5 seconds, after 10 second init delay)
//        DeferredHitInfoFlush dhif = new DeferredHitInfoFlush(this)
//        scheduledExecutor.scheduleAtFixedRate(dhif, 10, 5, TimeUnit.SECONDS)

        // Warm cache on start if configured to do so
        if (confXmlRoot.first("cache-list").attribute("warm-on-start") != "false") warmCache()

        // all config loaded, save memory by clearing the parsed MNode cache, especially for production mode
        MNode.clearParsedNodeCache()
        // bunch of junk in memory, trigger gc (to happen soon, when JVM decides, not immediate)
        System.gc()
    }

    void warmCache() {
        this.entityFacade.warmCache()
        this.serviceFacade.warmCache()
    }

    @Override void destroy() {
        if (destroyed.getAndSet(true)) {
            logger.warn("Not destroying ExecutionContextFactory, already destroyed (or destroying)")
            return
        }

        // persist any remaining bins in artifactHitBinByType
        Timestamp currentTimestamp = new Timestamp(System.currentTimeMillis())
        List<ArtifactStatsInfo> asiList = new ArrayList<>(artifactStatsInfoByType.values())
        artifactStatsInfoByType.clear()
        ArtifactExecutionFacade aefi = getEci().getArtifactExecution()
        boolean enableAuthz = !aefi.disableAuthz()
        try {
            for (ArtifactStatsInfo asi in asiList) {
                if (asi.curHitBin == null) continue
//                EntityValue ahb = asi.curHitBin.makeAhbValue(this, currentTimestamp)
//                ahb.setSequencedIdPrimary().create()
            }
        } finally { if (enableAuthz) aefi.enableAuthz() }
        logger.info("ArtifactHitBins stored")

        // shutdown scheduled executor and worker pools
        try {
            scheduledExecutor.shutdown()
            workerPool.shutdown()

            scheduledExecutor.awaitTermination(30, TimeUnit.SECONDS)
            logger.info("Scheduled executor pool shut down")
            logger.info("Shutting down worker pool")
            workerPool.awaitTermination(30, TimeUnit.SECONDS)
            logger.info("Worker pool shut down")
        } catch (Throwable t) { logger.error("Error in workerPool/scheduledExecutor shutdown", t) }

        // Run destroy() in ToolFactory implementations from tools.tool-factory elements, in reverse order
        ArrayList<ToolFactory> toolFactoryList = new ArrayList<>(toolFactoryMap.values())
        Collections.reverse(toolFactoryList)
        for (ToolFactory tf in toolFactoryList) {
            logger.info("Destroying ToolFactory: ${tf.getName()}")
            // NOTE: also calling System.out.println because log4j gets often gets closed before this completes
            // System.out.println("Destroying ToolFactory: ${tf.getName()}")
            try {
                tf.destroy()
            } catch (Throwable t) {
                logger.error("Error destroying ToolFactory ${tf.getName()}", t)
            }
        }

        /* use to watch destroy issues:
        if (activeContextMap.size() > 2) {
            Set<Long> threadIds = activeContextMap.keySet()
            ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean()
            for (Long threadId in threadIds) {
                ThreadInfo threadInfo = threadMXBean.getThreadInfo(threadId)
                if (threadInfo == null) continue
                logger.warn("Active execution context in thread ${threadInfo.threadId}:${threadInfo.getThreadName()} state ${threadInfo.getThreadState()} blocked ${threadInfo.getBlockedCount()} lock ${threadInfo.getLockInfo()}")
            }
            for (ThreadInfo threadInfo in threadMXBean.dumpAllThreads(true, true)) {
                System.out.println()
                System.out.println(threadInfo.toString())
                // for (StackTraceElement ste in threadInfo.stackTrace) System.out.println("    ste " + ste.toString())
            }
        }
        */

        // this destroy order is important as some use others so must be destroyed first
        if (this.serviceFacade != null) this.serviceFacade.destroy()
        if (this.entityFacade != null) this.entityFacade.destroy()
        if (this.transactionFacade != null) this.transactionFacade.destroy()
        if (this.cacheFacade != null) this.cacheFacade.destroy()
        logger.info("Facades destroyed")
        System.out.println("Facades destroyed")

        for (ToolFactory tf in toolFactoryList) {
            try {
                tf.postFacadeDestroy()
            } catch (Throwable t) {
                logger.error("Error in post-facade destroy of ToolFactory ${tf.getName()}", t)
            }
        }

        ExecutionContextThreadHolder.activeContext.remove()

        // use System.out directly for this as logger may already be stopped
        System.out.println("Moqui ExecutionContextFactory Destroyed")
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
            MoquiService.dynamicReInit(ServiceExecutionContextFactoryImpl.class, internalServletContext)
        })
    }

    // ====================================================
    // ========== Main Interface Implementations ==========
    // ====================================================

    EntityExecutionContextFactoryImpl getEecfi(){
        return (EntityExecutionContextFactoryImpl)MoquiEntity.getExecutionContextFactory()
    }

    void destroyActiveExecutionContext() {
        ExecutionContext ec = ExecutionContextThreadHolder.activeContext.get()
        if (ec != null) {
            ec.destroy()
            ExecutionContextThreadHolder.activeContext.remove()
            ExecutionContextThreadHolder.activeContextMap.remove(Thread.currentThread().id)
        }
    }

    @Override
    <V> ToolFactory<V> getToolFactory(@Nonnull String toolName) {
        ToolFactory<V> toolFactory = (ToolFactory<V>) toolFactoryMap.get(toolName)
        return toolFactory
    }
    @Override
    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters) {
        ToolFactory<V> toolFactory = (ToolFactory<V>) toolFactoryMap.get(toolName)
        if (toolFactory == null) throw new IllegalArgumentException("No ToolFactory found with name ${toolName}")
        return toolFactory.getInstance(parameters)
    }

    @Override @Nonnull LinkedHashMap<String, String> getComponentBaseLocations() {
        LinkedHashMap<String, String> compLocMap = new LinkedHashMap<String, String>()
        for (ComponentInfo componentInfo in componentInfoMap.values()) compLocMap.put(componentInfo.name, componentInfo.location)
        return compLocMap
    }

    @Override @Nonnull L10nFacade getL10n() { getEci().getL10n() }
    @Override @Nonnull ResourceFacade getResource() { resourceFacade }
    @Override @Nonnull LoggerFacade getLogger() { loggerFacade }
    @Override @Nonnull CacheFacade getCache() { cacheFacade }
    @Override @Nonnull TransactionFacade getTransaction() { transactionFacade }
    @Override @Nonnull EntityFacade getEntity() { entityFacade }
    @Override @Nonnull ServiceFacade getService() { serviceFacade }

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

        def runtimeFile = new File(runtimePath)

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

//        HttpServletRequest request = getEci().getWeb()?.getRequest()
        Map<String, Object> statusMap = [ MoquiFramework:moquiVersion,
            Utilization: [LoadPercent:loadPercent, HeapPercent:heapPercent, DiskPercent:diskPercent],
//            Web: [ LocalAddr:request?.getLocalAddr(), LocalPort:request?.getLocalPort(), LocalName:request?.getLocalName(),
//                     ServerName:request?.getServerName(), ServerPort:request?.getServerPort() ],
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
            DataSources: entityFacade.getDataSourcesInfo()
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
        for (String sortedName in sortedNames) newMap.put(sortedName, componentInfoMap.get(sortedName) as ComponentInfo)
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

    protected final Set<String> entitiesToSkipHitCount = new HashSet([
            'moqui.server.ArtifactHit', 'create#moqui.server.ArtifactHit',
            'moqui.server.ArtifactHitBin', 'create#moqui.server.ArtifactHitBin',
            'moqui.entity.SequenceValueItem', 'moqui.security.UserAccount',
            'moqui.entity.document.DataDocument', 'moqui.entity.document.DataDocumentField',
            'moqui.entity.document.DataDocumentCondition', 'moqui.entity.feed.DataFeedAndDocument',
            'moqui.entity.view.DbViewEntity', 'moqui.entity.view.DbViewEntityMember',
            'moqui.entity.view.DbViewEntityKeyMap', 'moqui.entity.view.DbViewEntityAlias'])

    void countArtifactHit(ArtifactType artifactTypeEnum, String artifactSubType, String artifactName,
              Map<String, Object> parameters, long startTime, double runningTimeMillis, Long outputSize) {
        boolean isEntity = ArtifactExecutionInfo.AT_ENTITY.is(artifactTypeEnum) || (artifactSubType != null && artifactSubType.startsWith('entity'))
        // don't count the ones this calls
        if (isEntity && entitiesToSkipHitCount.contains(artifactName)) return
        // for screen, transition, screen-content check skip stats expression
        ExecutionContext eci = getEci()
        if (!isEntity && (ArtifactExecutionInfo.AT_XML_SCREEN.is(artifactTypeEnum) ||
                ArtifactExecutionInfo.AT_XML_SCREEN_CONTENT.is(artifactTypeEnum) ||
                ArtifactExecutionInfo.AT_XML_SCREEN_TRANS.is(artifactTypeEnum)) && eci.getSkipStats()) return

        boolean isSlowHit = false
        if (Boolean.TRUE.is((Boolean) artifactPersistBinByTypeEnum.get(artifactTypeEnum))) {
            // NOTE: not adding artifactTypeEnum.name() to key, artifact names should be unique
            String binKey = artifactName
            // TODO: may be more cases where we don't need to append artifactTypeEnum, ie based on artifactName
            if (artifactSubType != null && !ArtifactExecutionInfo.AT_SERVICE.is(artifactTypeEnum)) binKey = binKey.concat(artifactSubType)
            ArtifactStatsInfo statsInfo = (ArtifactStatsInfo) artifactStatsInfoByType.get(binKey)
            if (statsInfo == null) {
                // consider seeding this from the DB using ArtifactHitReport to get all past data, or maybe not to better handle different servers/etc over time, etc
                statsInfo = new ArtifactStatsInfo(artifactTypeEnum, artifactSubType, artifactName)
                artifactStatsInfoByType.put(binKey, statsInfo)
            }

            // has the current bin expired since the last hit record?
            if (statsInfo.curHitBin != null) {
                long binStartTime = statsInfo.curHitBin.startTime
                if (startTime > (binStartTime + hitBinLengthMillis)) {
                    if (isTraceEnabled) logger.trace("Advancing ArtifactHitBin [${artifactTypeEnum.name()}.${artifactSubType}:${artifactName}] current hit start [${new Timestamp(startTime)}], bin start [${new Timestamp(binStartTime)}] bin length ${hitBinLengthMillis/1000} seconds")
                    advanceArtifactHitBin(eci, statsInfo, startTime, hitBinLengthMillis)
                }
            }

            // handle stats since start
            isSlowHit = statsInfo.countHit(startTime, runningTimeMillis)
        }
        // NOTE: never save individual hits for entity artifact hits, way too heavy and also avoids self-reference
        //     (could also be done by checking for ArtifactHit/etc of course)
        // Always save slow hits above userImpactMinMillis regardless of settings
        if (!isEntity && ((isSlowHit && runningTimeMillis > ContextJavaUtil.userImpactMinMillis) ||
                Boolean.TRUE.is((Boolean) artifactPersistHitByTypeEnum.get(artifactTypeEnum)))) {
            ArtifactHitInfo ahi = new ArtifactHitInfo(eci, isSlowHit, artifactTypeEnum, artifactSubType, artifactName,
                    startTime, runningTimeMillis, parameters, outputSize)
            deferredHitInfoQueue.add(ahi)
        }
    }

    static class DeferredHitInfoFlush implements Runnable {
        // max creates per chunk, one transaction per chunk (unless error)
        final static int maxCreates = 1000
        final ServiceExecutionContextFactoryImpl ecfi
        DeferredHitInfoFlush(ServiceExecutionContextFactoryImpl ecfi) { this.ecfi = ecfi }
        @Override synchronized void run() {
            ExecutionContext eci = ecfi.getEci()
            eci.getArtifactExecution().disableAuthz()
            try {
                try {
                    ConcurrentLinkedQueue<ArtifactHitInfo> queue = ecfi.deferredHitInfoQueue
                    // split into maxCreates chunks, repeat based on initial size (may be added to while running)
                    int remainingCreates = queue.size()
                    // if (remainingCreates > maxCreates) logger.warn("Deferred ArtifactHit create queue size ${remainingCreates} is greater than max creates per chunk ${maxCreates}")
                    while (remainingCreates > 0) {
                        flushQueue(queue)
                        remainingCreates -= maxCreates
                    }
                } catch (Throwable t) {
                    logger.error("Error saving ArtifactHits", t)
                }
            } finally {
                // no need, we're destroying the eci: if (!authzDisabled) eci.artifactExecution.enableAuthz()
                eci.destroy()
            }
        }

        void flushQueue(ConcurrentLinkedQueue<ArtifactHitInfo> queue) {
            ServiceExecutionContextFactoryImpl localEcfi = ecfi
            ArrayList<ArtifactHitInfo> createList = new ArrayList<>(maxCreates)
            int createCount = 0
            while (createCount < maxCreates) {
                ArtifactHitInfo ahi = queue.poll()
                if (ahi == null) break
                createCount++
                createList.add(ahi)
            }
            int retryCount = 5
            while (retryCount > 0) {
                try {
                    int createListSize = createList.size()
                    if (createListSize == 0) break
                    long startTime = System.currentTimeMillis()
                    ecfi.transactionFacade.runUseOrBegin(60, "Error saving ArtifactHits", {
                        for (int i = 0; i < createListSize; i++) {
                            ArtifactHitInfo ahi = (ArtifactHitInfo) createList.get(i)
                            try {
//                                EntityValue ahValue = ahi.makeAhiValue(localEcfi)
//                                ahValue.setSequencedIdPrimary()
//                                ahValue.create()
                            } catch (Throwable t) {
                                createList.remove(i)
                                throw t
                            }
                        }
                    })
                    if (isTraceEnabled) logger.trace("Created ${createListSize} ArtifactHit records in ${System.currentTimeMillis() - startTime}ms")
                    break
                } catch (Throwable t) {
                    logger.error("Error saving ArtifactHits, retrying (${retryCount})", t)
                    retryCount--
                }
            }
        }
    }

    protected synchronized void advanceArtifactHitBin(ExecutionContext eci, ArtifactStatsInfo statsInfo,
                                                      long startTime, long hitBinLengthMillis) {
        ArtifactBinInfo abi = statsInfo.curHitBin
        if (abi == null) {
            statsInfo.curHitBin = new ArtifactBinInfo(statsInfo, startTime)
            return
        }

        // check the time again and return just in case something got in while waiting with the same type
        long binStartTime = abi.startTime
        if (startTime < (binStartTime + hitBinLengthMillis)) return

        // otherwise, persist the old and create a new one
//        EntityValue ahb = abi.makeAhbValue(this, new Timestamp(binStartTime + hitBinLengthMillis))
//        eci.runInWorkerThread({
//            ArtifactExecutionFacadeImpl aefi = getEci().artifactExecutionFacade
//            boolean enableAuthz = !aefi.disableAuthz()
//            try { ahb.setSequencedIdPrimary().create() }
//            finally { if (enableAuthz) aefi.enableAuthz() }
//        })

        statsInfo.curHitBin = new ArtifactBinInfo(statsInfo, startTime)
    }

}

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
import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.tools.GroovyClass
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.ExecutionContextThreadHolder
import org.moqui.context.ToolFactory
import org.moqui.resource.ResourceReference
import org.moqui.resource.UrlResourceReference
import org.moqui.util.*
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.annotation.Nonnull
import java.lang.management.ManagementFactory
import java.sql.Timestamp
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.Attributes
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

@CompileStatic
abstract class ExecutionContextFactoryImpl implements ExecutionContextFactory {
    protected final static Logger logger = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

    protected AtomicBoolean destroyed = new AtomicBoolean(false)

    protected String runtimePath
    @SuppressWarnings("GrFinalVariableAccess") protected final String moduleConfPath
    @SuppressWarnings("GrFinalVariableAccess") protected static final MNode confXmlDefault
    @SuppressWarnings("GrFinalVariableAccess") protected static final MNode confXmlRoot
    protected static final Map<String, MNode> confXmlMap = new HashMap()

    protected MNode serverStatsNode
    protected String moquiVersion = ""
    protected Map versionMap = null
    protected InetAddress localhostAddress = null

    protected MClassLoader moquiClassLoader
    public GroovyClassLoader groovyClassLoader
    protected CompilerConfiguration groovyCompilerConf
    // NOTE: this is experimental, don't set to true! still issues with unique class names, etc
    // also issue with how to support recompile of actions on change, could just use for expressions but that only helps so much
    // maybe some way to load from disk only if timestamp newer for XmlActions and GroovyScriptRunner
    // this could be driven by setting in Moqui Conf XML file
    // also need to clean out runtime/script-classes in gradle cleanAll
    protected boolean groovyCompileCacheToDisk = false

    protected LinkedHashMap<String, ComponentInfo> componentInfoMap = new LinkedHashMap<>()
    protected final LinkedHashMap<String, ToolFactory> toolFactoryMap = new LinkedHashMap<>()

    /** The main worker pool for services, running async closures and runnables, etc */
    @SuppressWarnings("GrFinalVariableAccess") public final ThreadPoolExecutor workerPool
    /** An executor for the scheduled job runner */
    public final ScheduledThreadPoolExecutor scheduledExecutor = new ScheduledThreadPoolExecutor(4)

    static {
        URL defaultConfUrl = ClassLoader.getSystemResource("MoquiDefaultConf.xml")
        if (defaultConfUrl == null) throw new IllegalArgumentException("Could not find MoquiDefaultConf.xml file on the classpath")

        confXmlDefault = MNode.parse(defaultConfUrl.toString(), defaultConfUrl.newInputStream())
        confXmlRoot = MNode.parse(defaultConfUrl.toString(), defaultConfUrl.newInputStream())

        String componentConfigFile = "MoquiConf.xml";
        Enumeration<URL> urlEnumeration = ClassLoader.getSystemResources(componentConfigFile)
        if (urlEnumeration != null){
            while(urlEnumeration.hasMoreElements()){
                URL url = urlEnumeration.nextElement()
                logger.info("loadComponentConf {}", url.getPath())
                MNode compXmlNode = MNode.parse(url.toString(), url.newInputStream())
                mergeConfigNodes(confXmlRoot, compXmlNode)
            }
        }
    }

    ExecutionContextFactoryImpl(String moduleConfPath) {
        this.moduleConfPath = moduleConfPath
        loadModuleConf()
        this.workerPool = makeWorkerPool()
    }

    protected MNode loadModuleConf() {
        logger.info("loadModuleConf {}", moduleConfPath)

        URL moduleConfUrl = this.class.getClassLoader().getResource(moduleConfPath)
        if (moduleConfUrl == null) throw new IllegalArgumentException("Could not find $moduleConfPath file on the classpath")

        // initialize all configuration, get various conf files merged and load components
        MNode moduleConfXmlRoot = MNode.parse(moduleConfUrl.toString(), moduleConfUrl.newInputStream())

        confXmlMap.put(moduleConfPath, moduleConfXmlRoot);

        MNode baseConfigNode = initBaseConfig(moduleConfXmlRoot)
        // init components before initConfig() so component configuration files can be incorporated
        initComponents(baseConfigNode)
        // init the configuration (merge from component and runtime conf files)
        MNode config = initConfig(baseConfigNode, moduleConfXmlRoot)
        return config
    }

    protected MNode initBaseConfig(MNode moduleConfXmlRoot) {
        Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF")
        while (resources.hasMoreElements()) {
            try {
                Manifest manifest = new Manifest(resources.nextElement().openStream())
                Attributes attributes = manifest.getMainAttributes()
                String implTitle = attributes.getValue("Implementation-Title")
                String implVendor = attributes.getValue("Implementation-Vendor")
                if ("Moqui Framework".equals(implTitle) && "Moqui Ecosystem".equals(implVendor)) {
                    moquiVersion = attributes.getValue("Implementation-Version")
                    break
                }
            } catch (IOException e) {
                logger.info("Error reading manifest files", e)
            }
        }
        System.setProperty("moqui.version", moquiVersion)

        // don't set the moqui.runtime and moqui.conf system properties as before, causes conflict with multiple moqui instances in one JVM
        // NOTE: moqui.runtime is set in MoquiStart and in MoquiContextListener (if there is an embedded runtime directory)
        // System.setProperty("moqui.runtime", runtimePath)
        // System.setProperty("moqui.conf", moduleConfPath)

//        logger.info("Initializing Moqui Framework version ${moquiVersion ?: 'Unknown'}\n - runtime directory: ${this.runtimePath}\n - runtime config:    ${this.moduleConfPath}")


        // just merge the component configuration, needed before component init is done
        mergeConfigComponentNodes(confXmlRoot, moduleConfXmlRoot)

        return confXmlRoot
    }

    protected void initComponents(MNode baseConfigNode) {
        File versionJsonFile = new File(runtimePath + "/version.json")
        if (versionJsonFile.exists()) {
            try {
                versionMap = (Map) new JsonSlurper().parse(versionJsonFile)
            } catch (Exception e) {
                logger.warn("Error parsion runtime/version.json", e)
            }
        }

        // init components referred to in component-list.component and component-dir elements in the conf file
        for (MNode childNode in baseConfigNode.first("component-list").children) {
            if ("component".equals(childNode.name)) {
                addComponent(new ComponentInfo(null, childNode, this))
            } else if ("component-dir".equals(childNode.name)) {
                addComponentDir(childNode.attribute("location"))
            }
        }
        checkSortDependentComponents()
    }

    protected MNode initConfig(MNode baseConfigNode, MNode moduleConfXmlRoot) {

        // merge the runtime conf file into the default one to override any settings (they both have the same root node, go from there)
        logger.info("Merging runtime configuration at ${moduleConfPath}")
        mergeConfigNodes(baseConfigNode, moduleConfXmlRoot)

        // set default System properties now that all is merged
        for (MNode defPropNode in baseConfigNode.children("default-property")) {
            String propName = defPropNode.attribute("name")
            if (System.getProperty(propName)) {
                if (propName.contains("pass") || propName.contains("pw") || propName.contains("key")) {
                    logger.info("Found pw/key property ${propName}, not setting from env var or default")
                } else {
                    logger.info("Found property ${propName} with value [${System.getProperty(propName)}], not setting from env var or default")
                }
                continue
            }
            if (System.getenv(propName) && !System.getProperty(propName)) {
                // make env vars available as Java System properties
                System.setProperty(propName, System.getenv(propName))
                if (propName.contains("pass") || propName.contains("pw") || propName.contains("key")) {
                    logger.info("Setting pw/key property ${propName} from env var")
                } else {
                    logger.info("Setting property ${propName} from env var with value [${System.getProperty(propName)}]")
                }
            }
            if (!System.getProperty(propName) && !System.getenv(propName)) {
                String valueAttr = defPropNode.attribute("value")
                if (valueAttr != null && !valueAttr.isEmpty()) {
                    System.setProperty(propName, SystemBinding.expand(valueAttr))
                    if (propName.contains("pass") || propName.contains("pw") || propName.contains("key")) {
                        logger.info("Setting pw/key property ${propName} from default")
                    } else {
                        logger.info("Setting property ${propName} from default with value [${System.getProperty(propName)}]")
                    }
                }
            }
        }

        // if there are default_locale or default_time_zone Java props or system env vars set defaults
        String localeStr = SystemBinding.getPropOrEnv("default_locale")
        if (localeStr) {
            try {
                int usIdx = localeStr.indexOf("_")
                Locale.setDefault(usIdx < 0 ? new Locale(localeStr) :
                        new Locale(localeStr.substring(0, usIdx), localeStr.substring(usIdx+1).toUpperCase()))
            } catch (Throwable t) {
                logger.error("Error setting default locale to ${localeStr}: ${t.toString()}")
            }
        }
        String tzStr = SystemBinding.getPropOrEnv("default_time_zone")
        if (tzStr) {
            try {
                logger.info("Found default_time_zone ${tzStr}: ${TimeZone.getTimeZone(tzStr)}")
                TimeZone.setDefault(TimeZone.getTimeZone(tzStr))
            } catch (Throwable t) {
                logger.error("Error setting default time zone to ${tzStr}: ${t.toString()}")
            }
        }
        logger.info("Default locale ${Locale.getDefault()}, time zone ${TimeZone.getDefault()}")

        return baseConfigNode
    }

    /** Setup the cached ClassLoader, this should init in the main thread so we can set it properly */
    protected void initClassLoader() {
        long startTime = System.currentTimeMillis()

        ClassLoader pcl = (Thread.currentThread().getContextClassLoader() ?: this.class.classLoader) ?: System.classLoader
        moquiClassLoader = new MClassLoader(pcl)

        setGroovyClassLoader(new GroovyClassLoader(moquiClassLoader))

        File scriptClassesDir = new File(runtimePath ?: "." + "/script-classes")
        scriptClassesDir.mkdirs()
        if (groovyCompileCacheToDisk) moquiClassLoader.addClassesDirectory(scriptClassesDir)
        groovyCompilerConf = new CompilerConfiguration()
        groovyCompilerConf.setTargetDirectory(scriptClassesDir)

        // add runtime/classes jar files to the class loader
        File runtimeClassesFile = new File(runtimePath ?: "." + "/classes")
        if (runtimeClassesFile.exists()) {
            moquiClassLoader.addClassesDirectory(runtimeClassesFile)
        }
        // add runtime/lib jar files to the class loader
        File runtimeLibFile = new File(runtimePath ?: "." + "/lib")
        if (runtimeLibFile.exists()) for (File jarFile: runtimeLibFile.listFiles()) {
            if (jarFile.getName().endsWith(".jar")) {
                moquiClassLoader.addJarFile(new JarFile(jarFile), jarFile.toURI().toURL())
                logger.info("Added JAR from runtime/lib: ${jarFile.getName()}")
            }
        }

        // add <component>/classes and <component>/lib jar files to the class loader now that component locations loaded
        for (ComponentInfo ci in componentInfoMap.values()) {
            ResourceReference classesRr = ci.componentRr.getChild("classes")
            if (classesRr.exists && classesRr.supportsDirectory() && classesRr.isDirectory()) {
                moquiClassLoader.addClassesDirectory(new File(classesRr.getUrl().getPath()))
            }

            ResourceReference libRr = ci.componentRr.getChild("lib")
            if (libRr.exists && libRr.supportsDirectory() && libRr.isDirectory()) {
                Set<String> jarsLoaded = new LinkedHashSet<>()
                for (ResourceReference jarRr: libRr.getDirectoryEntries()) {
                    if (jarRr.fileName.endsWith(".jar")) {
                        try {
                            moquiClassLoader.addJarFile(new JarFile(new File(jarRr.getUrl().getPath())), jarRr.getUrl())
                            jarsLoaded.add(jarRr.getFileName())
                        } catch (Exception e) {
                            logger.error("Could not load JAR from component ${ci.name}: ${jarRr.getLocation()}: ${e.toString()}")
                        }
                    }
                }
                logger.info("Added JARs from component ${ci.name}: ${jarsLoaded}")
            }
        }

        // clear not found info just in case anything was falsely added
        moquiClassLoader.clearNotFoundInfo()
        // set as context classloader
        Thread.currentThread().setContextClassLoader(groovyClassLoader)

        logger.info("Initialized ClassLoader in ${System.currentTimeMillis() - startTime}ms")
    }

    // NOTE: using unbound LinkedBlockingQueue, so max pool size in ThreadPoolExecutor has no effect
    private static class WorkerThreadFactory implements ThreadFactory {
        private final ThreadGroup workerGroup = new ThreadGroup("MoquiWorkers")
        private final AtomicInteger threadNumber = new AtomicInteger(1)
        Thread newThread(Runnable r) { return new Thread(workerGroup, r, "MoquiWorker-" + threadNumber.getAndIncrement()) }
    }
    private ThreadPoolExecutor makeWorkerPool() {
        MNode toolsNode = confXmlRoot.first('tools')

        int workerQueueSize = (toolsNode.attribute("worker-queue") ?: "65536") as int
        BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>(workerQueueSize)

        int coreSize = (toolsNode.attribute("worker-pool-core") ?: "4") as int
        int maxSize = (toolsNode.attribute("worker-pool-max") ?: "16") as int
        int availableProcessorsSize = Runtime.getRuntime().availableProcessors() * 2
        if (availableProcessorsSize > maxSize) {
            logger.info("Setting worker pool size to ${availableProcessorsSize} based on available processors * 2")
            maxSize = availableProcessorsSize
        }
        long aliveTime = (toolsNode.attribute("worker-pool-alive") ?: "60") as long

        logger.info("Initializing worker ThreadPoolExecutor: queue limit ${workerQueueSize}, pool-core ${coreSize}, pool-max ${maxSize}, pool-alive ${aliveTime}s")
        return new ThreadPoolExecutor(coreSize, maxSize, aliveTime, TimeUnit.SECONDS, workQueue, new WorkerThreadFactory())
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

    @Override @Nonnull String getRuntimePath() { return runtimePath }
    @Override @Nonnull String getMoquiVersion() { return moquiVersion }
    Map getVersionMap() { return versionMap }
    @Override @Nonnull
    MNode getConfXmlRoot() { return confXmlRoot }
    @Override
    MNode getServerStatsNode() {
        return serverStatsNode
    }
    MNode getArtifactExecutionNode(String artifactTypeEnumId) {
        return confXmlRoot.first("artifact-execution-facade")
                .first({ MNode it -> it.name == "artifact-execution" && it.attribute("type") == artifactTypeEnumId })
    }

    @Override
    InetAddress getLocalhostAddress() { return localhostAddress }

    // NOTE: may not be used
    static String getRandomSalt() { return StringUtilities.getRandomString(8) }
    String getPasswordHashType() {
        MNode passwordNode = confXmlRoot.first("user-facade").first("password")
        return passwordNode.attribute("encrypt-hash-type") ?: "SHA-256"
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

    @Override @Nonnull ExecutionContext getExecutionContext() { return getEci() }

    @Override
    @Nonnull
    ExecutionContext getEci() {
        // the ExecutionContextImpl cast here looks funny, but avoids Groovy using a slow castToType call
        ExecutionContext ec = ExecutionContextThreadHolder.activeContext.get()
        if (ec != null) return ec

        Thread currentThread = Thread.currentThread()
        if (logger.traceEnabled) logger.trace("Creating new ExecutionContext in thread [${currentThread.id}:${currentThread.name}]")
        if (!currentThread.getContextClassLoader().is(groovyClassLoader)) {
            currentThread.setContextClassLoader(groovyClassLoader)
        }
        ec = createExecutionContextImpl(this, currentThread);
        ExecutionContextThreadHolder.activeContext.set(ec)
        ExecutionContextThreadHolder.activeContextMap.put(currentThread.id, ec)
        return ec
    }

    @Nonnull
    ExecutionContextImpl createExecutionContextImpl(ExecutionContextFactory executionContextFactory, Thread thread){
        return new ExecutionContextImpl(executionContextFactory, thread)
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
    void useExecutionContextInThread(ExecutionContext eci) {
        ExecutionContext curEc = ExecutionContextThreadHolder.activeContext.get()
        if (curEc != null) curEc.destroy()
        ExecutionContextThreadHolder.activeContext.set(eci)
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

    @Override @Nonnull ClassLoader getClassLoader() { groovyClassLoader }
    @Override @Nonnull GroovyClassLoader getGroovyClassLoader() { groovyClassLoader }

    @Override @Nonnull void setGroovyClassLoader(GroovyClassLoader groovyClassLoader) {
        this.groovyClassLoader = groovyClassLoader
    }
    void setDestroyed(AtomicBoolean destroyed) {
        this.destroyed = destroyed
    }

    void setRuntimePath(String runtimePath) {
        this.runtimePath = runtimePath
    }

    void setServerStatsNode(MNode serverStatsNode) {
        this.serverStatsNode = serverStatsNode
    }

    void setMoquiVersion(String moquiVersion) {
        this.moquiVersion = moquiVersion
    }

    void setVersionMap(Map versionMap) {
        this.versionMap = versionMap
    }

    void setLocalhostAddress(InetAddress localhostAddress) {
        this.localhostAddress = localhostAddress
    }

    void setMoquiClassLoader(MClassLoader moquiClassLoader) {
        this.moquiClassLoader = moquiClassLoader
    }

    void setGroovyCompilerConf(CompilerConfiguration groovyCompilerConf) {
        this.groovyCompilerConf = groovyCompilerConf
    }

    void setGroovyCompileCacheToDisk(boolean groovyCompileCacheToDisk) {
        this.groovyCompileCacheToDisk = groovyCompileCacheToDisk
    }

    void setComponentInfoMap(LinkedHashMap<String, ComponentInfo> componentInfoMap) {
        this.componentInfoMap = componentInfoMap
    }

    @Override
    synchronized Class compileGroovy(String script, String className) {
        boolean hasClassName = className != null && !className.isEmpty()
        if (groovyCompileCacheToDisk && hasClassName) {
            // if the className already exists just return it
            try {
                Class existingClass = groovyClassLoader.loadClass(className)
                if (existingClass != null) return existingClass
            } catch (ClassNotFoundException e) { /* ignore */ }

            CompilationUnit compileUnit = new CompilationUnit(groovyCompilerConf, null, groovyClassLoader)
            compileUnit.addSource(className, script)
            compileUnit.compile() // just through Phases.CLASS_GENERATION?

            List compiledClasses = compileUnit.getClasses()
            if (compiledClasses.size() > 1) logger.warn("WARNING: compiled groovy class ${className} got ${compiledClasses.size()} classes")
            Class returnClass = null
            for (Object compiledClass in compiledClasses) {
                GroovyClass groovyClass = (GroovyClass) compiledClass
                String compiledName = groovyClass.getName()
                byte[] compiledBytes = groovyClass.getBytes()
                // NOTE: this is the same step we'd use when getting bytes from disk
                Class curClass = null
                try { curClass = groovyClassLoader.loadClass(compiledName) } catch (ClassNotFoundException e) { /* ignore */ }
                if (curClass == null) curClass = groovyClassLoader.defineClass(compiledName, compiledBytes)
                if (compiledName.equals(className)) {
                    returnClass = curClass
                } else {
                    logger.warn("Got compiled groovy class with name ${compiledName} not same as original class name ${className}")
                }
            }

            if (returnClass == null) logger.error("No errors in groovy compilation but got null Class for ${className}")
            return returnClass
        } else {
            // the simple approach, groovy compiles internally and don't save to disk/etc
            return hasClassName ? groovyClassLoader.parseClass(script, className) : groovyClassLoader.parseClass(script)
        }
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
                           PeakThreadCount:threadMXBean.getPeakThreadCount() ] as Map<String, Object>
//                                          ,
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

    protected static String stripVersionFromName(String name) {
        int lastDash = name.lastIndexOf("-")
        if (lastDash > 0 && lastDash < name.length() - 2 && Character.isDigit(name.charAt(lastDash + 1))) {
            return name.substring(0, lastDash)
        } else {
            return name
        }
    }
    protected static ResourceReference getResourceReference(String location) {
        // NOTE: somehow support other resource location types?
        // the ResourceFacade inits after components are loaded (so it is aware of initial components), so we can't get ResourceReferences from it
        return new UrlResourceReference().init(location)
    }

    static class ComponentInfo {
        ExecutionContextFactoryImpl ecfi
        String name, location, version
        Map versionMap = null
        ResourceReference componentRr
        Set<String> dependsOnNames = new LinkedHashSet<String>()
        ComponentInfo(String baseLocation, MNode componentNode, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            String curLoc = null
            if (baseLocation) curLoc = baseLocation + "/" + componentNode.attribute("location")
            init(curLoc, componentNode)
        }
        ComponentInfo(String location, ExecutionContextFactoryImpl ecfi) {
            this.ecfi = ecfi
            init(location, null)
        }
        protected void init(String specLoc, MNode origNode) {
            location = specLoc ?: origNode?.attribute("location")
            if (!location) throw new IllegalArgumentException("Cannot init component with no location (not specified or found in component.@location)")

            // support component zip files, expand now and replace name and location
            if (location.endsWith(".zip")) {
                ResourceReference zipRr = getResourceReference(location)
                if (!zipRr.supportsExists()) throw new IllegalArgumentException("Could component location ${location} does not support exists, cannot use as a component location")
                // make sure corresponding directory does not exist
                String locNoZip = stripVersionFromName(location.substring(0, location.length() - 4))
                ResourceReference noZipRr = getResourceReference(locNoZip)
                if (zipRr.getExists() && !noZipRr.getExists()) {
                    // NOTE: could use getPath() instead of toExternalForm().substring(5) for file specific URLs, will work on Windows?
                    String zipPath = zipRr.getUrl().toExternalForm().substring(5)
                    File zipFile = new File(zipPath)
                    String targetDirLocation = zipFile.getParent()
                    logger.info("Expanding component archive ${zipRr.getFileName()} to ${targetDirLocation}")

                    ZipInputStream zipIn = new ZipInputStream(zipRr.openStream())
                    try {
                        ZipEntry entry = zipIn.getNextEntry()
                        // iterates over entries in the zip file
                        while (entry != null) {
                            ResourceReference entryRr = getResourceReference(targetDirLocation + '/' + entry.getName())
                            String filePath = entryRr.getUrl().toExternalForm().substring(5)
                            if (entry.isDirectory()) {
                                File dir = new File(filePath)
                                dir.mkdir()
                            } else {
                                OutputStream os = new FileOutputStream(filePath)
                                ObjectUtilities.copyStream(zipIn, os)
                            }
                            zipIn.closeEntry()
                            entry = zipIn.getNextEntry()
                        }
                    } finally {
                        zipIn.close()
                    }
                }

                // assumes zip contains a single directory named the same as the component name (without version)
                location = locNoZip
            }

            // clean up the location
            if (location.endsWith('/')) location = location.substring(0, location.length()-1)
            int lastSlashIndex = location.lastIndexOf('/')
            if (lastSlashIndex < 0) {
                // if this happens the component directory is directly under the runtime directory, so prefix loc with that
                if(ecfi.runtimePath) {
                    location = ecfi.runtimePath + '/' + location
                }
                lastSlashIndex = location.lastIndexOf('/')
            }
            // set the default component name, version
            name = location.substring(lastSlashIndex+1)
            version = "unknown"

            // make sure directory exists
            componentRr = getResourceReference(location)
            if (!componentRr.supportsExists()) throw new IllegalArgumentException("Could component location ${location} does not support exists, cannot use as a component location")
            if (!componentRr.getExists()) throw new IllegalArgumentException("Could not find component directory at: ${location}")
            if (!componentRr.isDirectory()) throw new IllegalArgumentException("Component location is not a directory: ${location}")

            // see if there is a component.xml file, if so use that as the componentNode instead of origNode
            ResourceReference compXmlRr = componentRr.getChild("component.xml")
            MNode componentNode = compXmlRr.exists ? MNode.parse(compXmlRr) : origNode
            if (componentNode != null) {
                String nameAttr = componentNode.attribute("name")
                if (nameAttr) name = nameAttr
                String versionAttr = componentNode.attribute("version")
                if (versionAttr) version = SystemBinding.expand(versionAttr)
                if (componentNode.hasChild("depends-on")) for (MNode dependsOnNode in componentNode.children("depends-on"))
                    dependsOnNames.add(dependsOnNode.attribute("name"))
            }

            ResourceReference versionJsonRr = componentRr.getChild("version.json")
            if (versionJsonRr.exists) {
                try {
                    versionMap = (Map) new JsonSlurper().parseText(versionJsonRr.getText())
                } catch (Exception e) {
                    logger.warn("Error parsing ${versionJsonRr.location}", e)
                }
            }
        }

        List<String> getRecursiveDependencies() {
            List<String> dependsOnList = []
            for (String dependsOnName in dependsOnNames) {
                ComponentInfo depCompInfo = ecfi.componentInfoMap.get(dependsOnName)
                if (depCompInfo == null) throw new IllegalArgumentException("Component ${name} depends on component ${dependsOnName} which is not initialized; try running 'gradle getDepends'")
                List<String> childDepList = depCompInfo.getRecursiveDependencies()
                for (String childDep in childDepList) if (!dependsOnList.contains(childDep)) dependsOnList.add(childDep)
                if (!dependsOnList.contains(dependsOnName)) dependsOnList.add(dependsOnName)
            }
            return dependsOnList
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

    // ========================================================
    // ========== Configuration File Merging Methods ==========
    // ========================================================

    protected static void mergeConfigNodes(MNode baseNode, MNode overrideNode) {
        baseNode.mergeChildrenByKey(overrideNode, "default-property", "name", null)
        baseNode.mergeChildWithChildKey(overrideNode, "tools", "tool-factory", "class", null)
        baseNode.mergeChildWithChildKey(overrideNode, "cache-list", "cache", "name", null)

        if (overrideNode.hasChild("server-stats")) {
            // the artifact-stats nodes have 2 keys: type, sub-type; can't use the normal method
            MNode ssNode = baseNode.first("server-stats")
            MNode overrideSsNode = overrideNode.first("server-stats")
            // override attributes for this node
            ssNode.attributes.putAll(overrideSsNode.attributes)
            for (MNode childOverrideNode in overrideSsNode.children("artifact-stats")) {
                String type = childOverrideNode.attribute("type")
                String subType = childOverrideNode.attribute("sub-type")
                MNode childBaseNode = ssNode.first({ MNode it -> it.name == "artifact-stats" && it.attribute("type") == type &&
                        (it.attribute("sub-type") == subType || (!it.attribute("sub-type") && !subType)) })
                if (childBaseNode) {
                    // merge the node attributes
                    childBaseNode.attributes.putAll(childOverrideNode.attributes)
                } else {
                    // no matching child base node, so add a new one
                    ssNode.append(childOverrideNode)
                }
            }
        }

        baseNode.mergeChildWithChildKey(overrideNode, "webapp-list", "webapp", "name",
                { MNode childBaseNode, MNode childOverrideNode -> mergeWebappChildNodes(childBaseNode, childOverrideNode) })

        baseNode.mergeChildWithChildKey(overrideNode, "artifact-execution-facade", "artifact-execution", "type", null)

        if (overrideNode.hasChild("user-facade")) {
            MNode ufBaseNode = baseNode.first("user-facade")
            MNode ufOverrideNode = overrideNode.first("user-facade")
            ufBaseNode.attributes.putAll(ufOverrideNode.attributes)
            ufBaseNode.mergeSingleChild(ufOverrideNode, "password")
            ufBaseNode.mergeSingleChild(ufOverrideNode, "login-key")
            ufBaseNode.mergeSingleChild(ufOverrideNode, "login")
        }

        if (overrideNode.hasChild("transaction-facade")) {
            MNode tfBaseNode = baseNode.first("transaction-facade")
            MNode tfOverrideNode = overrideNode.first("transaction-facade")
            tfBaseNode.attributes.putAll(tfOverrideNode.attributes)
            tfBaseNode.mergeSingleChild(tfOverrideNode, "server-jndi")
            tfBaseNode.mergeSingleChild(tfOverrideNode, "transaction-jndi")
            tfBaseNode.mergeSingleChild(tfOverrideNode, "transaction-internal")
        }

        if (overrideNode.hasChild("resource-facade")) {
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "resource-reference", "scheme", null)
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "template-renderer", "extension", null)
            baseNode.mergeChildWithChildKey(overrideNode, "resource-facade", "script-runner", "extension", null)
        }

        if (overrideNode.hasChild("screen-facade")) {
            baseNode.mergeChildWithChildKey(overrideNode, "screen-facade", "screen-text-output", "type", null)
            baseNode.mergeChildWithChildKey(overrideNode, "screen-facade", "screen", "location", {
                MNode childBaseNode, MNode childOverrideNode -> childBaseNode.mergeChildrenByKey(childOverrideNode, "subscreens-item", "name", null) })
        }

        if (overrideNode.hasChild("service-facade")) {
            MNode sfBaseNode = baseNode.first("service-facade")
            MNode sfOverrideNode = overrideNode.first("service-facade")
            sfBaseNode.mergeNodeWithChildKey(sfOverrideNode, "service-location", "name", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "service-type", "name", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "service-file", "location", null)
            sfBaseNode.mergeChildrenByKey(sfOverrideNode, "startup-service", "name", null)

            // handle thread-pool
            MNode tpOverrideNode = sfOverrideNode.first("thread-pool")
            if (tpOverrideNode) {
                MNode tpBaseNode = sfBaseNode.first("thread-pool")
                if (tpBaseNode) {
                    tpBaseNode.mergeNodeWithChildKey(tpOverrideNode, "run-from-pool", "name", null)
                } else {
                    sfBaseNode.append(tpOverrideNode)
                }
            }

            // handle jms-service, just copy all over
            for (MNode jsOverrideNode in sfOverrideNode.children("jms-service")) {
                sfBaseNode.append(jsOverrideNode)
            }
        }

        if (overrideNode.hasChild("entity-facade")) {
            MNode efBaseNode = baseNode.first("entity-facade")
            MNode efOverrideNode = overrideNode.first("entity-facade")
            efBaseNode.mergeNodeWithChildKey(efOverrideNode, "datasource", "group-name", { MNode childBaseNode, MNode childOverrideNode ->
                // handle the jndi-jdbc and inline-jdbc nodes: if either exist in override have it totally remove both from base, then copy over
                if (childOverrideNode.hasChild("jndi-jdbc") || childOverrideNode.hasChild("inline-jdbc")) {
                    childBaseNode.remove("jndi-jdbc")
                    childBaseNode.remove("inline-jdbc")

                    if (childOverrideNode.hasChild("inline-jdbc")) {
                        childBaseNode.append(childOverrideNode.first("inline-jdbc"))
                    } else if (childOverrideNode.hasChild("jndi-jdbc")) {
                        childBaseNode.append(childOverrideNode.first("jndi-jdbc"))
                    }
                }
            })
            efBaseNode.mergeSingleChild(efOverrideNode, "server-jndi")
            // for load-entity and load-data just copy over override nodes
            for (MNode copyNode in efOverrideNode.children("load-entity")) efBaseNode.append(copyNode)
            for (MNode copyNode in efOverrideNode.children("load-data")) efBaseNode.append(copyNode)
        }

        if (overrideNode.hasChild("database-list")) {
            baseNode.mergeChildWithChildKey(overrideNode, "database-list", "dictionary-type", "type", null)
            // handle database-list -> database, database -> database-type@type
            baseNode.mergeChildWithChildKey(overrideNode, "database-list", "database", "name",
                    { MNode childBaseNode, MNode childOverrideNode -> childBaseNode.mergeNodeWithChildKey(childOverrideNode, "database-type", "type", null) })
        }

        baseNode.mergeChildWithChildKey(overrideNode, "repository-list", "repository", "name", {
            MNode childBaseNode, MNode childOverrideNode -> childBaseNode.mergeChildrenByKey(childOverrideNode, "init-param", "name", null) })

        // NOTE: don't merge component-list node, done separately (for runtime config only, and before component config merges)
    }

    protected static void mergeConfigComponentNodes(MNode baseNode, MNode overrideNode) {
        if (overrideNode.hasChild("component-list")) {
            if (!baseNode.hasChild("component-list")) baseNode.append("component-list", null)
            MNode baseComponentNode = baseNode.first("component-list")
            for (MNode copyNode in overrideNode.first("component-list").children) baseComponentNode.append(copyNode)
        }
    }

    protected static void mergeWebappChildNodes(MNode baseNode, MNode overrideNode) {
        baseNode.mergeChildrenByKey(overrideNode, "root-screen", "host", null)
        baseNode.mergeChildrenByKey(overrideNode, "error-screen", "error", null)
        // handle webapp -> first-hit-in-visit[1], after-request[1], before-request[1], after-login[1], before-logout[1]
        mergeWebappActions(baseNode, overrideNode, "first-hit-in-visit")
        mergeWebappActions(baseNode, overrideNode, "after-request")
        mergeWebappActions(baseNode, overrideNode, "before-request")
        mergeWebappActions(baseNode, overrideNode, "after-login")
        mergeWebappActions(baseNode, overrideNode, "before-logout")
        mergeWebappActions(baseNode, overrideNode, "after-startup")
        mergeWebappActions(baseNode, overrideNode, "before-shutdown")

        baseNode.mergeChildrenByKey(overrideNode, "filter", "name", { MNode childBaseNode, MNode childOverrideNode ->
            childBaseNode.mergeChildrenByKey(childOverrideNode, "init-param", "name", null)
            for (MNode upNode in overrideNode.children("url-pattern")) childBaseNode.append(upNode.deepCopy(null))
            for (MNode upNode in overrideNode.children("dispatcher")) childBaseNode.append(upNode.deepCopy(null))
        })
        baseNode.mergeChildrenByKey(overrideNode, "listener", "class", null)
        baseNode.mergeChildrenByKey(overrideNode, "servlet", "name", { MNode childBaseNode, MNode childOverrideNode ->
            childBaseNode.mergeChildrenByKey(childOverrideNode, "init-param", "name", null)
            for (MNode upNode in overrideNode.children("url-pattern")) childBaseNode.append(upNode.deepCopy(null))
        })
        baseNode.mergeSingleChild(overrideNode, "session-config")

        baseNode.mergeChildrenByKey(overrideNode, "endpoint", "path", null)

        baseNode.mergeChildrenByKeys(overrideNode, "response-header", null, "type", "name")
    }

    protected static void mergeWebappActions(MNode baseWebappNode, MNode overrideWebappNode, String childNodeName) {
        List<MNode> overrideActionNodes = overrideWebappNode.first(childNodeName)?.first("actions")?.children
        if (overrideActionNodes) {
            MNode childNode = baseWebappNode.first(childNodeName)
            if (childNode == null) childNode = baseWebappNode.append(childNodeName, null)
            MNode actionsNode = childNode.first("actions")
            if (actionsNode == null) actionsNode = childNode.append("actions", null)

            for (MNode overrideActionNode in overrideActionNodes) actionsNode.append(overrideActionNode)
        }
    }

    MNode getWebappNode(String webappName) { return confXmlRoot.first("webapp-list")
            .first({ MNode it -> it.name == "webapp" && it.attribute("name") == webappName }) }

    AtomicBoolean getDestroyed() {
        return destroyed
    }

}

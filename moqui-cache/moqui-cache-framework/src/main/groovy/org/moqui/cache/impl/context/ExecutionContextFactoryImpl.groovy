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
package org.moqui.cache.impl.context

import groovy.transform.CompileStatic
import org.moqui.cache.context.CacheFacade
import org.moqui.cache.context.ExecutionContextFactory
import org.moqui.cache.context.ToolFactory
import org.moqui.cache.impl.context.CacheFacadeImpl
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode
import org.moqui.util.SystemBinding
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.annotation.Nonnull
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.jar.Attributes
import java.util.jar.Manifest

@CompileStatic
class ExecutionContextFactoryImpl implements ExecutionContextFactory {
    protected final static Logger logger = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

    private AtomicBoolean destroyed = new AtomicBoolean(false)

    protected String runtimePath
    @SuppressWarnings("GrFinalVariableAccess") protected final String runtimeConfPath
    @SuppressWarnings("GrFinalVariableAccess") protected final MNode confXmlRoot
    protected String moquiVersion = ""

    protected final LinkedHashMap<String, ToolFactory> toolFactoryMap = new LinkedHashMap<>()

    // ======== Permanent Delegated Facades ========
    @SuppressWarnings("GrFinalVariableAccess") public final CacheFacadeImpl cacheFacade

    /**
     * This constructor gets runtime directory and conf file location from a properties file on the classpath so that
     * it can initialize on its own. This is the constructor to be used by the ServiceLoader in the Moqui.java file,
     * or by init methods in a servlet or context filter or OSGi component or Spring component or whatever.
     */
    ExecutionContextFactoryImpl() {
        long initStartTime = System.currentTimeMillis()

        runtimeConfPath = "MoquiCacheConf.xml";
        URL confUrl = this.class.getClassLoader().getResource(runtimeConfPath)
        if (confUrl == null) throw new IllegalArgumentException("Could not find MoquiCacheConf.xml file on the classpath")

        // initialize all configuration, get various conf files merged and load components
        MNode runtimeConfXmlRoot = MNode.parse(confUrl.toString(), confUrl.newInputStream())
        MNode baseConfigNode = initBaseConfig(runtimeConfXmlRoot)
        // init the configuration (merge from component and runtime conf files)
        confXmlRoot = initConfig(baseConfigNode, runtimeConfXmlRoot)

        preFacadeInit()

        // this init order is important as some facades will use others
        cacheFacade = new CacheFacadeImpl(this)
        logger.info("Cache Facade initialized")
//        loggerFacade = new LoggerFacadeImpl(this)
        // logger.info("Logger Facade initialized")
//        resourceFacade = new ResourceFacadeImpl(this)
//        logger.info("Resource Facade initialized")

        postFacadeInit()

        logger.info("Execution Context Factory initialized in ${(System.currentTimeMillis() - initStartTime)/1000} seconds")
    }

    protected MNode initBaseConfig(MNode runtimeConfXmlRoot) {
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
        // System.setProperty("moqui.conf", runtimeConfPath)

        logger.info("Initializing Moqui Framework version ${moquiVersion ?: 'Unknown'}\n - runtime directory: ${this.runtimePath}\n - runtime config:    ${this.runtimeConfPath}")

        URL defaultConfUrl = this.class.getClassLoader().getResource("MoquiDefaultConf.xml")
        if (defaultConfUrl == null) throw new IllegalArgumentException("Could not find MoquiDefaultConf.xml file on the classpath")
        MNode newConfigXmlRoot = MNode.parse(defaultConfUrl.toString(), defaultConfUrl.newInputStream())

        return newConfigXmlRoot
    }

    protected MNode initConfig(MNode baseConfigNode, MNode runtimeConfXmlRoot) {

        // merge the runtime conf file into the default one to override any settings (they both have the same root node, go from there)
        logger.info("Merging runtime configuration at ${runtimeConfPath}")
        mergeConfigNodes(baseConfigNode, runtimeConfXmlRoot)

        String projectConfigFile = "MoquiConf.xml";
        URL confUrl = this.class.getClassLoader().getResource(projectConfigFile);
        if (confUrl != null){
            logger.info("Merging MoquiConf.xml")
            MNode compXmlNode = MNode.parse(confUrl.toString(), confUrl.newInputStream())
            mergeConfigNodes(baseConfigNode, compXmlNode)
        }

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


    private void preFacadeInit() {

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
                tf.preFacadeInit(this)
                toolFactoryMap.put(tf.getName(), tf)
            } catch (Throwable t) {
                logger.error("Error loading ToolFactory with class ${tfClass}", t)
            }
        }
    }

    private void postFacadeInit() {
        // Run init() in ToolFactory implementations from tools.tool-factory elements
        for (ToolFactory tf in toolFactoryMap.values()) {
            logger.info("Initializing ToolFactory: ${tf.getName()}")
            try {
                tf.init(this)
            } catch (Throwable t) {
                logger.error("Error initializing ToolFactory ${tf.getName()}", t)
            }
        }

        // all config loaded, save memory by clearing the parsed MNode cache, especially for production mode
        MNode.clearParsedNodeCache()
        // bunch of junk in memory, trigger gc (to happen soon, when JVM decides, not immediate)
        System.gc()
    }

    @Override void destroy() {
        if (destroyed.getAndSet(true)) {
            logger.warn("Not destroying ExecutionContextFactory, already destroyed (or destroying)")
            return
        }

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

        // this destroy order is important as some use others so must be destroyed first
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

    MNode getConfXmlRoot() { return confXmlRoot }

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


    @Override @Nonnull CacheFacade getCache() { cacheFacade }

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
}

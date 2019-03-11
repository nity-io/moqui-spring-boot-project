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
import org.moqui.util.CollectionUtilities
import org.moqui.util.MNode
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.annotation.Nonnull
import java.util.concurrent.atomic.AtomicBoolean

@CompileStatic
class ExecutionContextFactoryImpl implements ExecutionContextFactory {
    protected final static Logger logger = LoggerFactory.getLogger(ExecutionContextFactoryImpl.class)
    protected final static boolean isTraceEnabled = logger.isTraceEnabled()

    private AtomicBoolean destroyed = new AtomicBoolean(false)

    @SuppressWarnings("GrFinalVariableAccess") protected final MNode confXmlRoot

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

        String fileName = "MoquiCacheConf.xml";
        URL confUrl = this.class.getClassLoader().getResource(fileName)
        if (confUrl == null) throw new IllegalArgumentException("Could not find MoquiCacheConf.xml file on the classpath")
        confXmlRoot = MNode.parse(confUrl.toString(), confUrl.newInputStream())

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


}

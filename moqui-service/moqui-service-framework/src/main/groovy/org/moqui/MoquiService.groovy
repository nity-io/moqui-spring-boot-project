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
package org.moqui

import org.moqui.context.ArtifactExecutionInfo
import org.moqui.context.ExecutionContext
import org.moqui.context.ServiceExecutionContextFactory
import org.moqui.entity.EntityDataLoader
import org.moqui.service.ServiceFacade
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.servlet.ServletContext

/**
 * This is a base class that implements a simple way to access the Moqui framework for use in simple deployments where
 * there is nothing available like a webapp or an OSGi component.
 *
 * In deployments where a static reference to the ExecutionContextFactory is not helpful, or not possible, this does
 * not need to be used and the ExecutionContextFactory instance should be referenced and used from somewhere else.
 */

@SuppressWarnings("unused")
public class MoquiService {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiEntity.class);

    private static ServiceExecutionContextFactory activeExecutionContextFactory = null;

    public static void dynamicInit(ServiceExecutionContextFactory executionContextFactory) {
        if (activeExecutionContextFactory != null && !activeExecutionContextFactory.isDestroyed())
            throw new IllegalStateException("Active ExecutionContextFactory already in place, cannot set one dynamically.");
        activeExecutionContextFactory = executionContextFactory;

        // check for an empty DB
        if (executionContextFactory.checkEmptyDb()) {
            logger.warn("Data loaded into empty DB");
        }
    }
    public static <K extends ServiceExecutionContextFactory> K dynamicInit(Class<K> ecfClass, ServletContext sc)
            throws InstantiationException, IllegalAccessException {
        if (activeExecutionContextFactory != null && !activeExecutionContextFactory.isDestroyed())
            throw new IllegalStateException("Active ExecutionContextFactory already in place, cannot set one dynamically.");

        K newEcf = ecfClass.newInstance();
        activeExecutionContextFactory = newEcf;

        if (sc != null) {
            // tell ECF about the ServletContext
            newEcf.initServletContext(sc);
            // set SC attribute and Moqui class static reference
            sc.setAttribute("executionContextFactory", newEcf);
        }

        return newEcf;
    }
    public static <K extends ServiceExecutionContextFactory> K dynamicReInit(Class<K> ecfClass, ServletContext sc)
            throws InstantiationException, IllegalAccessException {

        // handle Servlet pause then resume taking requests after by removing executionContextFactory attribute
        if (sc.getAttribute("executionContextFactory") != null) sc.removeAttribute("executionContextFactory");

        if (activeExecutionContextFactory != null) {
            if (!activeExecutionContextFactory.isDestroyed()) {
                activeExecutionContextFactory.destroyActiveExecutionContext();
                activeExecutionContextFactory.destroy();
            }
            activeExecutionContextFactory = null;
            System.gc();
        }

        return dynamicInit(ecfClass, sc);
    }

    public static ServiceExecutionContextFactory getExecutionContextFactory() { return activeExecutionContextFactory; }
    
    public static ExecutionContext getExecutionContext() {
        return activeExecutionContextFactory.getExecutionContext();
    }

    static ServiceFacade getService() {
        return getExecutionContextFactory().getService()
    }

    /** This method is meant to be run from a command-line interface and handle data loading in a generic way.
     * @param argMap Arguments, generally from command line, to configure this data load.
     */
    public static void loadData(Map<String, String> argMap) {
        // make sure we have a factory, even if moqui.init.static != true
        if (activeExecutionContextFactory == null){
            throw new BaseException("ServiceExecutionContextFactory hasn't init.")
        }

        ExecutionContext ec = getExecutionContext();
        def serviceExecutionContextFactory = getExecutionContextFactory()
        // disable authz and add an artifact set to anonymous authorized all
        ec.getArtifactExecution().disableAuthz();
        ec.getArtifactExecution().push("loadData", ArtifactExecutionInfo.AT_OTHER, ArtifactExecutionInfo.AUTHZA_ALL, false);
        ec.getArtifactExecution().setAnonymousAuthorizedAll();

        // login anonymous user
        ec.getUser().loginAnonymousIfNoUser();

        // set the data load parameters
        EntityDataLoader edl = serviceExecutionContextFactory.getService().makeDataLoader();
        if (argMap.containsKey("types"))
            edl.dataTypes(new HashSet<>(Arrays.asList(argMap.get("types").split(","))));
        if (argMap.containsKey("components"))
            edl.componentNameList(Arrays.asList(argMap.get("components").split(",")));
        if (argMap.containsKey("location")) edl.location(argMap.get("location"));
        if (argMap.containsKey("timeout")) edl.transactionTimeout(Integer.valueOf(argMap.get("timeout")));
        if (argMap.containsKey("raw") || argMap.containsKey("dummy-fks")) edl.dummyFks(true);
        if (argMap.containsKey("raw") || argMap.containsKey("use-try-insert")) edl.useTryInsert(true);
        if (argMap.containsKey("raw") || argMap.containsKey("disable-eeca")) edl.disableEntityEca(true);
        if (argMap.containsKey("raw") || argMap.containsKey("disable-audit-log")) edl.disableAuditLog(true);

        // do the data load
        try {
            long startTime = System.currentTimeMillis();
            long records = edl.load();
            long totalSeconds = (System.currentTimeMillis() - startTime)/1000;
            logger.info("Loaded [" + records + "] records in " + totalSeconds + " seconds.");
        } catch (Throwable t) {
            System.out.println("Error loading data: " + t.toString());
            t.printStackTrace();
        }

        // cleanup and quit
//        activeExecutionContextFactory.destroyActiveExecutionContext();
//        activeExecutionContextFactory.destroy();
    }

    /** This should be called when it is known a context won't be used any more, such as at the end of a web request or service execution. */
    public static void destroyActiveExecutionContext() { activeExecutionContextFactory.destroyActiveExecutionContext(); }

    /** This should be called when the process is terminating to clean up framework and tool operations and resources. */
    public static void destroyActiveExecutionContextFactory() { activeExecutionContextFactory.destroy(); }

}

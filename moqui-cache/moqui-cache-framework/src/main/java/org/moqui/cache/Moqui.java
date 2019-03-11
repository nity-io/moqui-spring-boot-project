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
package org.moqui.cache;

import org.moqui.cache.context.ExecutionContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * This is a base class that implements a simple way to access the Moqui framework for use in simple deployments where
 * there is nothing available like a webapp or an OSGi component.
 *
 * In deployments where a static reference to the ExecutionContextFactory is not helpful, or not possible, this does
 * not need to be used and the ExecutionContextFactory instance should be referenced and used from somewhere else.
 */

@SuppressWarnings("unused")
public class Moqui {
    protected final static Logger logger = LoggerFactory.getLogger(Moqui.class);

    private static ExecutionContextFactory activeExecutionContextFactory = null;

    private static final ServiceLoader<ExecutionContextFactory> executionContextFactoryLoader =
            ServiceLoader.load(ExecutionContextFactory.class);
    static {
        // only do this if the moqui.init.static System property is true
        if ("true".equals(System.getProperty("moqui.init.static"))) {
            // initialize the activeExecutionContextFactory from configuration using java.util.ServiceLoader
            // the implementation class name should be in: "META-INF/services/org.moqui.context.ExecutionContextFactory"
            activeExecutionContextFactory = executionContextFactoryLoader.iterator().next();
        }
    }

    public static void dynamicInit(ExecutionContextFactory executionContextFactory) {
        if (activeExecutionContextFactory != null && !activeExecutionContextFactory.isDestroyed())
            throw new IllegalStateException("Active ExecutionContextFactory already in place, cannot set one dynamically.");
        activeExecutionContextFactory = executionContextFactory;
    }
    public static <K extends ExecutionContextFactory> K dynamicInit(Class<K> ecfClass, ServletContext sc)
            throws InstantiationException, IllegalAccessException {
        if (activeExecutionContextFactory != null && !activeExecutionContextFactory.isDestroyed())
            throw new IllegalStateException("Active ExecutionContextFactory already in place, cannot set one dynamically.");

        K newEcf = ecfClass.newInstance();
        activeExecutionContextFactory = newEcf;

        return newEcf;
    }
    public static <K extends ExecutionContextFactory> K dynamicReInit(Class<K> ecfClass, ServletContext sc)
            throws InstantiationException, IllegalAccessException {

        // handle Servlet pause then resume taking requests after by removing executionContextFactory attribute
        if (sc.getAttribute("executionContextFactory") != null) sc.removeAttribute("executionContextFactory");

        return dynamicInit(ecfClass, sc);
    }

    public static ExecutionContextFactory getExecutionContextFactory() { return activeExecutionContextFactory; }

    /** This should be called when the process is terminating to clean up framework and tool operations and resources. */
    public static void destroyActiveExecutionContextFactory() { activeExecutionContextFactory.destroy(); }

    /** This method is meant to be run from a command-line interface and handle data loading in a generic way.
     * @param argMap Arguments, generally from command line, to configure this data load.
     */
    public static void loadData(Map<String, String> argMap) {

    }
}

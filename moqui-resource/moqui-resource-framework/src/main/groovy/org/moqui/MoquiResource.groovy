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
package org.moqui;

import org.moqui.context.ExecutionContext;
import org.moqui.context.ResourceExecutionContextFactory
import org.moqui.context.ResourceFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import java.util.ServiceLoader;

/**
 * This is a base class that implements a simple way to access the Moqui framework for use in simple deployments where
 * there is nothing available like a webapp or an OSGi component.
 *
 * In deployments where a static reference to the ExecutionContextFactory is not helpful, or not possible, this does
 * not need to be used and the ExecutionContextFactory instance should be referenced and used from somewhere else.
 */

@SuppressWarnings("unused")
public class MoquiResource {
    protected final static Logger logger = LoggerFactory.getLogger(MoquiResource.class);

    private static ResourceExecutionContextFactory activeExecutionContextFactory = null;

    private static final ServiceLoader<ResourceExecutionContextFactory> executionContextFactoryLoader =
            ServiceLoader.load(ResourceExecutionContextFactory.class);
    static {
        // only do this if the moqui.init.static System property is true
        if ("true".equals(System.getProperty("moqui.init.static"))) {
            // initialize the activeExecutionContextFactory from configuration using java.util.ServiceLoader
            // the implementation class name should be in: "META-INF/services/org.moqui.context.ExecutionContextFactory"
            activeExecutionContextFactory = executionContextFactoryLoader.iterator().next();
        }
    }

    public static void dynamicInit(ResourceExecutionContextFactory executionContextFactory) {
        if (activeExecutionContextFactory != null && !activeExecutionContextFactory.isDestroyed())
            throw new IllegalStateException("Active ExecutionContextFactory already in place, cannot set one dynamically.");
        activeExecutionContextFactory = executionContextFactory;
    }
    public static <K extends ResourceExecutionContextFactory> K dynamicInit(Class<K> ecfClass, ServletContext sc)
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
    public static <K extends ResourceExecutionContextFactory> K dynamicReInit(Class<K> ecfClass, ServletContext sc)
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

    public static ResourceExecutionContextFactory getExecutionContextFactory() { return activeExecutionContextFactory; }

    public static ExecutionContext getExecutionContext() {
        return activeExecutionContextFactory.getExecutionContext();
    }

    static ResourceFacade getResource() {
        return getExecutionContextFactory().getResource()
    }

    /** This should be called when it is known a context won't be used any more, such as at the end of a web request or service execution. */
    public static void destroyActiveExecutionContext() { activeExecutionContextFactory.destroyActiveExecutionContext(); }

    /** This should be called when the process is terminating to clean up framework and tool operations and resources. */
    public static void destroyActiveExecutionContextFactory() { activeExecutionContextFactory.destroy(); }

}

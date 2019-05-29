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
package org.moqui.context;

import groovy.lang.GroovyClassLoader;
import org.moqui.util.MNode;

import javax.annotation.Nonnull;
import java.net.InetAddress;
import java.util.LinkedHashMap;

/**
 * Interface for the object that will be used to get an ExecutionContext object and manage framework life cycle.
 */
public interface ExecutionContextFactory {

    /** Get the ExecutionContext associated with the current thread or initialize one and associate it with the thread. */
    @Nonnull ExecutionContext getExecutionContext();

    @Nonnull
    ExecutionContext getEci();

    /** Destroy the active Execution Context. When another is requested in this thread a new one will be created. */
    void destroyActiveExecutionContext();

    /** Destroy this ExecutionContextFactory and all resources it uses (all facades, tools, etc) */
    void destroy();
    boolean isDestroyed();

    /** Get the path of the runtime directory */
    @Nonnull String getRuntimePath();
    @Nonnull String getMoquiVersion();

    MNode getConfXmlRoot();

    MNode getServerStatsNode();

    InetAddress getLocalhostAddress();

    /** Using an EC in multiple threads is dangerous as much of the ECI is not designed to be thread safe. */
    void useExecutionContextInThread(ExecutionContext eci);

    /** Get the named ToolFactory instance (loaded by configuration) */
    <V> ToolFactory<V> getToolFactory(@Nonnull String toolName);
    /** Get an instance object from the named ToolFactory instance (loaded by configuration); the instanceClass may be
     * null in scripts or other contexts where static typing is not needed */
    <V> V getTool(@Nonnull String toolName, Class<V> instanceClass, Object... parameters);

    /** Get a Map where each key is a component name and each value is the component's base location. */
    @Nonnull LinkedHashMap<String, String> getComponentBaseLocations();

    /** Get the framework ClassLoader, aware of all additional classes in runtime and in components. */
    @Nonnull ClassLoader getClassLoader();
    /** Get a GroovyClassLoader for runtime compilation, etc. */
    @Nonnull GroovyClassLoader getGroovyClassLoader();
    void setGroovyClassLoader(GroovyClassLoader groovyClassLoader);

    Class compileGroovy(String script, String className);
}

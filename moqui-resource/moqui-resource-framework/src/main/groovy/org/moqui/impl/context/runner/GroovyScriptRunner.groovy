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
package org.moqui.impl.context.runner

import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.InvokerHelper
import org.moqui.BaseException
import org.moqui.context.ExecutionContext
import org.moqui.context.ResourceExecutionContextFactory
import org.moqui.context.ScriptRunner
import org.moqui.impl.context.ResourceExecutionContextFactoryImpl
import org.moqui.util.StringUtilities

import javax.cache.Cache

@CompileStatic
class GroovyScriptRunner implements ScriptRunner {
    private ResourceExecutionContextFactoryImpl ecfi
    private Cache<String, Class> scriptGroovyLocationCache

    GroovyScriptRunner() { }

    @Override
    ScriptRunner init(ResourceExecutionContextFactory ecf) {
        this.ecfi = (ResourceExecutionContextFactoryImpl) ecf
        this.scriptGroovyLocationCache = ecfi.cacheFacade.getCache("resource.groovy.location", String.class, Class.class)
        return this
    }

    @Override
    Object run(String location, String method, ExecutionContext ec) {
        Script script = InvokerHelper.createScript(getGroovyByLocation(location), ec.contextBinding)
        Object result
        if (method != null && !method.isEmpty()) {
            result = script.invokeMethod(method, null)
        } else {
            result = script.run()
        }
        return result
    }

    @Override
    void destroy() { }

    Class getGroovyByLocation(String location) {
        Class gc = (Class) scriptGroovyLocationCache.get(location)
        if (gc == null) gc = loadGroovy(location)
        return gc
    }
    private synchronized Class loadGroovy(String location) {
        Class gc = (Class) scriptGroovyLocationCache.get(location)
        if (gc == null) {
            String groovyText = ecfi.resourceFacade.getLocationText(location, false)

            if (groovyText == null) {
                throw new BaseException("Can't find file, location: " + location);
            }

            String instancePurpose = System.getProperty("instance_purpose");
            if ("dev".equals(instancePurpose) || "test".equals(instancePurpose)) {
                gc = ecfi.compileGroovy(groovyText, location)
            } else {
                gc = ecfi.compileGroovy(groovyText, StringUtilities.cleanStringForJavaName(location))
            }
            scriptGroovyLocationCache.put(location, gc)
        }
        return gc
    }
}

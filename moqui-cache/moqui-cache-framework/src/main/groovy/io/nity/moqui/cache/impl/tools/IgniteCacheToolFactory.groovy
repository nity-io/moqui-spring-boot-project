/*
 * Copyright 2019 The nity.io Moqui Spring Boot Project Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.nity.moqui.cache.impl.tools

import groovy.transform.CompileStatic
import org.moqui.cache.MoquiCache
import org.moqui.cache.context.ExecutionContextFactory
import org.moqui.context.ToolFactory
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import javax.cache.CacheManager
import javax.cache.Caching
import javax.cache.spi.CachingProvider

/** A factory for getting a Ignite CacheManager; this has no compile time dependency on Ignite, just add the dependencies
 * Current version:
 *     implementation 'org.apache.ignite:ignite-core:2.7.0'
 *     implementation 'org.apache.ignite:ignite-spring:2.7.0'
 */
@CompileStatic
class IgniteCacheToolFactory implements ToolFactory<CacheManager> {
    protected final static Logger logger = LoggerFactory.getLogger(IgniteCacheToolFactory.class)
    final static String TOOL_NAME = "IgniteCache"

    protected ExecutionContextFactory ecf = null

    protected CacheManager cacheManager = null

    /** Default empty constructor */
    IgniteCacheToolFactory() { }

    @Override
    String getName() { return TOOL_NAME }

    @Override
    void init() { }

    @Override
    void preFacadeInit() {
        this.ecf = MoquiCache.getExecutionContextFactory()
        ClassLoader cl = Thread.currentThread().getContextClassLoader()
        CachingProvider providerInternal = Caching.getCachingProvider("org.apache.ignite.cache.CachingProvider", cl)
        URL cmUrl = cl.getResource("example-persistent-store.xml")
        logger.info("Ignite config URI: ${cmUrl}")
        cacheManager = providerInternal.getCacheManager(cmUrl.toURI(), cl)
        logger.info("Initialized Ignite CacheManager")
    }

    @Override
    CacheManager getInstance(Object... parameters) {
        if (cacheManager == null) throw new IllegalStateException("IgniteCacheToolFactory not initialized")
        return cacheManager
    }

    @Override
    void destroy() {
        // do nothing?
    }

    ExecutionContextFactory getEcf() { return ecf }
}

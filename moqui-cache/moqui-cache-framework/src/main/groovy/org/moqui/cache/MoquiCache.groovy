package org.moqui.cache

import org.moqui.context.ExecutionContextFactory
import org.moqui.cache.impl.context.CacheExecutionContextFactoryImpl
import org.moqui.context.CacheFacade

/**
 * MoquiCache Framework Resource Holder
 */
class MoquiCache {
    static ExecutionContextFactory getExecutionContextFactory() {
        return CacheExecutionContextFactoryImpl.getInstance();
    }

    static CacheFacade getCache() {
        return getExecutionContextFactory().getCache();
    }
}

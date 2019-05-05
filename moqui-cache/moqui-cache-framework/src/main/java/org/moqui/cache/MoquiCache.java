package org.moqui.cache;

import org.moqui.cache.context.CacheFacade;
import org.moqui.cache.context.ExecutionContextFactory;

/**
 * MoquiCache Framework Resource Holder
 */
public class MoquiCache {

    private static ExecutionContextFactory ecf = null;

    public static void init(ExecutionContextFactory ecf) {
        MoquiCache.ecf = ecf;
    }

    public static ExecutionContextFactory getExecutionContextFactory() {
        return ecf;
    }

    public static CacheFacade getCache() {
        return ecf.getCache();
    }
}

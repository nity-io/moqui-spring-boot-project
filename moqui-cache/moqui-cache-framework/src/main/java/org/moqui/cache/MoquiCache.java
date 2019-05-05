package org.moqui.cache;

import org.moqui.cache.context.CacheFacade;

/**
 * CacheFacade Holder
 */
public class MoquiCache {

    private static CacheFacade cacheFacade = null;

    public static void setCache(CacheFacade cacheFacade) {
        MoquiCache.cacheFacade = cacheFacade;
    }

    public static CacheFacade getCache() {
        return cacheFacade;
    }
}

package org.moqui;


import org.moqui.cache.context.CacheFacade;
import org.moqui.context.ExecutionContextFactory;
import org.moqui.entity.EntityFacade;
import org.moqui.service.ServiceFacade;

/**
 * MoquiEntity Framework Resource Holder
 */
public class MoquiEntity {

    private static ExecutionContextFactory ecf = null;

    public static void init(ExecutionContextFactory ecf) {
        MoquiEntity.ecf = ecf;
    }

    public static ExecutionContextFactory getExecutionContextFactory() {
        return ecf;
    }

    public static CacheFacade getCache() {
        return ecf.getCache();
    }

    public static EntityFacade getEntity() {
        return ecf.getEntity();
    }

    public static ServiceFacade getService() {
        return ecf.getService();
    }
}

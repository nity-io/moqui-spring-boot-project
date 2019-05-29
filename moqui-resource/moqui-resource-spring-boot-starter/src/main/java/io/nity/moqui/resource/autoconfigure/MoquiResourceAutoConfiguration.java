package io.nity.moqui.resource.autoconfigure;

import org.moqui.context.CacheFacade;
import org.moqui.context.LoggerFacade;
import org.moqui.context.ResourceFacade;
import org.moqui.impl.context.CacheFacadeImpl;
import org.moqui.impl.context.LoggerFacadeImpl;
import org.moqui.impl.context.ResourceExecutionContextFactoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MoquiResourceAutoConfiguration {

    private final CacheFacade cacheFacade;
    private final LoggerFacade loggerFacade;

    public MoquiResourceAutoConfiguration(CacheFacade cacheFacade, LoggerFacade loggerFacade) {
        this.cacheFacade = cacheFacade;
        this.loggerFacade = loggerFacade;
    }

    @Bean
    public ResourceExecutionContextFactoryImpl resourceExecutionContextFactoryImpl() {
        CacheFacadeImpl cacheFacadeImpl = (CacheFacadeImpl) cacheFacade;
        LoggerFacadeImpl loggerFacadeImpl = (LoggerFacadeImpl) loggerFacade;
        ResourceExecutionContextFactoryImpl resourceExecutionContextFactoryImpl = new ResourceExecutionContextFactoryImpl(cacheFacadeImpl, loggerFacadeImpl);

        return resourceExecutionContextFactoryImpl;
    }

    @Bean
    public ResourceFacade resourceFacade(ResourceExecutionContextFactoryImpl executionContextFactoryImpl) {
        ResourceFacade resourceFacade = executionContextFactoryImpl.getResource();

        return resourceFacade;
    }
}

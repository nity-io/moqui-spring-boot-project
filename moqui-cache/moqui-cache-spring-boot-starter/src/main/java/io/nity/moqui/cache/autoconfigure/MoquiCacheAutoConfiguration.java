package io.nity.moqui.cache.autoconfigure;

import org.moqui.cache.context.CacheFacade;
import org.moqui.cache.impl.context.ExecutionContextFactoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MoquiCacheAutoConfiguration {

    @Bean
    public ExecutionContextFactoryImpl executionContextFactoryImpl() {
        ExecutionContextFactoryImpl executionContextFactoryImpl = new ExecutionContextFactoryImpl();

        return executionContextFactoryImpl;
    }

    @Bean
    public CacheFacade cacheFacade(ExecutionContextFactoryImpl executionContextFactoryImpl) {
        CacheFacade cacheFacade = executionContextFactoryImpl.getCache();

        return cacheFacade;
    }

}

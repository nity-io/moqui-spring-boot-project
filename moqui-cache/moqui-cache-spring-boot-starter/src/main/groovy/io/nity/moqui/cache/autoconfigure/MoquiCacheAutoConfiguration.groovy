package io.nity.moqui.cache.autoconfigure

import groovy.transform.CompileStatic
import org.moqui.cache.context.CacheFacade
import org.moqui.cache.impl.context.ExecutionContextFactoryImpl
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@CompileStatic
@Configuration
class MoquiCacheAutoConfiguration {

    @Bean
    ExecutionContextFactoryImpl executionContextFactoryImpl() {
        ExecutionContextFactoryImpl executionContextFactoryImpl = new ExecutionContextFactoryImpl()

        return executionContextFactoryImpl
    }

    @Bean
    CacheFacade cacheFacade(ExecutionContextFactoryImpl executionContextFactoryImpl) {
        CacheFacade cacheFacade = executionContextFactoryImpl.getCache()

        return cacheFacade
    }

}

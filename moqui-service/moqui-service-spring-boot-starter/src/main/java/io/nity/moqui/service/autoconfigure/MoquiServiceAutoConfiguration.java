package io.nity.moqui.service.autoconfigure;

import org.moqui.impl.context.EntityExecutionContextFactoryImpl;
import org.moqui.impl.context.ServiceExecutionContextFactoryImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MoquiServiceAutoConfiguration {

    private final EntityExecutionContextFactoryImpl entityExecutionContextFactoryImpl;

    public MoquiServiceAutoConfiguration(EntityExecutionContextFactoryImpl entityExecutionContextFactoryImpl) {
        this.entityExecutionContextFactoryImpl = entityExecutionContextFactoryImpl;
    }

    @Bean
    public ServiceExecutionContextFactoryImpl serviceExecutionContextFactoryImpl() {
        ServiceExecutionContextFactoryImpl serviceExecutionContextFactory = new ServiceExecutionContextFactoryImpl();

        return serviceExecutionContextFactory;
    }

}

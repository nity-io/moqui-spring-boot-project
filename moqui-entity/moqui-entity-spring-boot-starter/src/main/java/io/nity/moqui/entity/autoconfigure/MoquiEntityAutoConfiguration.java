package io.nity.moqui.entity.autoconfigure;

import bitronix.tm.BitronixTransactionManager;
import org.moqui.cache.context.CacheFacade;
import org.moqui.cache.impl.context.CacheFacadeImpl;
import org.moqui.entity.EntityFacade;
import org.moqui.impl.context.ExecutionContextFactoryImpl;
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class MoquiEntityAutoConfiguration {

    private final CacheFacade cacheFacade;

    private final DataSource dataSource;

    private final JdbcProperties properties;
    private final BitronixTransactionManager transactionManager;


    public MoquiEntityAutoConfiguration(DataSource dataSource, JdbcProperties properties, CacheFacade cacheFacade, BitronixTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.cacheFacade = cacheFacade;
        this.transactionManager = transactionManager;
    }

    @Bean
    public ExecutionContextFactoryImpl entityExecutionContextFactoryImpl() {
        CacheFacadeImpl cacheFacadeImpl = (CacheFacadeImpl) this.cacheFacade;
        ExecutionContextFactoryImpl executionContextFactoryImpl = new ExecutionContextFactoryImpl(cacheFacadeImpl, this.dataSource, this.transactionManager);

        return executionContextFactoryImpl;
    }

    @Bean
    public EntityFacade entityFacade(ExecutionContextFactoryImpl executionContextFactoryImpl) {
        EntityFacade entityFacade = executionContextFactoryImpl.getEntity();

        return entityFacade;
    }

}

package io.nity.moqui.entity.autoconfigure;

import bitronix.tm.BitronixTransactionManager;
import org.moqui.context.CacheFacade;
import org.moqui.context.LoggerFacade;
import org.moqui.context.ResourceFacade;
import org.moqui.impl.context.CacheFacadeImpl;
import org.moqui.entity.EntityFacade;
import org.moqui.impl.context.EntityExecutionContextFactoryImpl;
import org.moqui.impl.context.LoggerFacadeImpl;
import org.moqui.impl.context.ResourceFacadeImpl;
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class MoquiEntityAutoConfiguration {

    private final CacheFacade cacheFacade;
    private final LoggerFacade loggerFacade;
    private final ResourceFacade resourceFacade;

    private final DataSource dataSource;

    private final JdbcProperties properties;
    private final BitronixTransactionManager transactionManager;


    public MoquiEntityAutoConfiguration(JdbcProperties properties, DataSource dataSource, CacheFacade cacheFacade, LoggerFacade loggerFacade, ResourceFacade resourceFacade, BitronixTransactionManager transactionManager) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.cacheFacade = cacheFacade;
        this.loggerFacade = loggerFacade;
        this.resourceFacade = resourceFacade;
        this.transactionManager = transactionManager;
    }

    @Bean
    public EntityExecutionContextFactoryImpl entityExecutionContextFactoryImpl() {
        CacheFacadeImpl cacheFacadeImpl = (CacheFacadeImpl) this.cacheFacade;
        LoggerFacadeImpl loggerFacadeImpl = (LoggerFacadeImpl) this.loggerFacade;
        ResourceFacadeImpl resourceFacadeImpl = (ResourceFacadeImpl) this.resourceFacade;
        EntityExecutionContextFactoryImpl executionContextFactoryImpl = new EntityExecutionContextFactoryImpl(cacheFacadeImpl, loggerFacadeImpl, resourceFacadeImpl, this.dataSource);

        return executionContextFactoryImpl;
    }

    @Bean
    public EntityFacade entityFacade(EntityExecutionContextFactoryImpl executionContextFactoryImpl) {
        EntityFacade entityFacade = executionContextFactoryImpl.getEntity();

        return entityFacade;
    }

}

package io.nity.moqui.logger.autoconfigure;

import org.moqui.MoquiLogger;
import org.moqui.context.LoggerFacade;
import org.moqui.impl.context.LoggerFacadeImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MoquiLoggerAutoConfiguration {

    @Bean
    public LoggerFacade loggerFacade() {
        LoggerFacade loggerFacade = new LoggerFacadeImpl();

        MoquiLogger.setLoggerFacade(loggerFacade);

        return loggerFacade;
    }

}

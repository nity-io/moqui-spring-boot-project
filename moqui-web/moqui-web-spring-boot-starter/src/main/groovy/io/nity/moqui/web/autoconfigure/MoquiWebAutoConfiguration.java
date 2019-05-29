package io.nity.moqui.web.autoconfigure;

import org.moqui.impl.context.ServiceExecutionContextFactoryImpl;
import org.moqui.impl.webapp.MoquiContextListener;
import org.moqui.impl.webapp.MoquiServlet;
import org.moqui.impl.webapp.MoquiSessionAttributeListener;
import org.moqui.impl.webapp.MoquiSessionListener;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.servlet.ServletContextListener;

@Configuration
public class MoquiWebAutoConfiguration {

    public MoquiWebAutoConfiguration(ServiceExecutionContextFactoryImpl serviceExecutionContextFactoryImpl) {

    }

    @Bean
    public ServletContextListener moquiContextListener() {
        return new MoquiContextListener();
    }

    @Bean
    public ServletListenerRegistrationBean sessionListenerRegistration() {
        ServletListenerRegistrationBean srb = new ServletListenerRegistrationBean();
        srb.setListener(new MoquiSessionListener());
        return srb;
    }

    @Bean
    public ServletListenerRegistrationBean sessionAttributeListenerRegistration() {
        ServletListenerRegistrationBean srb = new ServletListenerRegistrationBean();
        srb.setListener(new MoquiSessionAttributeListener());
        return srb;
    }

    @Bean
    public ServletRegistrationBean servletRegistrationBean() {
        return new ServletRegistrationBean(new MoquiServlet(), "/*");
    }

}

package com.netflix.karyon.example.jetty;

import javax.ws.rs.GET;
import javax.ws.rs.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.karyon.Karyon;
import com.netflix.karyon.KaryonFeatures;
import com.netflix.karyon.ProvisionDebugModule;
import com.netflix.karyon.admin.CoreAdminModule;
import com.netflix.karyon.admin.rest.AdminServerModule;
import com.netflix.karyon.admin.ui.AdminUIServerModule;
import com.netflix.karyon.archaius.ArchaiusKaryonModule;
import com.netflix.karyon.health.HealthIndicator;
import com.netflix.karyon.jetty.JettyModule;
import com.netflix.karyon.log4j.ArchaiusLog4J2ConfigurationModule;
import com.netflix.karyon.rxnetty.shutdown.ShutdownServerModule;
import com.netflix.karyon.spi.AbstractLifecycleListener;
import com.sun.jersey.guice.JerseyServletModule;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;

@Path("/hello")
public class HelloWorldApp extends AbstractLifecycleListener {
    private static final Logger LOG = LoggerFactory.getLogger(HelloWorldApp.class);
    
    public static void main(String[] args) throws Exception {
        Karyon.newBuilder()
            .addProfile("local")    // Setting the profile should normally done using system/env property: karyon.profiles=local
            .addModules(
                new ArchaiusKaryonModule()
                    .withConfigName("helloworld"),
                new ArchaiusLog4J2ConfigurationModule(),
                new CoreAdminModule(),
                new ProvisionDebugModule(),
                new JettyModule(),
                new AdminServerModule(),
                new AdminUIServerModule(),
                new ShutdownServerModule(),
                new JerseyServletModule() {
                    @Override
                    protected void configureServlets() {
                        serve("/REST/*").with(GuiceContainer.class);
                        bind(GuiceContainer.class).asEagerSingleton();
                        bind(ArchaiusEndpoint.class).asEagerSingleton();
                        bind(HelloWorldApp.class).asEagerSingleton();
                        bind(HealthIndicator.class).to(FooServiceHealthIndicator.class);
                      
                        bind(LongDelayService.class).asEagerSingleton();
                    }
                  
                    @Override
                    public String toString() {
                        return "JerseyServletModule";
                    }
                }
            )
            .disableFeature(KaryonFeatures.SHUTDOWN_ON_ERROR)
            .start()
            .awaitTermination();
    }
    
    @GET
    public String sayHello() {
        return "hello world";
    }

    @Override
    public void onStarted() {
        LOG.info("HelloWorldApp started");;
    }
}

package com.netflix.karyon.archaius;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.google.inject.AbstractModule;
import com.google.inject.Binding;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import com.google.inject.matcher.Matchers;
import com.google.inject.multibindings.MapBinder;
import com.google.inject.multibindings.Multibinder;
import com.netflix.archaius.CascadeStrategy;
import com.netflix.archaius.Config;
import com.netflix.archaius.ConfigListener;
import com.netflix.archaius.ConfigLoader;
import com.netflix.archaius.ConfigProxyFactory;
import com.netflix.archaius.ConfigReader;
import com.netflix.archaius.Decoder;
import com.netflix.archaius.DefaultConfigLoader;
import com.netflix.archaius.DefaultDecoder;
import com.netflix.archaius.DefaultPropertyFactory;
import com.netflix.archaius.PropertyFactory;
import com.netflix.archaius.annotations.Configuration;
import com.netflix.archaius.config.CompositeConfig;
import com.netflix.archaius.config.DefaultSettableConfig;
import com.netflix.archaius.config.EnvironmentConfig;
import com.netflix.archaius.config.MapConfig;
import com.netflix.archaius.config.SettableConfig;
import com.netflix.archaius.config.SystemConfig;
import com.netflix.archaius.exceptions.ConfigException;
import com.netflix.archaius.guice.ArchaiusConfiguration;
import com.netflix.archaius.guice.ConfigSeeder;
import com.netflix.archaius.guice.ConfigurationInjectingListener;
import com.netflix.archaius.inject.ApplicationLayer;
import com.netflix.archaius.inject.LibrariesLayer;
import com.netflix.archaius.inject.RemoteLayer;
import com.netflix.archaius.inject.RuntimeLayer;
import com.netflix.archaius.interpolate.ConfigStrLookup;
import com.netflix.archaius.readers.PropertiesConfigReader;
import com.netflix.karyon.AbstractPropertySource;
import com.netflix.karyon.PropertySource;
import com.netflix.karyon.TypeLiteralMatchers;
import com.netflix.karyon.annotations.Profiles;
import com.netflix.karyon.archaius.admin.ArchaiusAdminModule;
import com.netflix.karyon.spi.KaryonBinder;
import com.netflix.karyon.spi.KaryonModule;

/**
 * Module to set up archaius in a Karyon3 application. 
 * 
 * Note that this module has state and should therefore only be installed once.  Also,
 * note that configure() does follow Guice best practices and can therefore be called
 * multiple times without side effects.
 * 
 * By default this module will create a top level Config that is a CompositeConfig of the following layers,
 *  RUNTIME     - properties set from code
 *  REMOTE      - properties loaded from a remote source
 *  SYSTEM      - System properties
 *  ENVIRONMENT - Environment properties
 *  APPLICATION - Configuration loaded by the application
 *  LIBRARIES   - Configuration loaded by libraries used by the application
 * 
 * Runtime properties may be set in code by injecting and calling one of the
 * setters for,
 *  {@literal @}RuntimeLayer SettableConfig config
 *  
 * A remote configuration may be specified by binding to {@literal @}RemoteLayer Config
 * <code>
 * public class FooRemoteModule extends AbstractModule {
 *     {@literal @}Override
 *     protected void configure() {}
 *     
 *     {@literal @}Provides
 *     {@literal @}RemoteLayer
 *     // When setting up a remote configuration that need access to archaius's Config
 *     // make sure to inject the qualifier {@literal @}Raw otherwise the injector will fail
 *     // with a circular dependency error.  Note that the injected config will have 
 *     // system, environment and application properties loaded into it.
 *     Config getRemoteConfig({@literal @}Raw Config config) {
 *         return new FooRemoteConfigImplementaiton(config);
 *     }
 * }
 * </code>
 */
public final class ArchaiusKaryonModule extends AbstractModule implements KaryonModule {
    private static final String KARYON_PROFILES         = "karyon.profiles";
    
    private static final String DEFAULT_CONFIG_NAME     = "application";
    
    private static final String RUNTIME_LAYER_NAME      = "RUNTIME";
    private static final String REMOTE_LAYER_NAME       = "REMOTE";
    private static final String SYSTEM_LAYER_NAME       = "SYSTEM";
    private static final String ENVIRONMENT_LAYER_NAME  = "ENVIRONMENT";
    private static final String APPLICATION_LAYER_NAME  = "APPLICATION";
    private static final String LIBRARIES_LAYER_NAME    = "LIBRARIES";
    
    static {
        System.setProperty("archaius.default.configuration.class",      "com.netflix.archaius.bridge.StaticAbstractConfiguration");
        System.setProperty("archaius.default.deploymentContext.class",  "com.netflix.archaius.bridge.StaticDeploymentContext");
    }
    
    private String                  configName           = DEFAULT_CONFIG_NAME;
    private Config                  applicationOverrides = null;
    private Map<String, Config>     libraryOverrides     = new HashMap<>();
    private Set<Config>             runtimeOverrides     = new HashSet<>();
    private Class<? extends CascadeStrategy> cascadeStrategy = KaryonCascadeStrategy.class;

    private final SettableConfig  runtimeLayer;
    private final CompositeConfig remoteLayer;
    private final CompositeConfig applicationLayer;
    private final CompositeConfig librariesLayer;
    private final CompositeConfig rawConfig;

    private final String[] profiles;

    public ArchaiusKaryonModule() throws ConfigException {
        this.runtimeLayer     = new DefaultSettableConfig();
        this.applicationLayer = new CompositeConfig();
        this.librariesLayer   = new CompositeConfig();
        this.remoteLayer      = new CompositeConfig();
        this.rawConfig       = CompositeConfig.builder()
                .withConfig(RUNTIME_LAYER_NAME,      runtimeLayer)
                .withConfig(REMOTE_LAYER_NAME,       remoteLayer)
                .withConfig(SYSTEM_LAYER_NAME,       SystemConfig.INSTANCE)
                .withConfig(ENVIRONMENT_LAYER_NAME,  EnvironmentConfig.INSTANCE)
                .withConfig(APPLICATION_LAYER_NAME,  applicationLayer)
                .withConfig(LIBRARIES_LAYER_NAME,    librariesLayer)
                .build();
        
        this.profiles = this.rawConfig.getString(KARYON_PROFILES, "").split(",");
    }
    
    /**
     * Configuration name to use for property loading.  Default configuration
     * name is 'application'.  This value is injectable as
     *  
     * <code>{@literal @}Named("karyon.configName") String configName</code>
     * 
     * @param value
     * @return
     */
    public ArchaiusKaryonModule withConfigName(String value) {
        this.configName = value;
        return this;
    }
    
    public ArchaiusKaryonModule withApplicationOverrides(Properties prop) throws ConfigException {
        return withApplicationOverrides(MapConfig.from(prop));
    }
    
    public ArchaiusKaryonModule withApplicationOverrides(Config config) throws ConfigException {
        this.applicationOverrides = config;
        return this;
    }
    
    public ArchaiusKaryonModule withRuntimeOverrides(Properties prop) throws ConfigException {
        return withRuntimeOverrides(MapConfig.from(prop));
    }
    
    public ArchaiusKaryonModule withRuntimeOverrides(Config config) throws ConfigException {
        this.runtimeOverrides.add(config);
        return this;
    }
    
    public ArchaiusKaryonModule withLibraryOverrides(String name, Properties prop) throws ConfigException {
        return withLibraryOverrides(name, MapConfig.from(prop));
    }
    
    public ArchaiusKaryonModule withLibraryOverrides(String name, Config config) throws ConfigException {
        this.libraryOverrides.put(name, config);
        return this;
    }
    
    public ArchaiusKaryonModule withCascadeStrategy(Class<? extends CascadeStrategy> cascadeStrategy) {
        this.cascadeStrategy = cascadeStrategy;
        return this;
    }
    
    @Override
    protected void configure() {
        install(new ArchaiusAdminModule());
        
        bindListener(Matchers.any(), new ConfigurationInjectingListener());
        bind(ConfigLifecycleListener.class).asEagerSingleton();
        bind(CascadeStrategy.class).to(cascadeStrategy);
        
        for (String profile : profiles) {
            if (!profile.isEmpty()) 
                Multibinder.newSetBinder(binder(), String.class, Profiles.class).addBinding().toInstance(profile);
        }
        
        Multibinder.newSetBinder(binder(), ConfigReader.class)
            .addBinding().to(PropertiesConfigReader.class).in(Scopes.SINGLETON);
        
        MapBinder<String, Config> libraries = MapBinder.newMapBinder(binder(), String.class, Config.class, LibrariesLayer.class);
        for (Map.Entry<String, Config> c : libraryOverrides.entrySet()) {
            libraries.addBinding(c.getKey()).toInstance(c.getValue());
        }
        
    }

    @Provides
    @Singleton
    @RuntimeLayer
    SettableConfig getSettableConfig() {
        return runtimeLayer;
    }
    
    @Provides
    @Singleton
    @ApplicationLayer 
    CompositeConfig getApplicationLayer() {
        return applicationLayer;
    }

    @Provides
    @Singleton
    @LibrariesLayer
    CompositeConfig getLibrariesLayer() {
        return librariesLayer;
    }
    
    @Provides
    @Singleton
    PropertySource getPropertySource() {
        return new AbstractPropertySource() {
            @Override
            public String get(String key) {
                return rawConfig.getString(key, null);
            }

            @Override
            public String get(String key, String defaultValue) {
                return rawConfig.getString(key, defaultValue);
            }
        };
    }
    
    @Provides
    @Singleton
    @Raw
    Config getRawConfig() {
        return rawConfig;
    }
    
    @Provides
    @Singleton
    public Config getConfig(ConfigLoader loader, Injector injector) throws Exception {
        // load any runtime overrides
        for (Config c : runtimeOverrides) {
            runtimeLayer.setProperties(c);
        }
 
        if (applicationOverrides != null) {
            applicationLayer.addConfig("overrides", applicationOverrides);
        }
        
        // First load archaius2 configuration, which sets up all sort of env  
        librariesLayer.addConfig("archaius2",  loader
                .newLoader()
                .load("archaius2"));
            
        // 
        this.applicationLayer.addConfig("loaded",  loader
            .newLoader()
            .load(configName));

        // load any runtime overrides
        Binding<Config> binding = injector.getExistingBinding(Key.get(Config.class, RemoteLayer.class));
        if (binding != null) {
            // TODO: Ideally this should replace the remoteLayer in config but there is a bug in archaius
            //       where the replaced layer moves to the end of the hierarchy
            remoteLayer.addConfig("remote", binding.getProvider().get());
        }
        
        return rawConfig;
    }
        
    @Provides
    @Singleton
    ConfigLoader getLoader(
            @LibrariesLayer       CompositeConfig libraries,
            CascadeStrategy       cascadingStrategy,
            Set<ConfigReader>     readers
            ) throws ConfigException {
        
        return DefaultConfigLoader.builder()
            .withConfigReader(readers)
            .withDefaultCascadingStrategy(cascadingStrategy)
            .withStrLookup(ConfigStrLookup.from(rawConfig))
            .build();
    }
    
    @Provides
    @Singleton
    public Decoder getDecoder() {
        return DefaultDecoder.INSTANCE;
    }

    
    @Provides
    @Singleton
    PropertyFactory getPropertyFactory(Config config) {
        return DefaultPropertyFactory.from(config);
    }

    @Provides
    @Singleton
    ConfigProxyFactory getProxyFactory(Decoder decoder, PropertyFactory factory) {
        return new ConfigProxyFactory(decoder, factory);
    }
    
    @Provides 
    @Singleton
    @Deprecated
    // This is needed for ConfigurationInjectingListener
    ArchaiusConfiguration getArchaiusConfiguration(CascadeStrategy cascadeStrategy, @LibrariesLayer Map<String, Config> overrides) {
        return new ArchaiusConfiguration() {
            @Override
            public Set<ConfigSeeder> getRuntimeLayerSeeders() {
                return null;
            }

            @Override
            public Set<ConfigSeeder> getRemoteLayerSeeders() {
                return null;
            }

            @Override
            public Set<ConfigSeeder> getDefaultsLayerSeeders() {
                return null;
            }

            @Override
            public String getConfigName() {
                return null;
            }

            @Override
            public CascadeStrategy getCascadeStrategy() {
                return cascadeStrategy;
            }

            @Override
            public Decoder getDecoder() {
                return null;
            }

            @Override
            public Set<ConfigListener> getConfigListeners() {
                return null;
            }

            @Override
            public Map<String, Config> getLibraryOverrides() {
                return overrides;
            }

            @Override
            public Config getApplicationOverride() {
                return null;
            }
        };
    }

    @Override
    public void configure(KaryonBinder binder) {
        binder.bindAutoBinder(TypeLiteralMatchers.annotatedWith(Configuration.class), new ArchaiusProxyAutoBinder());
    }
}

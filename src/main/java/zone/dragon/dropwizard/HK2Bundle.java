package zone.dragon.dropwizard;

import java.lang.annotation.Annotation;
import java.lang.management.ManagementFactory;

import javax.inject.Inject;
import javax.ws.rs.core.Feature;
import javax.ws.rs.core.FeatureContext;

import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.AbstractLifeCycle.AbstractLifeCycleListener;
import org.eclipse.jetty.util.component.LifeCycle;
import org.glassfish.hk2.api.DynamicConfiguration;
import org.glassfish.hk2.api.Factory;
import org.glassfish.hk2.api.Immediate;
import org.glassfish.hk2.api.ImmediateController;
import org.glassfish.hk2.api.ImmediateController.ImmediateServiceState;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.api.ServiceLocatorFactory;
import org.glassfish.hk2.extras.ExtrasUtilities;
import org.glassfish.hk2.internal.InheritableThreadContext;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.hk2.utilities.binding.BindingBuilder;
import org.glassfish.hk2.utilities.binding.BindingBuilderFactory;
import org.glassfish.hk2.utilities.binding.ScopedBindingBuilder;
import org.glassfish.hk2.utilities.binding.ServiceBindingBuilder;
import org.glassfish.jersey.process.internal.RequestScoped;

import com.google.common.annotations.Beta;

import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import lombok.NonNull;
import zone.dragon.dropwizard.health.HealthCheckActivator;
import zone.dragon.dropwizard.jmx.MBeanActivator;
import zone.dragon.dropwizard.jmx.ManagedMBeanContainer;
import zone.dragon.dropwizard.lifecycle.LifeCycleActivator;
import zone.dragon.dropwizard.metrics.HK2MetricBinder;
import zone.dragon.dropwizard.metrics.MetricActivator;
import zone.dragon.dropwizard.metrics.factories.CounterFactory;
import zone.dragon.dropwizard.metrics.factories.HistogramFactory;
import zone.dragon.dropwizard.metrics.factories.MeterFactory;
import zone.dragon.dropwizard.metrics.factories.TimerFactory;
import zone.dragon.dropwizard.task.TaskActivator;

import static org.glassfish.hk2.utilities.ServiceLocatorUtilities.addClasses;

/**
 * Provides integration between DropWizard and HK2, allowing developers to leverage the framework built into Jersey.
 *
 * @author Bryan Harclerode
 */
public class HK2Bundle<T extends Configuration> implements ConfiguredBundle<T> {
    public static final String SERVICE_LOCATOR = HK2Bundle.class.getName() + "__LOCATOR";

    /**
     * Adds this bundle to the given {@link Bootstrap} if it has not already been added by another bundle or the application.
     *
     * @param bootstrap
     *     {@code Bootstrap} to which the {@code HK2Bundle} should be added
     *
     * @return The {@code HK2Bundle} registered with the {@code bootstrap}
     */
    @SuppressWarnings("unchecked")
    public static <T extends Configuration> HK2Bundle<T> addTo(Bootstrap<T> bootstrap) {
        return BootstrapExtensions.addBundleIfNotExist(bootstrap, HK2Bundle.class, HK2Bundle::new);
    }

    /**
     * Binds local services into a locator. These have to be bound both into our service locator and the Jersey service locator because
     * they do not cross bridge or parent/child boundaries.
     *
     * @param locator
     *     {@code ServiceLocator} into which local services should be installed
     *
     * @return Controller to activate {@link Immediate @Immediate} services
     */
    private static ImmediateController bindLocalServices(ServiceLocator locator) {
        // These have to be local because they rely on the InstantiationService, which can only get the Injectee for local injections
        addClasses(locator,
                   true,
                   CounterFactory.class,
                   HistogramFactory.class,
                   MeterFactory.class,
                   TimerFactory.class,
                   AnnotationInterceptionService.class
        );
        if (locator.getServiceHandle(InheritableThreadContext.class) == null) {
            ServiceLocatorUtilities.enableInheritableThreadScope(locator);
        }
        ExtrasUtilities.enableDefaultInterceptorServiceImplementation(locator);
        ExtrasUtilities.enableTopicDistribution(locator);
        if (locator.getServiceHandle(ImmediateController.class) == null) {
            return ServiceLocatorUtilities.enableImmediateScopeSuspended(locator);
        }
        return locator.getService(ImmediateController.class);
    }

    /**
     * Feature that bridges this bundle's {@link ServiceLocator} with Jersey's. We do this instead of setting the parent because it makes a
     * number of things such as the {@link RequestScoped} context work correctly in either locator.
     */
    private static class HK2BridgeFeature implements Feature {
        private final ServiceLocator serviceLocator;

        @Inject
        private HK2BridgeFeature(@NonNull ServiceLocator serviceLocator) {
            this.serviceLocator = serviceLocator;
        }

        @Override
        public boolean configure(FeatureContext context) {
            ServiceLocator bundleLocator = serviceLocator.getService(ServiceLocator.class, SERVICE_LOCATOR);
            ExtrasUtilities.bridgeServiceLocator(bundleLocator, serviceLocator);
            ExtrasUtilities.bridgeServiceLocator(serviceLocator, bundleLocator);
            bindLocalServices(serviceLocator).setImmediateState(ImmediateServiceState.RUNNING);
            return true;
        }
    }
    @Getter
    private final ServiceLocator      locator             = ServiceLocatorFactory.getInstance().create(null);
    private final ImmediateController immediateController = bindLocalServices(getLocator());
    private       BindingBuilder<?>   activeBuilder       = null;
    private final MBeanContainer      mBeanContainer      = new MBeanContainer(ManagementFactory.getPlatformMBeanServer());

    public HK2Bundle() {
    }

    private Bootstrap<T> bootstrap = null;

    public void autoBind(@NonNull Factory<?>... factories) {
        ServiceLocatorUtilities.addFactoryConstants(getLocator(), factories);
    }

    @Beta
    public <U> ServiceBindingBuilder<U> bind(@NonNull Class<U> serviceClass) {
        finishBinding();
        return (ServiceBindingBuilder<U>) (activeBuilder = BindingBuilderFactory.newBinder(serviceClass));
    }

    @Beta
    public <U> ScopedBindingBuilder<U> bind(@NonNull U singleton) {
        finishBinding();
        return (ScopedBindingBuilder<U>) (activeBuilder = BindingBuilderFactory.newBinder(singleton));
    }

    @Beta
    public <U> ServiceBindingBuilder<U> bindAsContract(@NonNull Class<U> serviceClass) {
        return bind(serviceClass).to(serviceClass);
    }

    @SuppressWarnings("unchecked")
    @Beta
    public <U> ScopedBindingBuilder<U> bindAsContract(@NonNull U singleton) {
        return bind(singleton).to((Class) singleton.getClass());
    }

    @Beta
    public <U> ServiceBindingBuilder<U> bindFactory(@NonNull Class<? extends Factory<U>> factoryClass) {
        finishBinding();
        return (ServiceBindingBuilder<U>) (activeBuilder = BindingBuilderFactory.newFactoryBinder(factoryClass));
    }

    @Beta
    public <U> ServiceBindingBuilder<U> bindFactory(
        @NonNull Class<? extends Factory<U>> factoryClass, Class<? extends Annotation> factoryScope
    ) {
        finishBinding();
        return (ServiceBindingBuilder<U>) (activeBuilder = BindingBuilderFactory.newFactoryBinder(factoryClass, factoryScope));
    }

    @Beta
    public <U> ServiceBindingBuilder<U> bindFactory(@NonNull Factory<U> factory) {
        finishBinding();
        return (ServiceBindingBuilder<U>) (activeBuilder = BindingBuilderFactory.newFactoryBinder(factory));
    }

    /**
     * Completes the {@link #activeBuilder} and adds it to the {@link #getLocator() service locator}
     */
    private void finishBinding() {
        if (activeBuilder != null) {
            DynamicConfiguration config = ServiceLocatorUtilities.createDynamicConfiguration(getLocator());
            BindingBuilderFactory.addBinding(activeBuilder, config);
            config.commit();
            activeBuilder = null;
        }
    }

    public void autoBind(@NonNull Class<?>... serviceClasses) {
        addClasses(getLocator(),true, serviceClasses);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void run(@NonNull T configuration, @NonNull Environment environment) {
        finishBinding();
        // Bridge into Jersey's locator
        environment.jersey().register(new AbstractBinder() {
            @Override
            protected void configure() {
                bind(getLocator()).to(ServiceLocator.class).named(SERVICE_LOCATOR);
            }
        });
        // Make the service locator available to the admin context too.
        environment.getAdminContext().setAttribute(SERVICE_LOCATOR, getLocator());
        // Finish configuring HK2 when Jetty starts (after the Application.run() method)
        environment.lifecycle().addLifeCycleListener(new AbstractLifeCycleListener() {
            @Override
            public void lifeCycleStarting(LifeCycle event) {
                if (event instanceof Server) {
                    finishBinding();
                    ServiceLocatorUtilities.addOneConstant(getLocator(), event, null, Server.class);
                    immediateController.setImmediateState(ImmediateServiceState.RUNNING);
                    ((Server) event).addBean(mBeanContainer);
                    ((Server) event).addBean(new ManagedMBeanContainer(mBeanContainer));
                }
            }
        });
        ServiceLocatorUtilities.bind(getLocator(), new EnvironmentBinder<>(bootstrap, configuration, environment));
        ServiceLocatorUtilities.bind(getLocator(), new HK2MetricBinder());
        ServiceLocatorUtilities.bind(getLocator(), new BundleBinder(bootstrap));
        ServiceLocatorUtilities.addOneConstant(getLocator(), mBeanContainer, null, MBeanContainer.class);
        // Register Jersey components to activate injectable dropwizard components when Jersey starts up
        environment.jersey().register(HK2BridgeFeature.class);
        environment.jersey().register(HealthCheckActivator.class);
        environment.jersey().register(MetricActivator.class);
        environment.jersey().register(LifeCycleActivator.class);
        environment.jersey().register(TaskActivator.class);
        autoBind(MBeanActivator.class);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void initialize(Bootstrap<?> bootstrap) {
        this.bootstrap = (Bootstrap<T>) bootstrap;
    }
}

package zone.dragon.dropwizard.lifecycle;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.testing.ResourceHelpers;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import zone.dragon.dropwizard.HK2Bundle;
import zone.dragon.dropwizard.TestConfig;

import javax.inject.Inject;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Bryan Harclerode
 * @date 10/29/2016
 */
public class InjectableManagedTest {
    private static String testValue;
    private static boolean stopped;

    @AfterClass
    public static void verifyStop() {
        assertThat(stopped).isEqualTo(true);
    }

    public static class ManagedApp extends Application<TestConfig> {
        @Override
        public void initialize(Bootstrap<TestConfig> bootstrap) {
            HK2Bundle.addTo(bootstrap);
        }

        @Override
        public void run(TestConfig testConfig, Environment environment) throws Exception {
            environment.jersey().register(TestManaged.class);
        }
    }

    public static class TestManaged implements InjectableManaged {
        private final TestConfig config;

        @Inject
        public TestManaged(TestConfig config) {
            this.config = config;
        }

        @Override
        public void start() throws Exception {
            if (testValue != null) {
                Assert.fail("Already started");
            }
            testValue = config.getTestProperty();
        }

        @Override
        public void stop() throws Exception {
            if (stopped) {
                Assert.fail("Already stopped");
            }
            stopped = true;
        }
    }

    @Rule
    public final DropwizardAppRule<TestConfig> RULE = new DropwizardAppRule<>(ManagedApp.class,
                                                                              ResourceHelpers.resourceFilePath("config.yaml")
    );

    @Test
    public void testServerStartedCalled() {
        assertThat(testValue).isEqualTo("testValue");
    }
}

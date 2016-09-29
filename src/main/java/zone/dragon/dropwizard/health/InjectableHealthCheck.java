package zone.dragon.dropwizard.health;

import com.codahale.metrics.health.HealthCheck;
import org.glassfish.jersey.spi.Contract;

/**
 * Marker class to tag a {@link HealthCheck} as a Jersey component; Extend this instead of the standard {@link HealthCheck} to allow
 * registration of the component with Jersey.
 *
 * @author Bryan Harclerode
 * @date 9/23/2016
 */
@Contract
public abstract class InjectableHealthCheck extends HealthCheck {}

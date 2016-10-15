package zone.dragon.dropwizard.metrics;

import com.codahale.metrics.MetricSet;
import org.glassfish.jersey.spi.Contract;

/**
 * Marker interface to tag a {@link MetricSet} as a Jersey component; Implement this instead of the standard {@link MetricSet} to allow
 * registration of the component with Jersey.
 *
 * @author Bryan Harclerode
 * @date 9/23/2016
 */
@Contract
public interface InjectableMetricSet extends MetricSet {}
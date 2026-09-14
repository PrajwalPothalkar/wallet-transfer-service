package com.paytm.exercise.wallet.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpLatencyMetricsConfig {
    private static final String HTTP_SERVER_REQUESTS = "http.server.requests";

    /**
     * Publishes client-side latency percentiles ({@code quantile="0.5|0.95|0.99"}) so p99 is
     * readable straight off {@code /metrics} with nothing but curl.
     *
     * <p>Deliberately <em>not</em> enabling {@code percentilesHistogram} on
     * {@code http.server.requests}: in Micrometer 1.14 the Prometheus exporter emits an
     * either / or — when a timer publishes histogram buckets, the quantile summary series are
     * suppressed (see PrometheusMeterRegistry#addDistributionStatisticSamples). The buckets only
     * serve a real multi-instance Prometheus; a single-instance exercise is better served by a
     * direct numeric p99 line.
     *
     * <p>Expressed as a {@link MeterFilter} rather than
     * {@code management.metrics.distribution.percentiles.*} because the dotted meter name is a
     * map key in that property and is not the single source of truth; the property is also set
     * in {@code application.yml}, and this filter documents and enforces the value in code.
     */
    @Bean
    MeterFilter httpServerRequestsLatencyDistribution() {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(Meter.Id id, DistributionStatisticConfig config) {
                if (!HTTP_SERVER_REQUESTS.equals(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentiles(0.5, 0.95, 0.99)
                        .build()
                        .merge(config);
            }
        };
    }
}

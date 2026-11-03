package com.ledgerline.projection.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.time.Duration;

@Configuration
public class TracingConfiguration {
    @Bean(destroyMethod = "close")
    OpenTelemetrySdk telemetry(@Value("${spring.application.name}") String service,
        @Value("${ledgerline.telemetry.endpoint:}") String endpoint,
        @Value("${ledgerline.telemetry.sample-ratio:0.1}") double ratio) {
        var provider = SdkTracerProvider.builder()
            .setResource(Resource.create(Attributes.of(AttributeKey.stringKey("service.name"), service)))
            .setSampler(Sampler.parentBased(Sampler.traceIdRatioBased(ratio)));
        // No exporter is created unless explicitly configured. Export happens off the request thread.
        if (!endpoint.isBlank()) {
            var exporter = OtlpHttpSpanExporter.builder().setEndpoint(endpoint)
                .setTimeout(Duration.ofSeconds(1)).build();
            provider.addSpanProcessor(BatchSpanProcessor.builder(exporter).setMaxQueueSize(512)
                .setMaxExportBatchSize(128).setScheduleDelay(Duration.ofSeconds(1))
                .setExporterTimeout(Duration.ofSeconds(2)).build());
        }
        return OpenTelemetrySdk.builder().setTracerProvider(provider.build()).build();
    }
    @Bean TraceSupport traceSupport(OpenTelemetry telemetry) { return new TraceSupport(telemetry); }
}

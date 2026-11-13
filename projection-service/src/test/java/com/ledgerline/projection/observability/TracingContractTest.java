package com.ledgerline.projection.observability;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.*;
import io.opentelemetry.sdk.common.CompletableResultCode;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TracingContractTest {
    private static final String PARENT="00-11111111111111111111111111111111-2222222222222222-01";
    @Test void preservesTraceCreatesChildAndRestoresThreadContext() {
        var spans=new ArrayList<SpanData>();
        SpanExporter exporter=new SpanExporter() {
            public CompletableResultCode export(Collection<SpanData> batch) { spans.addAll(batch); return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode flush() { return CompletableResultCode.ofSuccess(); }
            public CompletableResultCode shutdown() { return CompletableResultCode.ofSuccess(); }
        };
        try(var sdk=OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter)).build()).build()) {
            var tracing=new TraceSupport(sdk);
            MDC.put("request_id","owned");
            try(var request=tracing.start("http.request",SpanKind.SERVER,PARENT)) {
                assertEquals("11111111111111111111111111111111",MDC.get("trace_id"));
                String persisted=TraceSupport.currentParent();
                try(var producer=tracing.start("outbox.publish",SpanKind.PRODUCER,persisted)) {
                    try(var consumer=tracing.startBatch("projection.process",List.of(TraceSupport.currentParent()))) {
                        assertEquals("11111111111111111111111111111111",Span.current().getSpanContext().getTraceId());
                    }
                }
            }
            assertEquals(3,spans.size());
            assertEquals(spans.get(1).getSpanId(),spans.getFirst().getParentSpanId());
            assertEquals(spans.get(2).getSpanId(),spans.get(1).getParentSpanId());
            assertEquals("2222222222222222",spans.get(2).getParentSpanId());
            assertNull(MDC.get("trace_id")); assertEquals("owned",MDC.get("request_id"));
        } finally { MDC.clear(); }
    }
    @Test void rejectsMalformedZeroAndOversizedParents() {
        for(String value:List.of("garbage",PARENT+"\nsecret", "00-"+"0".repeat(32)+"-2222222222222222-01",PARENT.repeat(100))) {
            assertFalse(Span.fromContext(TraceSupport.parent(value)).getSpanContext().isValid());
        }
    }
    @Test void exportsOtlpToLocalCollectorAndIsolatesCollectorOutage() throws Exception {
        var received=new CountDownLatch(1);
        var status=new java.util.concurrent.atomic.AtomicInteger(200);
        var bytes=new java.util.concurrent.atomic.AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/traces",exchange->{
            bytes.set(exchange.getRequestBody().readAllBytes().length);
            exchange.sendResponseHeaders(status.get(),-1); exchange.close(); received.countDown();
        }); server.start();
        try(var sdk=new TracingConfiguration().telemetry("fixture","http://127.0.0.1:"+server.getAddress().getPort()+"/v1/traces",1.0)) {
            var tracing=new TraceSupport(sdk);
            try(var span=tracing.start("fixture",SpanKind.SERVER,PARENT)) { }
            sdk.getSdkTracerProvider().forceFlush().join(4,TimeUnit.SECONDS);
            assertTrue(received.await(1,TimeUnit.SECONDS)); assertTrue(bytes.get()>0);
            status.set(503);
            long start=System.nanoTime();
            for(int i=0;i<2000;i++) try(var span=tracing.start("fixture",SpanKind.SERVER,PARENT)) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-start)<3000,"Business thread must not wait for collector");
            sdk.getSdkTracerProvider().forceFlush().join(4,TimeUnit.SECONDS);
            try(var span=tracing.start("still-operational",SpanKind.SERVER,PARENT)) {
                assertTrue(Span.current().getSpanContext().isValid());
            }
        } finally { server.stop(0); }
    }
}

package com.ledgerline.projection.observability;

import com.ledgerline.projection.consumer.TransferEventConsumer;
import com.ledgerline.projection.service.ProjectionService;
import com.ledgerline.projection.model.TransferEventPayload;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConsumerTracingBoundaryTest {
    @Test void realConsumerUsesBrokerHeaderAndAcknowledgesOnlyAfterProjection() {
        var service=mock(ProjectionService.class); var mapper=mock(ObjectMapper.class); var ack=mock(Acknowledgment.class);
        var payload=new TransferEventPayload(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,"USD",Instant.now());
        when(mapper.readValue("fixture",TransferEventPayload.class)).thenReturn(payload);
        var record=new ConsumerRecord<String,String>("fixture",0,1,"key","fixture");
        record.headers().add("traceparent","00-11111111111111111111111111111111-2222222222222222-01".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        try(var sdk=OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().build()).build()) {
            var consumer=new TransferEventConsumer(service,mapper);
            ReflectionTestUtils.setField(consumer,"tracing",new TraceSupport(sdk));
            doAnswer(call->{
                assertEquals("11111111111111111111111111111111",Span.current().getSpanContext().getTraceId());
                assertNotEquals("2222222222222222",Span.current().getSpanContext().getSpanId());
                verify(ack,never()).acknowledge(); return 1;
            }).when(service).projectBatch(anyList());
            consumer.onMessages(List.of(record),ack); verify(ack).acknowledge();
        }
    }
    @Test void nullTraceHeaderCannotPoisonValidRecordAndFailureCauseIsRetained() {
        var service=mock(ProjectionService.class); var mapper=mock(ObjectMapper.class); var ack=mock(Acknowledgment.class);
        var record=new ConsumerRecord<String,String>("fixture",0,1,"key","fixture");
        record.headers().add("traceparent",null);
        var payload=new TransferEventPayload(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),1,"USD",Instant.now());
        when(mapper.readValue("fixture",TransferEventPayload.class)).thenReturn(payload);
        var consumer=new TransferEventConsumer(service,mapper);
        assertDoesNotThrow(()->consumer.onMessages(List.of(record),ack)); verify(ack).acknowledge();
        var databaseFailure=new IllegalStateException("synthetic storage failure");
        doThrow(databaseFailure).when(service).projectBatch(anyList());
        reset(ack);
        assertSame(databaseFailure,assertThrows(RuntimeException.class,()->consumer.onMessages(List.of(record),ack)).getCause());
        verify(ack,never()).acknowledge();
    }
}

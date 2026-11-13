package com.ledgerline.projection.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.MDC;
import java.util.Map;

/** Strict W3C version-00 propagation; never carries baggage, payloads or credentials. */
public final class TraceSupport {
    private final Tracer tracer;
    public TraceSupport(OpenTelemetry telemetry) { tracer = telemetry.getTracer("ledgerline"); }
    public static Context parent(String value) {
        if (value == null || !value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")) return Context.root();
        var context = SpanContext.createFromRemoteParent(value.substring(3,35), value.substring(36,52),
            TraceFlags.fromHex(value,53), TraceState.getDefault());
        return context.isValid() ? Context.root().with(Span.wrap(context)) : Context.root();
    }
    public static String currentParent() {
        var context=Span.current().getSpanContext();
        return context.isValid() ? "00-"+context.getTraceId()+"-"+context.getSpanId()+"-"+context.getTraceFlags().asHex() : null;
    }
    public Operation start(String name, SpanKind kind, String parent) {
        Span span=tracer.spanBuilder(name).setSpanKind(kind).setParent(parent(parent)).startSpan();
        return new Operation(span);
    }
    public Operation startBatch(String name, java.util.List<String> parents) {
        var builder=tracer.spanBuilder(name).setSpanKind(SpanKind.CONSUMER)
            .setParent(parents.isEmpty() ? Context.root() : parent(parents.getFirst()));
        parents.stream().skip(1).map(TraceSupport::parent).map(Span::fromContext)
            .map(Span::getSpanContext).filter(SpanContext::isValid).forEach(builder::addLink);
        return new Operation(builder.startSpan());
    }
    public static final class Operation implements AutoCloseable {
        private final Span span;
        private final Scope scope;
        private final Map<String,String> previous;
        Operation(Span span) {
            this.span=span; previous=MDC.getCopyOfContextMap(); scope=span.makeCurrent();
            MDC.put("trace_id",span.getSpanContext().getTraceId()); MDC.put("span_id",span.getSpanContext().getSpanId());
        }
        public void failed() { span.setStatus(StatusCode.ERROR); }
        public void close() {
            scope.close(); span.end();
            if(previous==null) MDC.clear(); else MDC.setContextMap(previous);
        }
    }
}

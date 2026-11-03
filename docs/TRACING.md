# Tracing and structured logs

Both services emit Spring Boot ECS JSON console logs. Request IDs are application
generated. Active trace_id and span_id enter MDC during HTTP, outbox publishing
and projection processing; closing the span restores the prior thread context.
No request bodies, credentials, account identifiers or raw Kafka values are added
to spans or metric labels.

The HTTP filter validates bounded W3C version-00 traceparent values and creates a
SERVER child span. Invalid and zero identifiers start independent traces. No
baggage is propagated. The committed outbox stores optional traceparent metadata;
old events and constructors remain supported. The relay creates an outbox.publish
PRODUCER child and sends its context as an ASCII Kafka header. Projection creates
a CONSUMER child of the first record and links other valid batch parents. Missing,
invalid and null-valued trace headers cannot prevent ordinary projection.

TRACE_SAMPLE_RATIO defaults to 0.1 with parent-based sampling. Export is disabled
unless OTEL_EXPORTER_OTLP_TRACES_ENDPOINT names a full HTTP OTLP traces endpoint,
such as http://collector:4318/v1/traces inside an isolated lab. Deployment must
supply transport protection appropriate to its network.

Exports use a separate BatchSpanProcessor with queue limit 512, batch limit 128,
one second scheduling delay, one second HTTP timeout and two second processor
export timeout. Queue saturation drops telemetry. Collector health does not gate
readiness, financial commits or Kafka acknowledgement. The SDK closes on normal
application shutdown. Financial processing keeps its existing transaction and
offset semantics.

## Verification

Run focused checks without Docker:

    mvn -pl ledger-service,projection-service -am test -Dtest=TracingContractTest,ConsumerTracingBoundaryTest,ViewRenderingBoundaryTest -Dsurefire.failIfNoSpecifiedTests=false

TracingContractTest exercises real loopback HTTP OTLP export, a 503 collector
outage while generating 2,000 spans, strict parent validation and MDC cleanup.
ConsumerTracingBoundaryTest exercises the actual consumer, trace scope, offset
ordering, null header metadata and retention of original storage failure causes.
The MVC slice uses the real tracing configuration and emits ECS JSON.

The added AsyncFailureAndResilienceTest method
httpTraceSurvivesCommittedOutboxAndRealKafkaPublish uses fresh PostgreSQL and
Kafka fixtures. An HTTP filter invokes a real transfer; the test reads committed
outbox context and consumes the actual broker record to assert trace continuity
and a distinct producer child. It runs with the normal isolated Docker integration
suite. The separate [cross-service tracing smoke](TRACE_FLOW_SMOKE.md) verifies both
running services, exported parent relationships and financial behavior during a
collector outage. Its actual run status is recorded separately from these tests.

Official references: https://opentelemetry.io/docs/languages/java/sdk/ and
https://docs.spring.io/spring-boot/reference/features/logging.html

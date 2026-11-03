# Optional cross-service trace and collector-outage smoke

This is a separate bounded smoke, run after performance experiments have stopped. It never runs load or soak. Its Compose override leaves the frozen benchmark harness, base Compose and image pins untouched.

```text
python scripts/trace-flow-smoke.py --candidate /path/to/frozen-artifact-manifest.json --evidence /path/to/new-trace-smoke-output
```

The manifest is the same explicit source/artifact identity accepted by the benchmark lab. It must identify the instrumented application JARs. The smoke starts a fresh isolated synthetic PostgreSQL/Kafka/application fixture plus a small Python OTLP/HTTP receiver using the pinned public Python image declared in the smoke source. No existing database or application volumes are used. The receiver is reachable by the application on the internal fixture network and exposes a random loopback host port for bounded evidence retrieval.

A real HTTP transfer supplies a unique sampled W3C trace ID and parent. The smoke reads the committed PostgreSQL outbox traceparent, observes the actual Kafka record/header with an independent read-only consumer, waits for projection convergence and retrieves the actual SDK-exported OTLP records. It requires the exported HTTP server span to preserve the supplied parent, the producer span to parent to that HTTP span and the projection consumer span to parent to the producer. Service resources and server/producer/consumer kinds must match. A settled single transfer makes this a direct parent chain; this smoke does not claim arbitrary batch-link coverage.

The collector is then actually stopped. A new HTTP transfer must commit, project and replay with status 200 using the same transaction ID; the posting count must increase exactly two across the original call and replay. The receiver's stopped container state is retained. This verifies exporter outage does not bypass financial correctness or suppress projection convergence. It does not promise lossless telemetry during an outage.

Evidence: `trace-chain.json`, original OTLP batch wire bytes encoded in base64 plus SHA-256 and decoded spans in `otlp-receipts.json`, durable outbox/Kafka context in `kafka-trace-receipt.json`, `collector-outage.json`, fixture logs, cleanup receipt and hashes. Keep raw receipts private: they include synthetic fixture secrets and event payloads. A successful parser test alone is not runtime trace evidence; only `trace-result.json` from the actual smoke establishes its result.

The receiver implements only the needed bounded protobuf fields from the official [OpenTelemetry trace schema v1.5.0](https://github.com/open-telemetry/opentelemetry-proto/blob/v1.5.0/opentelemetry/proto/trace/v1/trace.proto) and [trace export service schema](https://github.com/open-telemetry/opentelemetry-proto/blob/v1.5.0/opentelemetry/proto/collector/trace/v1/trace_service.proto). Unknown primitive fields are skipped; malformed IDs, truncated/oversized messages, unsupported encodings and retention overflow are rejected. It accepts uncompressed protobuf over HTTP, matching the configured Java exporter default. It is a synthetic test receiver, not a production collector, security boundary or replacement for official generated OTLP libraries.

Local parser/chain controls, with no Docker services:

```text
python scripts/test_trace_smoke_controls.py
```

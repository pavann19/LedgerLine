package com.ledgerline.projection.consumer;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class ProjectionQuarantine {
    private final JdbcClient jdbc;
    public ProjectionQuarantine(JdbcClient jdbc, MeterRegistry metrics) {
        this.jdbc = jdbc;
        Gauge.builder("projection.quarantine.pending", this, q -> q.pending()).register(metrics);
    }
    public long pending() {
        return jdbc.sql("SELECT count(*) FROM projection_quarantine").query(Long.class).single();
    }
    @Transactional
    public void park(ConsumerRecord<?, ?> record, Exception error) {
        String payload = record.value() == null ? null : record.value().toString();
        byte[] bytes = payload == null ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 1048576) throw new IllegalArgumentException("Quarantine payload exceeds durable limit; offset must not advance");
        String hash;
        try { hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        jdbc.sql("""
            INSERT INTO projection_quarantine(topic,partition_id,record_offset,payload,payload_sha256,failure_class)
            VALUES(?,?,?,?,?,?) ON CONFLICT DO NOTHING
            """).params(record.topic(), record.partition(), record.offset(), payload, hash, error.getClass().getSimpleName()).update();
        // Conflict is safe only for the identical immutable Kafka coordinate.
        String stored = jdbc.sql("SELECT payload_sha256 FROM projection_quarantine WHERE topic=? AND partition_id=? AND record_offset=?")
            .params(record.topic(),record.partition(),record.offset()).query(String.class).single();
        if (!stored.equals(hash)) throw new IllegalStateException("Quarantine coordinate content mismatch");
    }
}

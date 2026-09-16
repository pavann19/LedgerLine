package com.ledgerline.ledger.repository;

import com.ledgerline.ledger.domain.OutboxEvent;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class OutboxRepository {

    private final JdbcClient jdbcClient;

    private final RowMapper<OutboxEvent> rowMapper = (rs, rowNum) -> new OutboxEvent(
        rs.getLong("id"),
        rs.getObject("aggregate_id", UUID.class),
        rs.getString("event_type"),
        rs.getString("payload"),
        rs.getTimestamp("created_at").toInstant(),
        rs.getTimestamp("published_at") != null ? rs.getTimestamp("published_at").toInstant() : null
    );

    public OutboxRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertEvent(OutboxEvent event) {
        String sql = """
            INSERT INTO outbox (aggregate_id, event_type, payload, created_at, published_at)
            VALUES (?, ?, ?::jsonb, ?, ?)
            """;
        jdbcClient.sql(sql)
            .params(
                event.aggregateId(),
                event.eventType(),
                event.payload(),
                Timestamp.from(event.createdAt()),
                event.publishedAt() != null ? Timestamp.from(event.publishedAt()) : null
            )
            .update();
    }

    public List<OutboxEvent> fetchUnpublishedForUpdateSkipLocked(int limit) {
        String sql = """
            SELECT id, aggregate_id, event_type, payload::text, created_at, published_at 
            FROM outbox 
            WHERE published_at IS NULL 
            ORDER BY id ASC 
            FOR UPDATE SKIP LOCKED 
            LIMIT ?
            """;
        return jdbcClient.sql(sql).param(limit).query(rowMapper).list();
    }

    public void markPublished(long id, Instant publishedAt) {
        String sql = "UPDATE outbox SET published_at = ? WHERE id = ?";
        jdbcClient.sql(sql)
            .params(Timestamp.from(publishedAt), id)
            .update();
    }

    public long getBacklogCount() {
        String sql = "SELECT COUNT(1) FROM outbox WHERE published_at IS NULL";
        Long count = jdbcClient.sql(sql).query(Long.class).single();
        return count != null ? count : 0L;
    }

    public double getOldestUnpublishedAgeSeconds() {
        String sql = """
            SELECT COALESCE(EXTRACT(EPOCH FROM (clock_timestamp() - MIN(created_at))), 0.0)
            FROM outbox
            WHERE published_at IS NULL
            """;
        Double age = jdbcClient.sql(sql).query(Double.class).single();
        return age != null ? age : 0.0;
    }
}

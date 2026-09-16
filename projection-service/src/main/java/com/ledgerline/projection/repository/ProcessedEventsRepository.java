package com.ledgerline.projection.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class ProcessedEventsRepository {

    private final JdbcClient jdbcClient;

    public ProcessedEventsRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Atomically attempts to insert event_id into processed_events.
     * Returns true if event was not yet processed (inserted), false if duplicate.
     */
    public boolean tryMarkProcessed(UUID eventId, Instant processedAt) {
        String sql = """
            INSERT INTO processed_events (event_id, processed_at)
            VALUES (?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;
        int rows = jdbcClient.sql(sql)
            .params(eventId, Timestamp.from(processedAt))
            .update();
        return rows > 0;
    }

    public boolean isProcessed(UUID eventId) {
        String sql = "SELECT COUNT(1) FROM processed_events WHERE event_id = ?";
        Integer count = jdbcClient.sql(sql).param(eventId).query(Integer.class).single();
        return count != null && count > 0;
    }
}

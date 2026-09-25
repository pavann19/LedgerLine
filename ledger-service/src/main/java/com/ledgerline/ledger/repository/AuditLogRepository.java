package com.ledgerline.ledger.repository;

import com.ledgerline.ledger.domain.AuditLogEntry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;

@Repository
public class AuditLogRepository {

    private final JdbcClient jdbcClient;

    public AuditLogRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    public void insertLog(AuditLogEntry entry) {
        String sql = """
            INSERT INTO audit_log (actor, principal_id, action, entity_id, at, details)
            VALUES (?, ?, ?, ?, ?, ?::jsonb)
            """;
        jdbcClient.sql(sql)
            .params(
                entry.actor(),
                entry.actor(),
                entry.action(),
                entry.entityId(),
                Timestamp.from(entry.at()),
                entry.details()
            )
            .update();
    }
}

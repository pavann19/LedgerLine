package com.ledgerline.ledger.observability;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@ConditionalOnProperty(name="reconciliation.enabled",havingValue="true")
public class ReconciliationController {
    private final JdbcTemplate jdbc;
    public ReconciliationController(@Value("${reconciliation.jdbc-url}") String url,
        @Value("${reconciliation.username}") String user,@Value("${reconciliation.password}") String password) {
        // Dedicated view-only identity. Never fall back to the application's pool.
        var ds=new DriverManagerDataSource(url,user,password);
        jdbc=new JdbcTemplate(ds); jdbc.setQueryTimeout(2); jdbc.setMaxRows(1);
    }
    @GetMapping("/api/v1/ops/reconciliation")
    public Map<String,Object> summary() {
        Boolean writable=jdbc.queryForObject("""
            SELECT EXISTS(SELECT FROM pg_roles WHERE rolname=current_user AND (rolsuper OR rolcreaterole OR rolcreatedb))
              OR EXISTS(SELECT FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='public' AND c.relkind IN ('r','p')
                AND has_table_privilege(current_user,c.oid,'INSERT,UPDATE,DELETE,TRUNCATE'))
            """,Boolean.class);
        if (!Boolean.FALSE.equals(writable)) throw new IllegalStateException("Reconciliation identity is not read-only");
        return jdbc.queryForMap("SELECT * FROM ledger_reconciliation");
    }
}

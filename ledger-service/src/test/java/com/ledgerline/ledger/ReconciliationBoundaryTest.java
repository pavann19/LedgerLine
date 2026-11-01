package com.ledgerline.ledger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.sql.DriverManager;
import static org.junit.jupiter.api.Assertions.*;

class ReconciliationBoundaryTest extends com.ledgerline.ledger.concurrency.BaseIntegrationTest {
    @Autowired JdbcClient jdbc;
    @Test void actualViewOnlyDatabaseRoleCannotWriteOrReadRawFinancialData() throws Exception {
        String name="fixture_ledger_reader";
        jdbc.sql("CREATE ROLE "+name+" LOGIN PASSWORD 'synthetic-only-reader' NOSUPERUSER NOCREATEDB NOCREATEROLE").update();
        jdbc.sql("GRANT USAGE ON SCHEMA public TO "+name).update();
        jdbc.sql("GRANT SELECT ON ledger_reconciliation TO "+name).update();
        try(var c=DriverManager.getConnection(postgres.getJdbcUrl(),name,"synthetic-only-reader");var s=c.createStatement()) {
            // Turning off a session flag cannot bypass the actual privilege boundary.
            s.execute("SET default_transaction_read_only=off");
            try(var r=s.executeQuery("SELECT * FROM ledger_reconciliation")) { assertTrue(r.next()); assertEquals(1,r.getInt("schema_version")); }
            assertThrows(java.sql.SQLException.class,()->s.execute("DELETE FROM postings"));
            assertThrows(java.sql.SQLException.class,()->s.execute("SELECT * FROM outbox"));
            assertThrows(java.sql.SQLException.class,()->s.execute("CREATE TABLE public.forbidden_fixture(id int)"));
            assertThrows(java.sql.SQLException.class,()->s.execute("SET ROLE postgres"));
        }
    }
}

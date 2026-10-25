package com.ledgerline.ledger.domain;

import com.ledgerline.ledger.LedgerApplication;
import com.ledgerline.ledger.dto.TransferEventPayload;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class JsonWireCompatibilityTest {
    @Test void outboxKeepsIntegerMoneyAndNumericNanosecondTimestamps() {
        var mapper = new LedgerApplication().objectMapper();
        var id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var timestamp = Instant.ofEpochSecond(1735689599, 123456789);
        var event = new TransferEventPayload(id, id, id, id, Long.MAX_VALUE, "USD", timestamp);
        var json = mapper.writeValueAsString(event);
        var tree = mapper.reader()
            .with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .readTree(json);
        assertTrue(tree.get("amountMinor").isIntegralNumber());
        assertEquals(Long.MAX_VALUE, tree.get("amountMinor").longValue());
        assertTrue(tree.get("timestamp").isNumber());
        assertEquals(0, new BigDecimal("1735689599.123456789").compareTo(tree.get("timestamp").decimalValue()));
        assertTrue(json.startsWith("{\"eventId\":"), "Preserve the record field order used by existing payloads");
        assertEquals(event, mapper.readValue(json, TransferEventPayload.class));
    }
}

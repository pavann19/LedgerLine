package com.ledgerline.projection;

import com.ledgerline.projection.model.TransferEventPayload;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;

class JsonWireCompatibilityTest {
    @Test void readsExistingJacksonTwoOutboxPayloadWithoutLosingMoneyOrTimestampPrecision() {
        String json = """
            {"eventId":"00000000-0000-0000-0000-000000000001",
             "transactionId":"00000000-0000-0000-0000-000000000002",
             "fromAccountId":"00000000-0000-0000-0000-000000000003",
             "toAccountId":"00000000-0000-0000-0000-000000000004",
             "amountMinor":9223372036854775807,"currency":"USD","timestamp":1735689599.123456789}
            """;
        var event = new ProjectionApplication().objectMapper().readValue(json, TransferEventPayload.class);
        assertEquals(Long.MAX_VALUE, event.amountMinor());
        assertEquals(Instant.ofEpochSecond(1735689599, 123456789), event.timestamp());
        assertEquals("USD", event.currency());
    }
}

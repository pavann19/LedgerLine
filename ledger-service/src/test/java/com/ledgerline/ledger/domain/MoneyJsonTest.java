package com.ledgerline.ledger.domain;
import com.ledgerline.ledger.LedgerApplication;
import com.ledgerline.ledger.dto.TransferRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class MoneyJsonTest {
    @Test void refusesFractionalAndOutOfRangeMinorUnits() throws Exception {
        var mapper = new LedgerApplication().objectMapper();
        String body = "{\"fromAccountId\":\"00000000-0000-0000-0000-000000000001\",\"toAccountId\":\"00000000-0000-0000-0000-000000000002\",\"currency\":\"USD\",\"amountMinor\":%s}";
        for (String value : new String[]{"0.01", "0.10", "0.29", "1.9", "999999999.99", "9223372036854775808"}) {
            assertThrows(tools.jackson.core.JacksonException.class,
                () -> mapper.readValue(body.formatted(value), TransferRequest.class));
        }
        assertEquals(Long.MAX_VALUE, mapper.readValue(body.formatted(Long.MAX_VALUE), TransferRequest.class).amountMinor());
        assertEquals(1, mapper.readValue(body.formatted(1), TransferRequest.class).amountMinor());
    }
}

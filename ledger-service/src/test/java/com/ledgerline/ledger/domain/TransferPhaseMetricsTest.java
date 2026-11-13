package com.ledgerline.ledger.domain;

import com.ledgerline.ledger.service.performance.TransferPhaseMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static com.ledgerline.ledger.service.performance.TransferPhaseMetrics.Phase.PERSIST;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TransferPhaseMetricsTest {
    @Test void brokenObserverCannotRepeatOrReplaceSuccessfulOperation() {
        MeterRegistry registry = mock(MeterRegistry.class);
        var metrics = new TransferPhaseMetrics(registry);
        var effects = new AtomicInteger();
        assertEquals(1, metrics.measure(PERSIST, effects::incrementAndGet));
        assertEquals(1, effects.get());
    }

    @Test void brokenObserverCannotHideDomainFailureOrRetryIt() {
        var metrics = new TransferPhaseMetrics(mock(MeterRegistry.class));
        var effects = new AtomicInteger();
        var failure = new IllegalStateException("synthetic domain failure");
        assertSame(failure, assertThrows(IllegalStateException.class, () -> metrics.measure(PERSIST, () -> {
            effects.incrementAndGet(); throw failure;
        })));
        assertEquals(1, effects.get());
    }
}

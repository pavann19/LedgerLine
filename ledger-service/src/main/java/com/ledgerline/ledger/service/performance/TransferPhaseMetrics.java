package com.ledgerline.ledger.service.performance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Fixed-cardinality timings; instrumentation failure must never repeat or undo money work. */
@Component
public class TransferPhaseMetrics {
    public enum Phase {
        TRANSACTION_START, KEY_LOCK, REPLAY_CHECK, METADATA_LOCK, BALANCE_LOCK,
        PERSIST, COMMIT_CLEANUP, BALANCE_LOCK_TO_COMPLETION, TOTAL_TRANSACTION
    }

    private final Map<Phase, Timer> timers;

    public TransferPhaseMetrics(MeterRegistry registry) {
        Map<Phase, Timer> registered = new EnumMap<>(Phase.class);
        for (Phase phase : Phase.values()) {
            try {
                registered.put(phase, Timer.builder("ledger.transfer.phase")
                    .description("Pessimistic transfer phase elapsed time; transaction phases overlap total")
                    .tag("phase", phase.name().toLowerCase(Locale.ROOT))
                    .publishPercentiles(0.5, 0.95, 0.99).register(registry));
            } catch (RuntimeException ignored) {
                // A broken observer must not prevent the deterministic service from starting.
            }
        }
        timers = Map.copyOf(registered);
    }

    public <T> T measure(Phase phase, Supplier<T> operation) {
        long started = System.nanoTime();
        try { return operation.get(); }
        finally { record(phase, started); }
    }

    public void record(Phase phase, long started) {
        try {
            Timer timer = timers.get(phase);
            if (timer != null) timer.record(Math.max(0, System.nanoTime() - started), TimeUnit.NANOSECONDS);
        } catch (RuntimeException ignored) {
            // The domain operation already ran. A broken observer cannot authorize a retry.
        }
    }
}

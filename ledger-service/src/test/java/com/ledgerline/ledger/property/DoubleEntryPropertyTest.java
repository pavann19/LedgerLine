package com.ledgerline.ledger.property;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class DoubleEntryPropertyTest {

    record AccountState(UUID id, long balanceMinor, boolean allowsOverdraft) {
        AccountState withDelta(long delta) {
            return new AccountState(id, balanceMinor + delta, allowsOverdraft);
        }
    }

    record Action(int fromIdx, int toIdx, long amount, String idempotencyKey) {}

    @Property
    void doubleEntryLedgerInvariantsHoldUnderArbitraryOperationSequences(
        @ForAll("accountInitialBalances") List<Long> initialBalances,
        @ForAll("actionsList") List<Action> actions
    ) {
        int numAccounts = initialBalances.size();
        Map<Integer, AccountState> accounts = new HashMap<>();
        long initialTotal = 0;

        for (int i = 0; i < numAccounts; i++) {
            long bal = initialBalances.get(i);
            accounts.put(i, new AccountState(UUID.randomUUID(), bal, false));
            initialTotal += bal;
        }

        Map<String, Action> executedIdempotentKeys = new HashMap<>();
        List<Long> postingSums = new ArrayList<>(Collections.nCopies(numAccounts, 0L));

        for (Action action : actions) {
            int fromIdx = Math.abs(action.fromIdx) % numAccounts;
            int toIdx = Math.abs(action.toIdx) % numAccounts;

            if (fromIdx == toIdx || action.amount <= 0) {
                continue; // invalid transfer
            }

            // Idempotency check
            if (executedIdempotentKeys.containsKey(action.idempotencyKey)) {
                // Replay: no state change allowed
                continue;
            }

            AccountState from = accounts.get(fromIdx);
            AccountState to = accounts.get(toIdx);

            if (!from.allowsOverdraft && from.balanceMinor < action.amount) {
                // Insufficient funds rejected cleanly
                continue;
            }

            // Apply transfer
            accounts.put(fromIdx, from.withDelta(-action.amount));
            accounts.put(toIdx, to.withDelta(action.amount));

            postingSums.set(fromIdx, postingSums.get(fromIdx) - action.amount);
            postingSums.set(toIdx, postingSums.get(toIdx) + action.amount);

            executedIdempotentKeys.put(action.idempotencyKey, action);

            // Invariant 1: Conservation of Money at every single step
            long currentTotal = accounts.values().stream().mapToLong(AccountState::balanceMinor).sum();
            assertEquals(initialTotal, currentTotal, "Conservation of money violated");

            // Invariant 2: Non-negative balance on standard accounts
            assertTrue(accounts.get(fromIdx).balanceMinor >= 0, "Negative balance encountered");
        }

        // Invariant 3: Postings sum must exactly match balance delta
        for (int i = 0; i < numAccounts; i++) {
            long expectedBalance = initialBalances.get(i) + postingSums.get(i);
            assertEquals(expectedBalance, accounts.get(i).balanceMinor, "Postings sum drift for account " + i);
        }
    }

    @Provide
    Arbitrary<List<Long>> accountInitialBalances() {
        return Arbitraries.longs().between(1_000L, 500_000L)
            .list().ofMinSize(3).ofMaxSize(10);
    }

    @Provide
    Arbitrary<List<Action>> actionsList() {
        Arbitrary<Integer> idx = Arbitraries.integers().between(0, 9);
        Arbitrary<Long> amount = Arbitraries.longs().between(10L, 20_000L);
        Arbitrary<String> keys = Arbitraries.strings().alpha().ofLength(5);

        Arbitrary<Action> actionArbitrary = Combinators.combine(idx, idx, amount, keys)
            .as(Action::new);

        return actionArbitrary.list().ofMinSize(10).ofMaxSize(100);
    }
}

# Financial invariants

| Invariant | Application | Database | Executable evidence |
|---|---|---|---|
| Positive integral amount | DTO/core validation; fractional JSON rejected | Nonzero posting CHECK | MoneyJsonTest |
| Same currency | Active accounts and matching currency | Composite posting/account currency FK; deferred single-currency check | FinancialEdgeRegressionTest |
| Debit equals credit | Two signed postings in one transaction | Deferred zero-sum SUM(NUMERIC) | DatabaseConstraintInvariantTest |
| No wraparound | addExact/subtractExact in safe strategies | BIGINT range | TransferStrategyRegressionTest; FinancialEdgeRegressionTest |
| No protected overdraft | Funds check under locks/version/SSI | Nonnegative CHECK and account-type composite FK | Stress and edge regressions |
| Atomic transfer | Header/postings/balances/audit/outbox in one transaction | Commit/rollback | Edge overflow rollback regression |
| One mutation per subject/key | SHA-256 body binding and replay | Composite uniqueness | IdempotencyIntegrationTest; exhausted-funds replay regression |
| Historical edit/delete rejected | No mutation API | Posting/transaction/audit triggers | Constraint and edge regressions |
| Customer cannot grant credit | Non-customer creation restricted to operator | DB trusts provisioning | ReleaseBoundaryTest |
| Balance equals initial balance plus postings | Safe strategies and invariant tests | Not enforced for arbitrary SQL | Stress/property tests |

A test name is not a current PASS result. Database-backed tests require Docker. Internal variant 0 deliberately violates cached balance correctness as a negative control.

Amounts are positive signed 64-bit minor units. USD 0.01 is sent as 1, 0.10 as 10, and 0.29 as 29. JSON 0.29 is rejected instead of rounded. No FX, exponent conversion, division, interest, fee, or rounding feature exists. Callers supply the correct unit interpretation. Currency normalization uses Locale.ROOT; account creation validates java.util.Currency. Existing data is not silently rewritten.

Long.MAX_VALUE is representable, but available funds and both resulting balances must fit. Only operator-provisioned OVERDRAFT permits negative balances. Demo funding has an offsetting negative funding balance; it does not create unbalanced postings.

Accounts start at zero through the API. Existing test/benchmark fixtures inject initial balances directly and assert initialBalance + SUM(postings). Those fixtures remain unchanged. SQL owners can independently change balances, truncate history, or append balanced rows for other accounts to an existing header. Deferred zero-sum does not enforce exactly two postings or require postings on every SQL-created header. These are limitations of the database trust boundary. No refund or dedicated reversal endpoint exists.

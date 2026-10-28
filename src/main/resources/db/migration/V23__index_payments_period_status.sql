-- TAR-225: index of the monthly sum of the approved payments of the contracts (finance summary,
-- sumAmountOfPeriod: contract_id IS NOT NULL AND period = ? AND status = ?), measured with EXPLAIN (ANALYZE,
-- BUFFERS) on a local PostgreSQL 17 with synthetic volume (320k payments): before, a parallel scan of all the
-- payments; after, a scan of the period in this index, with custom and generic plans alike.
--
-- Not partial on status: the status reaches the query as a bound parameter, and the generic plan of the
-- prepared statement cannot use an index whose predicate names a status. ix_payments_contract_period starts
-- with contract_id and does not serve a period of any contract.
--
-- Plain CREATE INDEX inside the Flyway transaction, as the other migrations: the table is small today.
-- Rollback: DROP INDEX ix_payments_period_status;

CREATE INDEX ix_payments_period_status ON payments (period, status);

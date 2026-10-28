-- TAR-224: indexes for reads that scanned the whole table, measured with EXPLAIN (ANALYZE, BUFFERS) on a
-- local PostgreSQL 17 with synthetic volume (80k stays, 320k payments).
--
-- ix_stays_start_date: the stays that start in a month (finance summary); before, a scan of all stays.
-- ix_stays_end_date_start_date: the stays in force on a day (admin dashboard); end_date >= day is the
--   selective bound, start_date <= day is filtered inside the index.
-- ix_payments_stay: the payments of a list of stays, of every status (dashboard). uq_payments_live_stay only
--   covers PENDING and APPROVED, so the read without status scanned every payment.
-- ix_payments_contract_period: the payments of a list of contracts in a period, of every status (dashboard).
--   uq_payments_live_period only covers PENDING and APPROVED.
--
-- Plain CREATE INDEX inside the Flyway transaction, as the other migrations: the tables are small today.
-- Rollback: DROP INDEX ix_stays_start_date, ix_stays_end_date_start_date, ix_payments_stay,
-- ix_payments_contract_period;

CREATE INDEX ix_stays_start_date ON stays (start_date);

CREATE INDEX ix_stays_end_date_start_date ON stays (end_date, start_date);

CREATE INDEX ix_payments_stay ON payments (stay_id);

CREATE INDEX ix_payments_contract_period ON payments (contract_id, period);

-- TAR-43: the queue without a status filter pages by (created_date, id) over every payment;
-- ix_payments_queue starts with status and cannot serve that order.
CREATE INDEX ix_payments_created ON payments (created_date, id);

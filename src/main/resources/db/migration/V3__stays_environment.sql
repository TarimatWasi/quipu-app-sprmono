-- TAR-237 (RN-20): una estadía de un huésped temporal ocupa un ambiente durante sus fechas, como un
-- contrato. Las estadías registradas antes quedan sin ambiente («Sin ambiente»).
ALTER TABLE stays ADD COLUMN environment_id BIGINT NULL;

ALTER TABLE stays
    ADD CONSTRAINT fk_stays_environment FOREIGN KEY (environment_id) REFERENCES environments (id);

-- La ocupación cruzada busca las estadías de un ambiente que se cruzan con unas fechas.
CREATE INDEX ix_stays_environment_dates ON stays (environment_id, start_date, end_date)
    WHERE environment_id IS NOT NULL;

COMMENT ON COLUMN stays.environment_id IS 'Ambiente que ocupa la estadía durante sus fechas (RN-20, TAR-237); nulo para una estadía registrada antes («Sin ambiente»).';

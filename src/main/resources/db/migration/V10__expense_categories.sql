-- TAR-183 (RF-07, D-26): las categorias de egreso pasan de WATER, ELECTRICITY, INTERNET y OTHER a las
-- cuatro aprobadas. Los valores viejos se llevan a UTILITIES (OTHER tambien, decision de la persona
-- responsable del 2026-10-13). V5 no se edita: una migracion aplicada es inmutable.

ALTER TABLE expenses DROP CONSTRAINT ck_expenses_type;

UPDATE expenses SET type = 'UTILITIES' WHERE type IN ('WATER', 'ELECTRICITY', 'INTERNET', 'OTHER');

ALTER TABLE expenses ADD CONSTRAINT ck_expenses_type
    CHECK (type IN ('UTILITIES', 'MAINTENANCE', 'CLEANING_SUPPLIES', 'TAXES_FEES'));

COMMENT ON COLUMN expenses.type IS 'Categoría: UTILITIES (luz, agua, internet), MAINTENANCE (mantenimiento y reparaciones), CLEANING_SUPPLIES (limpieza e insumos) o TAXES_FEES (impuestos y tasas).';
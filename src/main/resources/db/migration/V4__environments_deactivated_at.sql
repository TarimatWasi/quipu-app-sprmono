-- TAR-237 (A2.1): A2 muestra desde cuándo está inactivo un ambiente.
ALTER TABLE environments ADD COLUMN deactivated_at TIMESTAMPTZ NULL;

-- Los ambientes que ya estaban inactivos toman la fecha de su último cambio.
UPDATE environments SET deactivated_at = last_modified_date WHERE status = 'INACTIVE';

COMMENT ON COLUMN environments.deactivated_at IS 'Cuándo se desactivó el ambiente (A2.1, TAR-237); nulo mientras está activo.';

-- TAR-182 (RN-13, RN-17, CU-31): las estadías de un huésped temporal pasan a su propia tabla. Cada visita
-- conserva sus fechas y su monto acordado; una visita nueva es una fila nueva del mismo huésped, nunca un
-- huésped nuevo. Una estadía no se borra.
--
-- Las columnas stay_start_date, stay_end_date y agreed_amount de guests NO se eliminan aquí: la aplicación
-- anterior todavía las lee durante el despliegue y una migración posterior las retira (expandir, migrar,
-- contraer). La aplicación nueva ya no las escribe.

CREATE SEQUENCE stays_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE stays (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    start_date            DATE NOT NULL,
    end_date              DATE NOT NULL,
    agreed_amount         NUMERIC(10, 2) NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_stays_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT ck_stays_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_stays_agreed_amount CHECK (agreed_amount > 0)
);

-- El listado de un huésped va de la estadía más reciente a la más antigua.
CREATE INDEX ix_stays_guest ON stays (guest_id, start_date DESC, id DESC);

-- Una fila por cada huésped temporal que ya tenía las tres columnas completas; los demás no tienen estadía.
INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount, created_date, last_modified_date)
SELECT nextval('stays_seq'), g.id, g.stay_start_date, g.stay_end_date, g.agreed_amount, now(), now()
FROM guests g
WHERE g.guest_type = 'TEMPORARY'
  AND g.stay_start_date IS NOT NULL
  AND g.stay_end_date IS NOT NULL
  AND g.agreed_amount IS NOT NULL
ORDER BY g.id;

COMMENT ON TABLE stays IS 'Estadías de los huéspedes temporales (RN-13): cada visita es una fila con sus fechas y su monto acordado. No se borran.';
COMMENT ON COLUMN stays.id IS 'Identificador de la estadía (BIGINT de la secuencia stays_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN stays.guest_id IS 'Huésped temporal al que pertenece la estadía.';
COMMENT ON COLUMN stays.start_date IS 'Primer día de la estadía, inclusive.';
COMMENT ON COLUMN stays.end_date IS 'Último día de la estadía, inclusive; no es anterior a start_date.';
COMMENT ON COLUMN stays.agreed_amount IS 'Monto acordado en soles, mayor que 0.';
COMMENT ON COLUMN stays.version IS 'Versión para el bloqueo optimista: dos ediciones simultáneas no se pisan.';
COMMENT ON COLUMN stays.created_date IS 'Fecha de creación del registro (auditoría).';
COMMENT ON COLUMN stays.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN stays.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN stays.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

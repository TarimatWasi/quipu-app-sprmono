-- TAR-47 (RF-19, RN-30, RN-24): multas cobradas a un huésped, que cuentan como ingreso directo de su mes (sin
-- voucher ni aprobación). Se acepta cualquier huésped existente, activo o inactivo: un huésped nunca se borra
-- físicamente (se desactiva), así que la clave foránea basta para que la multa no quede huérfana.
--
-- Una multa no se edita: para corregirla se borra (borrado físico, RN-24, igual que los egresos) y se vuelve a
-- crear. Por eso no lleva version. El listado es por mes y sin paginar (pocas multas por mes).
--
-- Rollback: DROP TABLE fines; DROP SEQUENCE fines_seq; (se pierden las multas registradas).

CREATE SEQUENCE fines_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE fines (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    amount                NUMERIC(10, 2) NOT NULL,
    month                 DATE NOT NULL,
    description           TEXT NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_fines_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT ck_fines_amount CHECK (amount > 0),
    CONSTRAINT ck_fines_month CHECK (month = date_trunc('month', month)::date),
    CONSTRAINT ck_fines_description CHECK (char_length(description) <= 500)
);

CREATE INDEX ix_fines_month ON fines (month);

COMMENT ON TABLE fines IS 'Multas cobradas a un huésped, ingreso directo de su mes (RF-19, RN-30); no se editan y se eliminan libremente (RN-24).';
COMMENT ON COLUMN fines.id IS 'Identificador de la multa (BIGINT de la secuencia fines_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN fines.guest_id IS 'Huésped al que se cobra la multa, activo o inactivo.';
COMMENT ON COLUMN fines.amount IS 'Monto en soles, mayor que 0.';
COMMENT ON COLUMN fines.month IS 'Mes en que la multa cuenta como ingreso, truncado al primer día del mes.';
COMMENT ON COLUMN fines.description IS 'Descripción opcional, de hasta 500 caracteres.';
COMMENT ON COLUMN fines.created_date IS 'Fecha de creación del registro (auditoría).';
COMMENT ON COLUMN fines.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN fines.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN fines.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

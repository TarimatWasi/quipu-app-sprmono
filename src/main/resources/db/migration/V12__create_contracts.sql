-- TAR-40 (RF-03, RF-09, CU-03, CU-07, CU-12; RN-01, RN-07, RN-20, RN-21): contratos de los huéspedes con contrato.
-- Un contrato une a un huésped con un ambiente, con su monto mensual, su día de pago, sus días de gracia y el PDF firmado.
--
-- status guarda solo ACTIVE o TERMINATED. Vencido (EXPIRED) NO se guarda: se deriva de end_date en cada lectura
-- (America/Lima), así el vencimiento no necesita un proceso programado y el ambiente se libera solo (RN-07).
-- No hay índice único parcial por ambiente: dejaría el ambiente ocupado después de vencer el contrato. La ocupación
-- (RN-20) la valida la aplicación con el ambiente bloqueado.

CREATE SEQUENCE contracts_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE contracts (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    environment_id        BIGINT NOT NULL,
    start_date            DATE NOT NULL,
    end_date              DATE NULL,
    monthly_amount        NUMERIC(10, 2) NOT NULL,
    payment_day           SMALLINT NOT NULL,
    grace_days            SMALLINT NOT NULL,
    file_key              VARCHAR(255) NOT NULL,
    original_name         VARCHAR(255) NOT NULL,
    size_bytes            BIGINT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version               BIGINT NOT NULL DEFAULT 0,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_contracts_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT fk_contracts_environment FOREIGN KEY (environment_id) REFERENCES environments (id),
    CONSTRAINT ck_contracts_dates CHECK (end_date IS NULL OR end_date > start_date),
    CONSTRAINT ck_contracts_monthly_amount CHECK (monthly_amount > 0),
    CONSTRAINT ck_contracts_payment_day CHECK (payment_day BETWEEN 1 AND 31),
    CONSTRAINT ck_contracts_grace_days CHECK (grace_days BETWEEN 0 AND 31),
    CONSTRAINT ck_contracts_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT ck_contracts_status CHECK (status IN ('ACTIVE', 'TERMINATED'))
);

CREATE INDEX ix_contracts_guest ON contracts (guest_id);
CREATE INDEX ix_contracts_environment ON contracts (environment_id);
-- El listado va del contrato más reciente al más antiguo, leído por último índice.
CREATE INDEX ix_contracts_created_date_id ON contracts (created_date DESC, id DESC);

COMMENT ON TABLE contracts IS 'Contratos de los huéspedes con contrato (RF-03): ambiente, condiciones de pago y PDF. El vencimiento se deriva de end_date; no se borran.';
COMMENT ON COLUMN contracts.id IS 'Identificador del contrato (BIGINT de la secuencia contracts_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN contracts.guest_id IS 'Huésped con contrato al que pertenece.';
COMMENT ON COLUMN contracts.environment_id IS 'Ambiente que ocupa mientras el contrato está vigente (RN-01, RN-20).';
COMMENT ON COLUMN contracts.start_date IS 'Primer día del contrato, inclusive; puede ser futuro y aun así ocupa el ambiente.';
COMMENT ON COLUMN contracts.end_date IS 'Último día del contrato, inclusive; NULL es un contrato indefinido (RN-07); es posterior a start_date.';
COMMENT ON COLUMN contracts.monthly_amount IS 'Monto mensual en soles, mayor que 0.';
COMMENT ON COLUMN contracts.payment_day IS 'Día del mes en que vence el pago, de 1 a 31 (RN-10).';
COMMENT ON COLUMN contracts.grace_days IS 'Días de gracia después del día de pago, de 0 a 31 (RN-10).';
COMMENT ON COLUMN contracts.file_key IS 'Clave del objeto PDF en el bucket R2; nunca la URL firmada, que se firma al pedir el contrato y caduca.';
COMMENT ON COLUMN contracts.original_name IS 'Nombre del archivo que subió el administrador, saneado.';
COMMENT ON COLUMN contracts.size_bytes IS 'Tamaño del PDF en bytes, mayor que 0.';
COMMENT ON COLUMN contracts.status IS 'ACTIVE o TERMINATED (finalizado a mano, RN-21). Vencido no se guarda: se deriva de end_date.';
COMMENT ON COLUMN contracts.version IS 'Versión para el bloqueo optimista: dos finalizaciones simultáneas no se pisan.';
COMMENT ON COLUMN contracts.created_date IS 'Fecha de creación del registro (auditoría).';
COMMENT ON COLUMN contracts.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN contracts.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN contracts.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

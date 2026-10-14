-- TAR-43 (RF-05, RF-06, RF-10; CU-04, CU-05, CU-14; RN-02, RN-03, RN-04, RN-05, RN-15, RN-22, RN-23, RN-28):
-- pagos. Un pago es un voucher con su monto declarado: de un período de un contrato (huésped con contrato) o de
-- una estadía (huésped temporal, RN-03). Nace PENDING y el administrador lo aprueba o lo rechaza una sola vez.
--
-- Qué decide la base y qué la aplicación:
--  * Un solo pago vivo (PENDING o APPROVED) por contrato y período (RN-02, RN-15) y por estadía (RN-03): índices
--    únicos parciales, para que dos peticiones simultáneas no dejen dos filas. Un REJECTED no ocupa el lugar: el
--    huésped sube otro.
--  * El "pago complementario" (RN-16) no existe: si la persona lo decide, solo cambia el índice
--    uq_payments_live_period; el resto del esquema no lo impide.
--  * Los estados por período (pagado, en revisión, atrasado, sin pagar) y la deuda NO se guardan: se calculan al
--    consultar, con el Clock (America/Lima), igual que el vencimiento de un contrato.

-- Los períodos que cobra un contrato llegan hasta el mes en que se finalizó a mano (RN-21). Hasta ahora el
-- contrato guardaba que se finalizó pero no cuándo: terminated_at lo guarda. Para los ya finalizados se toma la
-- última modificación de la fila, que es cuando se finalizaron (lo único que se modifica después de cargarlo).
ALTER TABLE contracts ADD COLUMN terminated_at TIMESTAMPTZ NULL;
UPDATE contracts SET terminated_at = last_modified_date WHERE status = 'TERMINATED';
ALTER TABLE contracts ADD CONSTRAINT ck_contracts_terminated_at CHECK ((status = 'TERMINATED') = (terminated_at IS NOT NULL));
COMMENT ON COLUMN contracts.terminated_at IS 'Instante en que el administrador finalizó el contrato a mano (RN-21); NULL mientras no se finalizó. El último mes que cobra es el de esta fecha en America/Lima.';

CREATE SEQUENCE payments_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE payments (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    contract_id           BIGINT NULL,
    stay_id               BIGINT NULL,
    period                DATE NULL,
    amount                NUMERIC(10, 2) NOT NULL,
    payment_method        VARCHAR(50) NULL,
    voucher_key           VARCHAR(255) NOT NULL,
    original_name         VARCHAR(255) NOT NULL,
    content_type          VARCHAR(100) NOT NULL,
    size_bytes            BIGINT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason      VARCHAR(500) NULL,
    reviewed_at           TIMESTAMPTZ NULL,
    reviewed_by_id        BIGINT NULL,
    recorded_by           VARCHAR(20) NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_payments_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT fk_payments_contract FOREIGN KEY (contract_id) REFERENCES contracts (id),
    CONSTRAINT fk_payments_stay FOREIGN KEY (stay_id) REFERENCES stays (id),
    CONSTRAINT fk_payments_reviewed_by FOREIGN KEY (reviewed_by_id) REFERENCES users (id),
    -- Un período de un contrato, o una estadía; nunca los dos ni ninguno.
    CONSTRAINT ck_payments_target CHECK (
        (contract_id IS NOT NULL AND period IS NOT NULL AND stay_id IS NULL)
        OR (stay_id IS NOT NULL AND contract_id IS NULL AND period IS NULL)),
    CONSTRAINT ck_payments_period_first_day CHECK (period IS NULL OR EXTRACT(DAY FROM period) = 1),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT uq_payments_voucher_key UNIQUE (voucher_key),
    CONSTRAINT ck_payments_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_payments_recorded_by CHECK (recorded_by IN ('GUEST', 'ADMIN')),
    -- Todo rechazo lleva un motivo que no es solo espacios (RN-05).
    CONSTRAINT ck_payments_rejection_reason CHECK (
        status <> 'REJECTED' OR (rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) > 0)),
    -- Un pago resuelto dice quién y cuándo; uno pendiente no.
    CONSTRAINT ck_payments_review CHECK (
        (status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by_id IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND reviewed_at IS NOT NULL AND reviewed_by_id IS NOT NULL))
);

-- RN-02, RN-15: un período tiene a lo sumo UN pago vivo (PENDING o APPROVED). Un solo índice y no dos (uno por
-- estado): con dos, aprobar el voucher pendiente mientras otra petición sube uno nuevo dejaba un APPROVED y un
-- PENDING del mismo período, porque la aprobación libera el índice de pendientes y toma el de aprobados.
CREATE UNIQUE INDEX uq_payments_live_period ON payments (contract_id, period) WHERE status IN ('PENDING', 'APPROVED');
-- RN-03: una estadía tiene un único pago vivo.
CREATE UNIQUE INDEX uq_payments_live_stay ON payments (stay_id) WHERE status IN ('PENDING', 'APPROVED');

-- La deuda de un huésped (RN-25) lee sus pagos por estado y período.
CREATE INDEX ix_payments_guest_status_period ON payments (guest_id, status, period);
-- La cola de revisión va del voucher más antiguo al más reciente, leída por último índice.
CREATE INDEX ix_payments_queue ON payments (status, created_date, id);
-- El historial de un huésped va del más reciente al más antiguo.
CREATE INDEX ix_payments_history ON payments (guest_id, created_date DESC, id DESC);

COMMENT ON TABLE payments IS 'Pagos con voucher (RN-04, RN-22): de un período de un contrato o de una estadía. Se resuelven una sola vez y no se borran.';
COMMENT ON COLUMN payments.id IS 'Identificador del pago (BIGINT de la secuencia payments_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN payments.guest_id IS 'Huésped que paga (RN-09: solo ve los suyos).';
COMMENT ON COLUMN payments.contract_id IS 'Contrato al que pertenece el período; NULL en el pago de una estadía.';
COMMENT ON COLUMN payments.stay_id IS 'Estadía que paga un huésped temporal (RN-03); NULL en el pago de un período de contrato.';
COMMENT ON COLUMN payments.period IS 'Primer día del mes que se paga (RN-02, sin prorrateo); NULL en el pago de una estadía.';
COMMENT ON COLUMN payments.amount IS 'Monto declarado en soles, mayor que 0; el administrador lo compara con el voucher (RN-16).';
COMMENT ON COLUMN payments.payment_method IS 'Medio de pago en texto libre, por ejemplo Yape o transferencia (RN-28); opcional.';
COMMENT ON COLUMN payments.voucher_key IS 'Clave del objeto del voucher en el bucket R2; nunca la URL firmada, que se firma al pedirlo y caduca.';
COMMENT ON COLUMN payments.original_name IS 'Nombre del archivo que subió quien registró el pago, saneado.';
COMMENT ON COLUMN payments.content_type IS 'Tipo del archivo (image/jpeg, image/png o application/pdf), deducido del contenido y nunca del que declara el cliente.';
COMMENT ON COLUMN payments.size_bytes IS 'Tamaño del voucher en bytes, mayor que 0.';
COMMENT ON COLUMN payments.status IS 'PENDING (por revisar), APPROVED o REJECTED (RN-04, RN-05).';
COMMENT ON COLUMN payments.rejection_reason IS 'Motivo del rechazo, obligatorio si el pago está REJECTED (RN-05); el huésped lo ve solo en la app.';
COMMENT ON COLUMN payments.reviewed_at IS 'Cuándo el administrador aprobó o rechazó el pago; NULL mientras está PENDING.';
COMMENT ON COLUMN payments.reviewed_by_id IS 'Administrador que aprobó o rechazó el pago; NULL mientras está PENDING.';
COMMENT ON COLUMN payments.recorded_by IS 'GUEST (subió su voucher) o ADMIN (lo registró en su nombre, RN-23); lo fija el servidor.';
COMMENT ON COLUMN payments.version IS 'Versión para el bloqueo optimista: dos resoluciones simultáneas no se pisan.';
COMMENT ON COLUMN payments.created_date IS 'Fecha de creación del registro (auditoría); es cuándo se subió el voucher.';
COMMENT ON COLUMN payments.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN payments.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN payments.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

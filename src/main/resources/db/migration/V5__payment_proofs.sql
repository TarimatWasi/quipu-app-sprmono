-- TAR-239 (RN-22, RN-05, RN-16): un pago reúne de 1 a 5 comprobantes (voucher o efectivo), cada uno con su monto
-- declarado y el verificado al aprobar; cada decisión queda en payment_reviews; un pago rechazado se corrige y se
-- envía de nuevo, así que sigue ocupando su período o su estadía.

-- =====================================================================================================
-- Comprobantes de un pago (RN-22)
-- =====================================================================================================

CREATE SEQUENCE payment_proofs_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE payment_proofs (
    id                    BIGINT PRIMARY KEY,
    payment_id            BIGINT NOT NULL,
    kind                  VARCHAR(10) NOT NULL,
    declared_amount       NUMERIC(10, 2) NOT NULL,
    verified_amount       NUMERIC(10, 2) NULL,
    method                VARCHAR(50) NULL,
    file_key              VARCHAR(255) NULL,
    original_name         VARCHAR(255) NULL,
    content_type          VARCHAR(100) NULL,
    size_bytes            BIGINT NULL,
    uploaded_by           VARCHAR(20) NOT NULL,
    received_on           DATE NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_payment_proofs_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT ck_payment_proofs_kind CHECK (kind IN ('VOUCHER', 'CASH')),
    CONSTRAINT ck_payment_proofs_declared_amount CHECK (declared_amount > 0),
    CONSTRAINT ck_payment_proofs_verified_amount CHECK (verified_amount IS NULL OR verified_amount >= 0),
    CONSTRAINT ck_payment_proofs_uploaded_by CHECK (uploaded_by IN ('GUEST', 'ADMIN')),
    -- Un voucher tiene su archivo; el efectivo no tiene ninguno.
    CONSTRAINT ck_payment_proofs_file CHECK (
        (kind = 'VOUCHER' AND file_key IS NOT NULL AND original_name IS NOT NULL
            AND content_type IN ('image/jpeg', 'image/png', 'application/pdf') AND size_bytes > 0)
        OR (kind = 'CASH' AND file_key IS NULL AND original_name IS NULL AND content_type IS NULL
            AND size_bytes IS NULL)),
    CONSTRAINT uq_payment_proofs_file_key UNIQUE (file_key)
);

-- Los comprobantes de un pago, en el orden en que se agregaron.
CREATE INDEX ix_payment_proofs_payment ON payment_proofs (payment_id, id);

COMMENT ON TABLE payment_proofs IS 'Comprobantes de un pago (RN-22, TAR-239): de 1 a 5 por pago, voucher (archivo) o efectivo sin voucher, con el monto declarado y el verificado al aprobar (RN-16).';
COMMENT ON COLUMN payment_proofs.id IS 'Identificador del comprobante (BIGINT de la secuencia payment_proofs_seq); el BFF lo muestra enmascarado.';
COMMENT ON COLUMN payment_proofs.payment_id IS 'Pago al que pertenece el comprobante.';
COMMENT ON COLUMN payment_proofs.kind IS 'VOUCHER (imagen o PDF, RF-15) o CASH (efectivo sin voucher, RN-22).';
COMMENT ON COLUMN payment_proofs.declared_amount IS 'Monto declarado del comprobante, en soles (RN-16).';
COMMENT ON COLUMN payment_proofs.verified_amount IS 'Monto que verificó el administrador al aprobar el pago (RN-16); nulo mientras el pago no está aprobado; 0 si el comprobante no valía.';
COMMENT ON COLUMN payment_proofs.method IS 'Medio de pago en texto libre (RN-28), por ejemplo Yape o transferencia; nulo si no se dijo.';
COMMENT ON COLUMN payment_proofs.file_key IS 'Clave del voucher en R2 (RN-22); nula para el efectivo. Nunca se muestra: el BFF firma una URL al pedirla.';
COMMENT ON COLUMN payment_proofs.original_name IS 'Nombre del archivo que subió el navegador, saneado; nulo para el efectivo.';
COMMENT ON COLUMN payment_proofs.content_type IS 'Tipo del voucher deducido de su contenido (RF-15); nulo para el efectivo.';
COMMENT ON COLUMN payment_proofs.size_bytes IS 'Tamaño del voucher en bytes (RF-15, hasta 5 MB); nulo para el efectivo.';
COMMENT ON COLUMN payment_proofs.uploaded_by IS 'Quién agregó el comprobante (RN-22): GUEST o ADMIN.';
COMMENT ON COLUMN payment_proofs.received_on IS 'Día en que la administración recibió el dinero, en un pago directo (A5.1); nulo para lo que sube el huésped.';
COMMENT ON COLUMN payment_proofs.created_date IS 'Cuándo se agregó el comprobante.';
COMMENT ON COLUMN payment_proofs.last_modified_date IS 'Último cambio del comprobante (una corrección o la verificación).';
COMMENT ON COLUMN payment_proofs.created_by_id IS 'Usuario que agregó el comprobante.';
COMMENT ON COLUMN payment_proofs.last_modified_by_id IS 'Usuario del último cambio del comprobante.';

-- =====================================================================================================
-- Decisiones sobre un pago (RN-05, TAR-230)
-- =====================================================================================================

CREATE SEQUENCE payment_reviews_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE payment_reviews (
    id                    BIGINT PRIMARY KEY,
    payment_id            BIGINT NOT NULL,
    decision              VARCHAR(20) NOT NULL,
    reason                VARCHAR(500) NULL,
    reviewed_at           TIMESTAMPTZ NOT NULL,
    reviewed_by_id        BIGINT NOT NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_payment_reviews_payment FOREIGN KEY (payment_id) REFERENCES payments (id),
    CONSTRAINT fk_payment_reviews_reviewed_by FOREIGN KEY (reviewed_by_id) REFERENCES users (id),
    CONSTRAINT ck_payment_reviews_decision CHECK (decision IN ('APPROVED', 'REJECTED')),
    -- Todo rechazo lleva un motivo que no es solo espacios (RN-05).
    CONSTRAINT ck_payment_reviews_reason CHECK (
        decision <> 'REJECTED' OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);

-- Las decisiones de un pago, de la más antigua a la más reciente («Lo que pasó»).
CREATE INDEX ix_payment_reviews_payment ON payment_reviews (payment_id, reviewed_at, id);

COMMENT ON TABLE payment_reviews IS 'Decisiones sobre un pago (RN-05, TAR-239, TAR-230): un pago rechazado se corrige y se revisa de nuevo, así que puede tener varias; no se borran.';
COMMENT ON COLUMN payment_reviews.id IS 'Identificador de la decisión (BIGINT de la secuencia payment_reviews_seq); interno, el BFF no lo muestra.';
COMMENT ON COLUMN payment_reviews.payment_id IS 'Pago sobre el que se decidió.';
COMMENT ON COLUMN payment_reviews.decision IS 'APPROVED o REJECTED.';
COMMENT ON COLUMN payment_reviews.reason IS 'Motivo del rechazo (RN-05), visible solo en la app para el huésped (D-34d); nulo en una aprobación.';
COMMENT ON COLUMN payment_reviews.reviewed_at IS 'Cuándo se decidió.';
COMMENT ON COLUMN payment_reviews.reviewed_by_id IS 'Administrador que decidió (TAR-230); solo se muestra en las vistas del administrador.';
COMMENT ON COLUMN payment_reviews.created_date IS 'Cuándo se guardó la decisión.';
COMMENT ON COLUMN payment_reviews.last_modified_date IS 'Igual a created_date: una decisión no cambia.';
COMMENT ON COLUMN payment_reviews.created_by_id IS 'Usuario que guardó la decisión.';
COMMENT ON COLUMN payment_reviews.last_modified_by_id IS 'Usuario del último cambio; una decisión no cambia.';

-- =====================================================================================================
-- El pago: cuándo se envió por última vez, lo verificado y los rechazos que una corrección reemplazó
-- =====================================================================================================

ALTER TABLE payments ADD COLUMN submitted_at TIMESTAMPTZ NULL;
UPDATE payments SET submitted_at = created_date;
ALTER TABLE payments ALTER COLUMN submitted_at SET NOT NULL;

ALTER TABLE payments ADD COLUMN verified_amount NUMERIC(10, 2) NULL;
UPDATE payments SET verified_amount = amount WHERE status = 'APPROVED';
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_verified_amount CHECK (
        (status = 'APPROVED' AND verified_amount IS NOT NULL AND verified_amount >= 0)
        OR (status <> 'APPROVED' AND verified_amount IS NULL));

-- Cada voucher de antes es el único comprobante de su pago, agregado por quien registró el pago.
INSERT INTO payment_proofs (id, payment_id, kind, declared_amount, verified_amount, method, file_key,
                            original_name, content_type, size_bytes, uploaded_by, received_on, created_date,
                            last_modified_date, created_by_id, last_modified_by_id)
SELECT nextval('payment_proofs_seq'), id, 'VOUCHER', amount, verified_amount, payment_method, voucher_key,
       original_name, content_type, size_bytes, recorded_by, NULL, created_date, last_modified_date, created_by_id,
       last_modified_by_id
FROM payments
ORDER BY id;

-- Cada resolución de antes es la única decisión de su pago.
INSERT INTO payment_reviews (id, payment_id, decision, reason, reviewed_at, reviewed_by_id, created_date,
                             last_modified_date, created_by_id, last_modified_by_id)
SELECT nextval('payment_reviews_seq'), id, status, rejection_reason, reviewed_at, reviewed_by_id, reviewed_at,
       reviewed_at, reviewed_by_id, reviewed_by_id
FROM payments
WHERE status IN ('APPROVED', 'REJECTED')
ORDER BY id;

-- SUPERSEDED: un rechazo que quedó como historia. Antes un rechazo liberaba el período y se subía otro pago; ahora el
-- rechazado se corrige, así que cada período o estadía conserva a lo sumo uno rechazado: el más reciente, y solo si
-- no tiene un pago pendiente o aprobado.
-- Las tres reglas que nombran los estados se quitan antes del UPDATE y vuelven conociendo SUPERSEDED.
ALTER TABLE payments DROP CONSTRAINT ck_payments_status;
ALTER TABLE payments DROP CONSTRAINT ck_payments_rejection_reason;
ALTER TABLE payments DROP CONSTRAINT ck_payments_review;
ALTER TABLE payments
    ADD CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED'));

UPDATE payments p
SET status = 'SUPERSEDED'
WHERE p.status = 'REJECTED'
  AND EXISTS (
      SELECT 1 FROM payments q
      WHERE q.id <> p.id
        AND ((p.stay_id IS NOT NULL AND q.stay_id = p.stay_id)
            OR (p.contract_id IS NOT NULL AND q.contract_id = p.contract_id AND q.period = p.period))
        AND (q.status IN ('PENDING', 'APPROVED')
            OR (q.status = 'REJECTED' AND (q.submitted_at, q.id) > (p.submitted_at, p.id))));

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_rejection_reason CHECK (
        status NOT IN ('REJECTED', 'SUPERSEDED')
        OR (rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) > 0));

ALTER TABLE payments
    ADD CONSTRAINT ck_payments_review CHECK (
        (status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by_id IS NULL)
        OR (status <> 'PENDING' AND reviewed_at IS NOT NULL AND reviewed_by_id IS NOT NULL));

-- RN-02, RN-03, RN-15: un período o una estadía tiene a lo sumo UN pago que lo ocupa, también si está rechazado
-- (se corrige en vez de subir otro, RN-05).
DROP INDEX uq_payments_live_period;
CREATE UNIQUE INDEX uq_payments_live_period ON payments (contract_id, period)
    WHERE status IN ('PENDING', 'APPROVED', 'REJECTED');
DROP INDEX uq_payments_live_stay;
CREATE UNIQUE INDEX uq_payments_live_stay ON payments (stay_id) WHERE status IN ('PENDING', 'APPROVED', 'REJECTED');

-- La cola se ordena por el último envío: un pago corregido vuelve con su fecha nueva.
DROP INDEX ix_payments_queue;
CREATE INDEX ix_payments_queue ON payments (status, submitted_at, id);
DROP INDEX ix_payments_created;
CREATE INDEX ix_payments_submitted ON payments (submitted_at, id);

-- El archivo, el método y el tamaño viven ahora en cada comprobante.
ALTER TABLE payments DROP COLUMN payment_method;
ALTER TABLE payments DROP COLUMN voucher_key;
ALTER TABLE payments DROP COLUMN original_name;
ALTER TABLE payments DROP COLUMN content_type;
ALTER TABLE payments DROP COLUMN size_bytes;

COMMENT ON TABLE payments IS 'Pagos de un período de un contrato o de una estadía (RN-02, RN-03), con sus comprobantes en payment_proofs (RN-22) y sus decisiones en payment_reviews. Un rechazado se corrige y se envía de nuevo (RN-05). No se borran.';
COMMENT ON COLUMN payments.amount IS 'Suma de los montos declarados de sus comprobantes, en soles (RN-16).';
COMMENT ON COLUMN payments.verified_amount IS 'Suma de los montos verificados de sus comprobantes al aprobar (RN-16); nula mientras no está aprobado.';
COMMENT ON COLUMN payments.status IS 'PENDING, APPROVED o REJECTED; SUPERSEDED es un rechazo de antes de TAR-239 que otro pago del mismo período o estadía reemplazó: queda como historia y se muestra como REJECTED.';
COMMENT ON COLUMN payments.rejection_reason IS 'Motivo del último rechazo mientras el pago sigue rechazado (RN-05, D-34d); la historia de motivos está en payment_reviews.';
COMMENT ON COLUMN payments.reviewed_at IS 'Cuándo se decidió por última vez; nulo mientras está pendiente (también después de una corrección que lo envía de nuevo).';
COMMENT ON COLUMN payments.reviewed_by_id IS 'Administrador de la última decisión (TAR-230); nulo mientras está pendiente.';
COMMENT ON COLUMN payments.submitted_at IS 'Último envío del pago (TAR-239): al crearlo, y otra vez cuando el huésped corrige uno rechazado. Ordena la cola de revisión.';

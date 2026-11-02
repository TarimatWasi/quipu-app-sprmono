-- TAR-240 (RF-24, RN-38, RN-39): lo que Quipu devuelve a un huésped de su crédito pendiente. El crédito no se
-- guarda: se deriva al leer de los pagos aprobados y lo verificado de cada uno; aquí solo queda cada devolución,
-- que cuenta en el mes de su fecha y se resta de los ingresos de ese mes.

CREATE SEQUENCE refunds_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE refunds (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    from_payment_id       BIGINT NOT NULL,
    amount                NUMERIC(10, 2) NOT NULL,
    refunded_on           DATE NOT NULL,
    method                VARCHAR(50) NOT NULL,
    note                  VARCHAR(500) NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_refunds_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT fk_refunds_from_payment FOREIGN KEY (from_payment_id) REFERENCES payments (id),
    CONSTRAINT ck_refunds_amount CHECK (amount > 0),
    CONSTRAINT ck_refunds_method CHECK (length(btrim(method)) > 0),
    CONSTRAINT ck_refunds_note CHECK (note IS NULL OR length(btrim(note)) > 0)
);

-- Las devoluciones de un mes, de la más reciente a la más antigua (A6 «Devoluciones», RF-18).
CREATE INDEX ix_refunds_refunded_on ON refunds (refunded_on, created_date, id);
-- Las devoluciones de un huésped, para derivar su crédito pendiente (RN-38).
CREATE INDEX ix_refunds_guest ON refunds (guest_id);
CREATE INDEX ix_refunds_from_payment ON refunds (from_payment_id);

COMMENT ON TABLE refunds IS 'Devoluciones de Quipu a un huésped desde su crédito pendiente (RF-24, RN-39, TAR-240); en el MVP no se editan ni se borran.';
COMMENT ON COLUMN refunds.id IS 'Identificador de la devolución (BIGINT de la secuencia refunds_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN refunds.guest_id IS 'Huésped al que se devolvió, activo o inactivo.';
COMMENT ON COLUMN refunds.from_payment_id IS 'Pago cuyo excedente empezó a devolver: el más antiguo con crédito pendiente al registrarla.';
COMMENT ON COLUMN refunds.amount IS 'Monto devuelto en soles, mayor que 0 y no más que el crédito pendiente del huésped (RN-38).';
COMMENT ON COLUMN refunds.refunded_on IS 'Cuándo se devolvió el dinero, nunca en el futuro; la devolución cuenta en el mes de esta fecha (RN-39).';
COMMENT ON COLUMN refunds.method IS 'Cómo se devolvió, en texto libre (Yape, transferencia).';
COMMENT ON COLUMN refunds.note IS 'Nota opcional de hasta 500 caracteres; nula si no hay.';
COMMENT ON COLUMN refunds.created_date IS 'Cuándo se registró la devolución.';
COMMENT ON COLUMN refunds.last_modified_date IS 'Igual a created_date: una devolución no cambia.';
COMMENT ON COLUMN refunds.created_by_id IS 'Administrador que registró la devolución.';
COMMENT ON COLUMN refunds.last_modified_by_id IS 'Usuario del último cambio; una devolución no cambia.';

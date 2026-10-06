-- TAR-46 (RF-07): egresos operativos del negocio (agua, luz, internet, otros). Junto con las multas, es el
-- único borrado físico permitido (RN-24): un egreso se edita o se elimina libremente, por eso no lleva version.

CREATE TABLE expenses (
    id                       UUID PRIMARY KEY,
    type                     VARCHAR(20) NOT NULL,
    environment_id           UUID NULL REFERENCES environments (id),
    amount                   NUMERIC(10, 2) NOT NULL,
    month                    DATE NOT NULL,
    description              TEXT NULL,
    created_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by               VARCHAR(200) NULL,
    last_modified_by         VARCHAR(200) NULL,
    CONSTRAINT ck_expenses_type CHECK (type IN ('WATER', 'ELECTRICITY', 'INTERNET', 'OTHER')),
    CONSTRAINT ck_expenses_amount CHECK (amount > 0),
    CONSTRAINT ck_expenses_month CHECK (month = date_trunc('month', month)::date)
);

CREATE INDEX ix_expenses_month ON expenses (month);

COMMENT ON TABLE expenses IS 'Egresos operativos del negocio, en conjunto y sin reparto entre integrantes (RF-07, RN-11).';
COMMENT ON COLUMN expenses.id IS 'Identificador del egreso (UUID generado por la aplicación).';
COMMENT ON COLUMN expenses.type IS 'Categoría: WATER, ELECTRICITY, INTERNET u OTHER.';
COMMENT ON COLUMN expenses.environment_id IS 'Ambiente al que se atribuye el gasto; opcional y aún sin uso (el contrato de RF-07 no lo pide).';
COMMENT ON COLUMN expenses.amount IS 'Monto en soles, mayor que 0.';
COMMENT ON COLUMN expenses.month IS 'Mes al que pertenece el egreso, truncado al primer día del mes.';
COMMENT ON COLUMN expenses.description IS 'Descripción opcional.';
COMMENT ON COLUMN expenses.created_date IS 'Fecha de creación del registro (auditoría).';
COMMENT ON COLUMN expenses.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN expenses.created_by IS 'Quién creó el registro (auditoría).';
COMMENT ON COLUMN expenses.last_modified_by IS 'Quién modificó el registro por última vez (auditoría).';

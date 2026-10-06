-- TAR-32 (RF-01): inventario de ambientes alquilables. Un ambiente se desactiva, nunca se borra (RN-12).
-- La ocupación no se guarda: se deriva de si el ambiente tiene un contrato vigente (RN-20).

CREATE TABLE environments (
    id                       UUID PRIMARY KEY,
    code                     VARCHAR(50) NOT NULL,
    type                     VARCHAR(20) NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version                  BIGINT NOT NULL DEFAULT 0,
    created_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by               VARCHAR(200) NULL,
    last_modified_by         VARCHAR(200) NULL,
    CONSTRAINT uq_environments_code UNIQUE (code),
    CONSTRAINT ck_environments_type CHECK (type IN ('ROOM', 'CABIN')),
    CONSTRAINT ck_environments_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

COMMENT ON TABLE environments IS 'Ambientes alquilables: habitaciones y cabañas (RF-01).';
COMMENT ON COLUMN environments.id IS 'Identificador del ambiente (UUID generado por la aplicación).';
COMMENT ON COLUMN environments.code IS 'Código visible del ambiente, por ejemplo "201"; único, sin espacios en los extremos.';
COMMENT ON COLUMN environments.type IS 'ROOM (habitación) o CABIN (cabaña).';
COMMENT ON COLUMN environments.status IS 'ACTIVE o INACTIVE; un ambiente INACTIVE no aparece en los listados operativos pero conserva su historial (RN-12).';
COMMENT ON COLUMN environments.version IS 'Versión para el bloqueo optimista: dos ediciones simultáneas no se pisan.';
COMMENT ON COLUMN environments.created_date IS 'Fecha de creación del registro (auditoría).';
COMMENT ON COLUMN environments.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN environments.created_by IS 'Quién creó el registro (auditoría).';
COMMENT ON COLUMN environments.last_modified_by IS 'Quién modificó el registro por última vez (auditoría).';

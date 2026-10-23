-- TAR-36 (RF-11, RF-17, RN-32): accesos que el administrador entrega al huésped, un enlace de activación o una
-- contraseña temporal. Valen 30 minutos y se usan una sola vez. Solo se guarda el SHA-256 del secreto: el
-- token del enlace y la contraseña temporal en claro nunca se guardan.
--
-- Qué decide la base y qué la aplicación:
--  * Un solo acceso vivo (sin usar y sin revocar) por huésped: índice único parcial. Generar uno nuevo revoca
--    el anterior en la misma transacción, con la fila del huésped bloqueada.
--  * El vencimiento lo compara la aplicación con su Clock; un acceso vencido sigue "vivo" para el índice hasta
--    que se revoca al generar el siguiente.
--  * Consumir el enlace es un UPDATE condicionado (sin usar, sin revocar, sin vencer): de dos peticiones
--    simultáneas con el mismo token solo una cambia la fila.

CREATE SEQUENCE access_grants_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE access_grants (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    mode                  VARCHAR(20) NOT NULL,
    token_hash            VARCHAR(64) NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    used_at               TIMESTAMPTZ NULL,
    revoked_at            TIMESTAMPTZ NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_access_grants_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT uq_access_grants_token_hash UNIQUE (token_hash),
    CONSTRAINT ck_access_grants_mode CHECK (mode IN ('LINK', 'TEMPORARY_PASSWORD'))
);

CREATE UNIQUE INDEX uq_access_grants_live ON access_grants (guest_id) WHERE used_at IS NULL AND revoked_at IS NULL;

COMMENT ON TABLE access_grants IS 'Accesos del huésped: enlace de activación o contraseña temporal, de un solo uso y 30 minutos (RF-11, RN-32).';
COMMENT ON COLUMN access_grants.id IS 'Identificador del acceso (BIGINT de la secuencia access_grants_seq); el BFF no lo muestra.';
COMMENT ON COLUMN access_grants.guest_id IS 'Huésped al que pertenece el acceso.';
COMMENT ON COLUMN access_grants.mode IS 'LINK (enlace de activación) o TEMPORARY_PASSWORD (contraseña temporal).';
COMMENT ON COLUMN access_grants.token_hash IS 'SHA-256 en hexadecimal del token del enlace o de la contraseña temporal; el secreto en claro nunca se guarda.';
COMMENT ON COLUMN access_grants.expires_at IS 'Vencimiento: 30 minutos después de generarlo (RN-32).';
COMMENT ON COLUMN access_grants.used_at IS 'Cuándo se usó (enlace activado o contraseña temporal reemplazada, D-30); NULL si no se usó.';
COMMENT ON COLUMN access_grants.revoked_at IS 'Cuándo se revocó al generar otro acceso (RF-17); NULL si no se revocó.';
COMMENT ON COLUMN access_grants.created_date IS 'Fecha de creación del registro (auditoría).';
COMMENT ON COLUMN access_grants.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN access_grants.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN access_grants.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

-- D-33, RN-19: el correo de la cuenta es opcional (un huésped puede no tener correo). Los índices únicos de V1 y
-- V2 ya admiten varios NULL: la unicidad queda solo entre los correos presentes.
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
COMMENT ON COLUMN users.email IS 'Correo de la cuenta, opcional (D-33, RN-19); si existe, único sin distinguir mayúsculas.';

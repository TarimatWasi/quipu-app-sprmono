ALTER TABLE users ADD COLUMN security_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE users ADD CONSTRAINT ck_users_security_version CHECK (security_version >= 0);
COMMENT ON COLUMN users.security_version IS 'Versión de seguridad de la cuenta; un JWT solo es válido si conserva esta versión. Se incrementa al cambiar contraseña, rol o desactivar la cuenta.';

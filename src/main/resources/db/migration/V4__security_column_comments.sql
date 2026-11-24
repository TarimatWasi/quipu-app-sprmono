-- Los comentarios de V1 decían "aún sin uso" de columnas que el bloqueo por intentos (RF-08) y la
-- recuperación de contraseña (RF-10) ya usan. Solo cambia la documentación del esquema; la prueba
-- MigrationDocumentationTest impide que una columna documentada sin uso aparezca en el código.

COMMENT ON COLUMN users.failed_login_attempts IS 'Intentos fallidos consecutivos de login; al llegar al límite la cuenta se bloquea un tiempo (RF-08) y un login correcto los reinicia.';
COMMENT ON COLUMN users.locked_until IS 'Hasta cuándo está bloqueada la cuenta tras demasiados intentos fallidos (RF-08); NULL si no lo está.';
COMMENT ON COLUMN users.reset_token_hash IS 'SHA-256 del código de recuperación de contraseña o del enlace de activación de un administrador (RF-10); el código en claro nunca se guarda.';
COMMENT ON COLUMN users.reset_token_expires_at IS 'Vencimiento del código de recuperación (30 minutos desde su emisión); también da su hora de emisión, para no reemitirlo dentro de la ventana de espera.';

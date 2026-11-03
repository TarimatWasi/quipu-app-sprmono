-- TAR-127 (RF-22, A0.5): el administrador creado por otro escribe su nombre al activar la cuenta.
ALTER TABLE users ADD COLUMN name VARCHAR(100) NULL;

COMMENT ON COLUMN users.name IS 'Nombre completo de un administrador, que escribe al activar su cuenta (A0.5, TAR-127); nulo mientras no la activa y en las cuentas de huésped, cuyo nombre vive en guests.';

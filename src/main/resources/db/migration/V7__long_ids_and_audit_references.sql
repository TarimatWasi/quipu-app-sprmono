-- TAR-165 (parte de TAR-163): los identificadores pasan de UUID a BIGINT y la auditoría pasa a ser una
-- referencia al usuario (AbstractAuditable de Spring Data).
--
-- ATENCIÓN: esta migración VACÍA users, guests, environments y expenses. UUID no se convierte a BIGINT, y
-- los únicos datos que existen son de prueba de development (producción no se ha desplegado). El
-- administrador inicial se vuelve a crear solo en el arranque (AdminBootstrap).

TRUNCATE TABLE users, guests, expenses, environments CASCADE;

-- Las claves foráneas que apuntan a ids cambian junto con ellos.
ALTER TABLE users DROP CONSTRAINT IF EXISTS fk_users_guest;
ALTER TABLE guests DROP CONSTRAINT IF EXISTS fk_guests_environment;
ALTER TABLE expenses DROP CONSTRAINT IF EXISTS expenses_environment_id_fkey;

ALTER TABLE environments ALTER COLUMN id TYPE BIGINT USING NULL;
ALTER TABLE guests ALTER COLUMN id TYPE BIGINT USING NULL;
ALTER TABLE guests ALTER COLUMN current_environment_id TYPE BIGINT USING NULL;
ALTER TABLE expenses ALTER COLUMN id TYPE BIGINT USING NULL;
ALTER TABLE expenses ALTER COLUMN environment_id TYPE BIGINT USING NULL;
ALTER TABLE users ALTER COLUMN id TYPE BIGINT USING NULL;
ALTER TABLE users ALTER COLUMN guest_id TYPE BIGINT USING NULL;

ALTER TABLE users ADD CONSTRAINT fk_users_guest FOREIGN KEY (guest_id) REFERENCES guests (id);
ALTER TABLE guests ADD CONSTRAINT fk_guests_environment FOREIGN KEY (current_environment_id) REFERENCES environments (id);
ALTER TABLE expenses ADD CONSTRAINT fk_expenses_environment FOREIGN KEY (environment_id) REFERENCES environments (id);

-- Hibernate pide los ids por bloques de 50: las secuencias avanzan de 50 en 50, una por tabla. Los ids de
-- users también los toma el INSERT del administrador inicial con nextval('users_seq').
CREATE SEQUENCE users_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE guests_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE environments_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE expenses_seq START WITH 1 INCREMENT BY 50;

-- Auditoría: created_by y last_modified_by guardaban texto; ahora son created_by_id y last_modified_by_id,
-- clave foránea hacia users. NULL significa que no hubo un usuario con sesión.
ALTER TABLE users DROP COLUMN created_by, DROP COLUMN last_modified_by;
ALTER TABLE users ADD COLUMN created_by_id BIGINT NULL REFERENCES users (id),
                  ADD COLUMN last_modified_by_id BIGINT NULL REFERENCES users (id);
ALTER TABLE environments DROP COLUMN created_by, DROP COLUMN last_modified_by;
ALTER TABLE environments ADD COLUMN created_by_id BIGINT NULL REFERENCES users (id),
                         ADD COLUMN last_modified_by_id BIGINT NULL REFERENCES users (id);
ALTER TABLE expenses DROP COLUMN created_by, DROP COLUMN last_modified_by;
ALTER TABLE expenses ADD COLUMN created_by_id BIGINT NULL REFERENCES users (id),
                     ADD COLUMN last_modified_by_id BIGINT NULL REFERENCES users (id);
ALTER TABLE guests DROP COLUMN created_by, DROP COLUMN last_modified_by;
ALTER TABLE guests ADD COLUMN created_by_id BIGINT NULL REFERENCES users (id),
                   ADD COLUMN last_modified_by_id BIGINT NULL REFERENCES users (id);

COMMENT ON COLUMN users.id IS 'Identificador del usuario (BIGINT de la secuencia users_seq); el BFF no lo muestra tal cual.';
COMMENT ON COLUMN environments.id IS 'Identificador del ambiente (BIGINT de la secuencia environments_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN expenses.id IS 'Identificador del egreso (BIGINT de la secuencia expenses_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN guests.id IS 'Identificador del huésped (BIGINT de la secuencia guests_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN users.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN users.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN environments.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN environments.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN expenses.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN expenses.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN guests.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN guests.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

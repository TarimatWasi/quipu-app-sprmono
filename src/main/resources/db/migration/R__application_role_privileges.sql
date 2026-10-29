-- Privilegios del rol de aplicación (TAR-214). La aplicación se conecta con un rol que no es dueño de las tablas:
-- así no puede vaciar con TRUNCATE ni quitar los disparadores de la evidencia de guest_document_actions, que solo
-- se inserta (RN-37, Ley 29733). Este script lo corre el dueño (Flyway con app.migration.url) y concede al rol de
-- aplicación lo que la aplicación usa, nada más; nada a PUBLIC. No crea roles ni contraseñas: el rol lo crea la
-- persona responsable en la base.
--
-- Es repetible (R__) y no versionado: Flyway lo vuelve a aplicar cada vez que cambia su contenido o el valor de
-- ${app_role}, así que un despliegue que lo corrió sin rol (no hace nada) concede los privilegios en el primer
-- despliegue con el rol configurado. Cada sentencia es idempotente.
--
-- Una tabla nueva del dueño recibe SELECT, INSERT, UPDATE y DELETE por los privilegios por defecto; si es de solo
-- inserción, la migración que la crea revoca UPDATE y DELETE y se agrega aquí a la comprobación final.

DO $$
DECLARE
    app_role CONSTANT text := '${app_role}';
    target   CONSTANT text := current_schema();
    history  CONSTANT text := '${flyway:table}';
BEGIN
    IF app_role = '' THEN
        RETURN; -- sin rol de aplicación (local, pruebas, despliegue sin separar): la aplicación usa al dueño
    END IF;

    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role) THEN
        RAISE EXCEPTION 'El rol de aplicación % no existe', app_role;
    END IF;

    -- Un superusuario o un rol que es (o hereda) al dueño podría TRUNCATE o DROP TRIGGER igual.
    IF app_role = current_user
        OR pg_has_role(app_role, current_user, 'MEMBER')
        OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = app_role AND rolsuper) THEN
        RAISE EXCEPTION 'El rol de aplicación no puede ser superusuario, el dueño de las tablas ni heredarlo';
    END IF;

    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', target, app_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I', target, app_role);
    EXECUTE format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', target, app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO %I',
                   target, app_role);
    EXECUTE format('ALTER DEFAULT PRIVILEGES IN SCHEMA %I GRANT USAGE, SELECT ON SEQUENCES TO %I',
                   target, app_role);

    -- El historial de migraciones es solo de Flyway.
    EXECUTE format('REVOKE ALL ON TABLE %I.%I FROM %I', target, history, app_role);

    -- La evidencia solo se inserta y se lee.
    EXECUTE format('REVOKE UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER ON TABLE %I.guest_document_actions FROM %I',
                   target, app_role);

    -- Comprobación final: ningún privilegio heredado (por ejemplo pg_write_all_data) deja modificar la evidencia.
    IF has_table_privilege(app_role, format('%I.guest_document_actions', target), 'UPDATE, DELETE, TRUNCATE') THEN
        RAISE EXCEPTION 'El rol de aplicación conserva UPDATE, DELETE o TRUNCATE sobre guest_document_actions por otra vía';
    END IF;
END
$$;

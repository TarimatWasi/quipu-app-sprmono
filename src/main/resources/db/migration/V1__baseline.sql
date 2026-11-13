-- TAR-377: esquema inicial consolidado de V1..V9, antes del primer pase a producción.
-- Solo para una base vacía: el historial anterior no es compatible y no se repara ni se falsea.
-- La base de desarrollo se recrea mediante el procedimiento coordinado del reinicio autorizado.
-- Las próximas evoluciones versionadas parten de V2; R__application_role_privileges conserva
-- la concesión repetible de permisos cuando cambia el rol de aplicación configurado.
-- No contiene datos ni usuarios semilla: el primer ADMIN lo crea AdminBootstrap al arrancar.
-- BIGINT y secuencias de 50 en 50; auditoría y comentarios conservan el esquema verificado de PG18.

-- Las funciones de los triggers se definen antes de sus tablas. Restaurar la comprobación al final.
SET check_function_bodies = false;

CREATE FUNCTION reject_onboarding_record_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    RAISE EXCEPTION 'Los registros de % no se modifican ni se borran', TG_TABLE_NAME;
END;
$$;

CREATE FUNCTION require_guest_document_action() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM guest_document_actions WHERE document_id = OLD.id AND guest_id = OLD.guest_id) THEN
        RAISE EXCEPTION 'Un documento del huésped solo se borra con su evidencia (RN-37)';
    END IF;
    RETURN OLD;
END;
$$;

CREATE TABLE access_grants (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    mode character varying(20) NOT NULL,
    token_hash character varying(64) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    used_at timestamp with time zone,
    revoked_at timestamp with time zone,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_access_grants_mode CHECK ((mode IN ('LINK', 'TEMPORARY_PASSWORD')))
);

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

CREATE SEQUENCE access_grants_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE contracts (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    environment_id bigint NOT NULL,
    start_date date NOT NULL,
    end_date date,
    monthly_amount numeric(10,2) NOT NULL,
    payment_day smallint NOT NULL,
    grace_days smallint NOT NULL,
    file_key character varying(255) NOT NULL,
    original_name character varying(255) NOT NULL,
    size_bytes bigint NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    terminated_at timestamp with time zone,
    CONSTRAINT ck_contracts_dates CHECK (((end_date IS NULL) OR (end_date > start_date))),
    CONSTRAINT ck_contracts_grace_days CHECK (((grace_days >= 0) AND (grace_days <= 31))),
    CONSTRAINT ck_contracts_monthly_amount CHECK ((monthly_amount > (0)::numeric)),
    CONSTRAINT ck_contracts_payment_day CHECK (((payment_day >= 1) AND (payment_day <= 31))),
    CONSTRAINT ck_contracts_size_bytes CHECK ((size_bytes > 0)),
    CONSTRAINT ck_contracts_status CHECK ((status IN ('ACTIVE', 'TERMINATED'))),
    CONSTRAINT ck_contracts_terminated_at CHECK ((((status)::text = 'TERMINATED'::text) = (terminated_at IS NOT NULL)))
);

COMMENT ON TABLE contracts IS 'Contratos de los huéspedes con contrato (RF-03): ambiente, condiciones de pago y PDF. El vencimiento se deriva de end_date; no se borran.';

COMMENT ON COLUMN contracts.id IS 'Identificador del contrato (BIGINT de la secuencia contracts_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN contracts.guest_id IS 'Huésped con contrato al que pertenece.';

COMMENT ON COLUMN contracts.environment_id IS 'Ambiente que ocupa mientras el contrato está vigente (RN-01, RN-20).';

COMMENT ON COLUMN contracts.start_date IS 'Primer día del contrato, inclusive; puede ser futuro y aun así ocupa el ambiente.';

COMMENT ON COLUMN contracts.end_date IS 'Último día del contrato, inclusive; NULL es un contrato indefinido (RN-07); es posterior a start_date.';

COMMENT ON COLUMN contracts.monthly_amount IS 'Monto mensual en soles, mayor que 0.';

COMMENT ON COLUMN contracts.payment_day IS 'Día del mes en que vence el pago, de 1 a 31 (RN-10).';

COMMENT ON COLUMN contracts.grace_days IS 'Días de gracia después del día de pago, de 0 a 31 (RN-10).';

COMMENT ON COLUMN contracts.file_key IS 'Clave del objeto PDF en el bucket R2; nunca la URL firmada, que se firma al pedir el contrato y caduca.';

COMMENT ON COLUMN contracts.original_name IS 'Nombre del archivo que subió el administrador, saneado.';

COMMENT ON COLUMN contracts.size_bytes IS 'Tamaño del PDF en bytes, mayor que 0.';

COMMENT ON COLUMN contracts.status IS 'ACTIVE o TERMINATED (finalizado a mano, RN-21). Vencido no se guarda: se deriva de end_date.';

COMMENT ON COLUMN contracts.version IS 'Versión para el bloqueo optimista: dos finalizaciones simultáneas no se pisan.';

COMMENT ON COLUMN contracts.created_date IS 'Fecha de creación del registro (auditoría).';

COMMENT ON COLUMN contracts.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN contracts.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN contracts.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN contracts.terminated_at IS 'Instante en que el administrador finalizó el contrato a mano (RN-21); NULL mientras no se finalizó. El último mes que cobra es el de esta fecha en America/Lima.';

CREATE SEQUENCE contracts_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE environments (
    id bigint NOT NULL,
    code character varying(50) NOT NULL,
    type character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    deactivated_at timestamp with time zone,
    CONSTRAINT ck_environments_status CHECK ((status IN ('ACTIVE', 'INACTIVE'))),
    CONSTRAINT ck_environments_type CHECK ((type IN ('ROOM', 'CABIN')))
);

COMMENT ON TABLE environments IS 'Ambientes alquilables: habitaciones y cabañas (RF-01).';

COMMENT ON COLUMN environments.id IS 'Identificador del ambiente (BIGINT de la secuencia environments_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN environments.code IS 'Código visible del ambiente, por ejemplo "201"; único, sin espacios en los extremos.';

COMMENT ON COLUMN environments.type IS 'ROOM (habitación) o CABIN (cabaña).';

COMMENT ON COLUMN environments.status IS 'ACTIVE o INACTIVE; un ambiente INACTIVE no aparece en los listados operativos pero conserva su historial (RN-12).';

COMMENT ON COLUMN environments.version IS 'Versión para el bloqueo optimista: dos ediciones simultáneas no se pisan.';

COMMENT ON COLUMN environments.created_date IS 'Fecha de creación del registro (auditoría).';

COMMENT ON COLUMN environments.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN environments.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN environments.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN environments.deactivated_at IS 'Cuándo se desactivó el ambiente (A2.1, TAR-237); nulo mientras está activo.';

CREATE SEQUENCE environments_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE expenses (
    id bigint NOT NULL,
    type character varying(20) NOT NULL,
    environment_id bigint,
    amount numeric(10,2) NOT NULL,
    month date NOT NULL,
    description text,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_expenses_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT ck_expenses_month CHECK ((month = (date_trunc('month'::text, (month)::timestamp with time zone))::date)),
    CONSTRAINT ck_expenses_type CHECK ((type IN ('UTILITIES', 'MAINTENANCE', 'CLEANING_SUPPLIES', 'TAXES_FEES')))
);

COMMENT ON TABLE expenses IS 'Egresos operativos del negocio, en conjunto y sin reparto entre integrantes (RF-07, RN-11); se editan con bloqueo optimista y se eliminan libremente (RN-24).';

COMMENT ON COLUMN expenses.id IS 'Identificador del egreso (BIGINT de la secuencia expenses_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN expenses.type IS 'Categoría: UTILITIES (luz, agua, internet), MAINTENANCE (mantenimiento y reparaciones), CLEANING_SUPPLIES (limpieza e insumos) o TAXES_FEES (impuestos y tasas).';

COMMENT ON COLUMN expenses.environment_id IS 'Ambiente al que se atribuye el gasto; opcional y aún sin uso (el contrato de RF-07 no lo pide).';

COMMENT ON COLUMN expenses.amount IS 'Monto en soles, mayor que 0.';

COMMENT ON COLUMN expenses.month IS 'Mes al que pertenece el egreso, truncado al primer día del mes.';

COMMENT ON COLUMN expenses.description IS 'Descripción opcional.';

COMMENT ON COLUMN expenses.created_date IS 'Fecha de creación del registro (auditoría).';

COMMENT ON COLUMN expenses.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN expenses.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN expenses.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN expenses.version IS 'Versión para el bloqueo optimista: dos ediciones simultáneas no se pisan.';

CREATE SEQUENCE expenses_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE fines (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    amount numeric(10,2) NOT NULL,
    month date NOT NULL,
    description text,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_fines_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT ck_fines_description CHECK ((char_length(description) <= 500)),
    CONSTRAINT ck_fines_month CHECK ((month = (date_trunc('month'::text, (month)::timestamp with time zone))::date))
);

COMMENT ON TABLE fines IS 'Multas cobradas a un huésped, ingreso directo de su mes (RF-19, RN-30); no se editan y se eliminan libremente (RN-24).';

COMMENT ON COLUMN fines.id IS 'Identificador de la multa (BIGINT de la secuencia fines_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN fines.guest_id IS 'Huésped al que se cobra la multa, activo o inactivo.';

COMMENT ON COLUMN fines.amount IS 'Monto en soles, mayor que 0.';

COMMENT ON COLUMN fines.month IS 'Mes en que la multa cuenta como ingreso, truncado al primer día del mes.';

COMMENT ON COLUMN fines.description IS 'Descripción opcional, de hasta 500 caracteres.';

COMMENT ON COLUMN fines.created_date IS 'Fecha de creación del registro (auditoría).';

COMMENT ON COLUMN fines.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN fines.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN fines.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

CREATE SEQUENCE fines_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE guest_consents (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    consent_version character varying(50) NOT NULL,
    accepted_at timestamp with time zone NOT NULL,
    text_sha256 character varying(64) NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_guest_consents_text_sha256 CHECK (((text_sha256)::text ~ '^[0-9a-f]{64}$'::text))
);

COMMENT ON TABLE guest_consents IS 'Consentimientos de tratamiento de datos personales que aceptó el huésped (RF-14, RN-14). No se modifican ni se borran.';

COMMENT ON COLUMN guest_consents.id IS 'Identificador del consentimiento (BIGINT de la secuencia guest_consents_seq); el BFF no lo muestra.';

COMMENT ON COLUMN guest_consents.guest_id IS 'Huésped que aceptó.';

COMMENT ON COLUMN guest_consents.consent_version IS 'Versión del texto aceptado, la vigente en la configuración del backend al aceptar.';

COMMENT ON COLUMN guest_consents.accepted_at IS 'Fecha y hora en que el huésped aceptó (RN-14).';

COMMENT ON COLUMN guest_consents.text_sha256 IS 'SHA-256 en hexadecimal del texto aceptado, para probar qué texto exacto se mostró.';

COMMENT ON COLUMN guest_consents.created_date IS 'Fecha de creación del registro (auditoría).';

COMMENT ON COLUMN guest_consents.last_modified_date IS 'Fecha de la última modificación del registro (auditoría); igual a la de creación, el registro no cambia.';

COMMENT ON COLUMN guest_consents.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN guest_consents.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

CREATE SEQUENCE guest_consents_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE guest_document_actions (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    document_id bigint NOT NULL,
    replacement_document_id bigint,
    action character varying(20) NOT NULL,
    reason character varying(20) NOT NULL,
    requested_at date,
    request_channel character varying(20),
    note character varying(500),
    content_type character varying(100) NOT NULL,
    size_bytes bigint NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint NOT NULL,
    last_modified_by_id bigint,
    CONSTRAINT ck_guest_document_actions_action CHECK ((action IN ('DELETE', 'REPLACE'))),
    CONSTRAINT ck_guest_document_actions_channel CHECK ((request_channel IN ('WHATSAPP', 'EMAIL', 'IN_PERSON', 'OTHER'))),
    CONSTRAINT ck_guest_document_actions_content_type CHECK ((content_type IN ('image/jpeg', 'image/png', 'application/pdf'))),
    CONSTRAINT ck_guest_document_actions_note CHECK (((note IS NULL) OR (btrim((note)::text) <> ''::text))),
    CONSTRAINT ck_guest_document_actions_reason CHECK (((((reason)::text = 'ARCO_REQUEST'::text) AND (requested_at IS NOT NULL) AND (request_channel IS NOT NULL)) OR (((reason)::text = 'UPLOAD_ERROR'::text) AND (requested_at IS NULL) AND (request_channel IS NULL)))),
    CONSTRAINT ck_guest_document_actions_replacement CHECK ((((action)::text = 'REPLACE'::text) = (replacement_document_id IS NOT NULL))),
    CONSTRAINT ck_guest_document_actions_size_bytes CHECK (((size_bytes > 0) AND (size_bytes <= 5242880)))
);

COMMENT ON TABLE guest_document_actions IS 'Evidencia de cada borrado o reemplazo de un documento del huésped por el administrador (RN-37): quién, cuándo y por qué. Solo se inserta; nunca guarda el nombre, la clave ni el contenido del archivo.';

COMMENT ON COLUMN guest_document_actions.id IS 'Identificador de la acción (BIGINT de la secuencia guest_document_actions_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN guest_document_actions.guest_id IS 'Huésped dueño del documento.';

COMMENT ON COLUMN guest_document_actions.document_id IS 'Documento borrado o reemplazado; sin clave foránea, porque la fila del documento ya no existe.';

COMMENT ON COLUMN guest_document_actions.replacement_document_id IS 'Documento nuevo de un reemplazo (REPLACE); NULL en un borrado. Sin clave foránea: el nuevo también puede borrarse después.';

COMMENT ON COLUMN guest_document_actions.action IS 'DELETE (borrado) o REPLACE (reemplazo).';

COMMENT ON COLUMN guest_document_actions.reason IS 'ARCO_REQUEST (solicitud ARCO del titular) o UPLOAD_ERROR (error de carga).';

COMMENT ON COLUMN guest_document_actions.requested_at IS 'Fecha en que el titular hizo la solicitud ARCO (America/Lima); obligatoria con ARCO_REQUEST, NULL con UPLOAD_ERROR.';

COMMENT ON COLUMN guest_document_actions.request_channel IS 'Canal de la solicitud ARCO (WHATSAPP, EMAIL, IN_PERSON u OTHER); obligatorio con ARCO_REQUEST, NULL con UPLOAD_ERROR.';

COMMENT ON COLUMN guest_document_actions.note IS 'Referencia opcional (ticket o canal), sin datos personales del titular; NULL si no hay. Nunca se registra en logs.';

COMMENT ON COLUMN guest_document_actions.content_type IS 'Tipo del archivo borrado o reemplazado (image/jpeg, image/png o application/pdf).';

COMMENT ON COLUMN guest_document_actions.size_bytes IS 'Tamaño en bytes del archivo borrado o reemplazado.';

COMMENT ON COLUMN guest_document_actions.created_date IS 'Fecha y hora de la acción (auditoría); en un borrado, cuándo lo pidió el administrador.';

COMMENT ON COLUMN guest_document_actions.last_modified_date IS 'Fecha de la última modificación del registro (auditoría); igual a la de creación, el registro no cambia.';

COMMENT ON COLUMN guest_document_actions.created_by_id IS 'Administrador que hizo la acción (actor, auditoría); obligatorio.';

COMMENT ON COLUMN guest_document_actions.last_modified_by_id IS 'Usuario que modificó el registro por última vez (auditoría); igual al actor, el registro no cambia.';

CREATE SEQUENCE guest_document_actions_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE guest_document_deletions (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    document_id bigint NOT NULL,
    reason character varying(20) NOT NULL,
    requested_at date,
    request_channel character varying(20),
    note character varying(500),
    content_type character varying(100) NOT NULL,
    size_bytes bigint NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint NOT NULL,
    last_modified_by_id bigint,
    CONSTRAINT ck_guest_document_deletions_channel CHECK ((request_channel IN ('WHATSAPP', 'EMAIL', 'IN_PERSON', 'OTHER'))),
    CONSTRAINT ck_guest_document_deletions_content_type CHECK ((content_type IN ('image/jpeg', 'image/png', 'application/pdf'))),
    CONSTRAINT ck_guest_document_deletions_note CHECK (((note IS NULL) OR (btrim((note)::text) <> ''::text))),
    CONSTRAINT ck_guest_document_deletions_reason CHECK (((((reason)::text = 'ARCO_REQUEST'::text) AND (requested_at IS NOT NULL) AND (request_channel IS NOT NULL)) OR (((reason)::text = 'UPLOAD_ERROR'::text) AND (requested_at IS NULL) AND (request_channel IS NULL)))),
    CONSTRAINT ck_guest_document_deletions_size_bytes CHECK (((size_bytes > 0) AND (size_bytes <= 5242880)))
);

COMMENT ON TABLE guest_document_deletions IS 'Borrados de documentos del huésped en curso (RN-37): la intención se guarda antes de borrar el objeto de R2 y se consolida en guest_document_actions cuando la fila del documento se borra; si queda, la aplicación termina el borrado. Nunca guarda el nombre, la clave ni el contenido del archivo.';

COMMENT ON COLUMN guest_document_deletions.id IS 'Identificador de la intención (BIGINT de la secuencia guest_document_deletions_seq); interno, el BFF no lo muestra.';

COMMENT ON COLUMN guest_document_deletions.guest_id IS 'Huésped dueño del documento; sin clave foránea (ver la migración).';

COMMENT ON COLUMN guest_document_deletions.document_id IS 'Documento que se borra; uno por documento.';

COMMENT ON COLUMN guest_document_deletions.reason IS 'ARCO_REQUEST (solicitud ARCO del titular) o UPLOAD_ERROR (error de carga).';

COMMENT ON COLUMN guest_document_deletions.requested_at IS 'Fecha de la solicitud ARCO (America/Lima); obligatoria con ARCO_REQUEST, NULL con UPLOAD_ERROR.';

COMMENT ON COLUMN guest_document_deletions.request_channel IS 'Canal de la solicitud ARCO; obligatorio con ARCO_REQUEST, NULL con UPLOAD_ERROR.';

COMMENT ON COLUMN guest_document_deletions.note IS 'Referencia opcional (ticket o canal), sin datos personales; nunca se registra en logs.';

COMMENT ON COLUMN guest_document_deletions.content_type IS 'Tipo del archivo que se borra.';

COMMENT ON COLUMN guest_document_deletions.size_bytes IS 'Tamaño en bytes del archivo que se borra.';

COMMENT ON COLUMN guest_document_deletions.created_date IS 'Cuándo pidió el administrador el borrado; es la fecha de la evidencia.';

COMMENT ON COLUMN guest_document_deletions.last_modified_date IS 'Fecha de la última modificación del registro; igual a la de creación.';

COMMENT ON COLUMN guest_document_deletions.created_by_id IS 'Administrador que pidió el borrado (actor de la evidencia); obligatorio.';

COMMENT ON COLUMN guest_document_deletions.last_modified_by_id IS 'Usuario que modificó el registro por última vez; igual al actor.';

CREATE SEQUENCE guest_document_deletions_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE guest_document_purges (
    id bigint NOT NULL,
    storage_key character varying(255) NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint
);

COMMENT ON TABLE guest_document_purges IS 'Objetos de R2 de documentos del huésped que ya no tienen fila (el archivo antiguo de un reemplazo, RN-37) y falta borrar del bucket; la aplicación los reintenta hasta lograrlo y entonces borra la fila.';

COMMENT ON COLUMN guest_document_purges.id IS 'Identificador del pendiente (BIGINT de la secuencia guest_document_purges_seq); interno, el BFF no lo muestra.';

COMMENT ON COLUMN guest_document_purges.storage_key IS 'Clave aleatoria del objeto en el bucket R2, bajo guest-documents/; nunca la URL firmada. Nunca se registra en logs.';

COMMENT ON COLUMN guest_document_purges.created_date IS 'Fecha de creación del registro (auditoría): cuándo quedó pendiente el borrado.';

COMMENT ON COLUMN guest_document_purges.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN guest_document_purges.created_by_id IS 'Usuario con sesión que dejó pendiente el borrado (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN guest_document_purges.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

CREATE SEQUENCE guest_document_purges_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE guest_documents (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    storage_key character varying(255) NOT NULL,
    original_name character varying(255) NOT NULL,
    content_type character varying(100) NOT NULL,
    size_bytes bigint NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_guest_documents_content_type CHECK ((content_type IN ('image/jpeg', 'image/png', 'application/pdf'))),
    CONSTRAINT ck_guest_documents_size_bytes CHECK (((size_bytes > 0) AND (size_bytes <= 5242880)))
);

COMMENT ON TABLE guest_documents IS 'Documentos que el huésped sube en su onboarding (RN-37): a lo sumo 5, solo los ve el administrador. No se modifican ni se borran.';

COMMENT ON COLUMN guest_documents.id IS 'Identificador del documento (BIGINT de la secuencia guest_documents_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN guest_documents.guest_id IS 'Huésped que subió el documento.';

COMMENT ON COLUMN guest_documents.storage_key IS 'Clave aleatoria del objeto en el bucket R2, bajo guest-documents/; nunca la URL firmada.';

COMMENT ON COLUMN guest_documents.original_name IS 'Nombre del archivo que subió el huésped, saneado.';

COMMENT ON COLUMN guest_documents.content_type IS 'Tipo del archivo (image/jpeg, image/png o application/pdf), deducido del contenido y nunca del que declara el cliente.';

COMMENT ON COLUMN guest_documents.size_bytes IS 'Tamaño del archivo en bytes, entre 1 y 5 MB (RF-15).';

COMMENT ON COLUMN guest_documents.created_date IS 'Fecha de creación del registro (auditoría); es cuándo se subió el documento.';

COMMENT ON COLUMN guest_documents.last_modified_date IS 'Fecha de la última modificación del registro (auditoría); igual a la de creación, el registro no cambia.';

COMMENT ON COLUMN guest_documents.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN guest_documents.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

CREATE SEQUENCE guest_documents_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE guest_email_verifications (
    guest_id bigint NOT NULL,
    email character varying(200) NOT NULL,
    code_hash character varying(255) NOT NULL,
    expires_at timestamp with time zone NOT NULL,
    last_sent_at timestamp with time zone NOT NULL,
    attempts smallint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_guest_email_verification_attempts CHECK (((attempts >= 0) AND (attempts <= 5)))
);

COMMENT ON TABLE guest_email_verifications IS 'Solicitud pendiente de confirmar el correo del huésped; el correo vigente no cambia hasta validar el código (TAR-349).';

COMMENT ON COLUMN guest_email_verifications.guest_id IS 'Huésped que pidió el cambio; a lo sumo una solicitud pendiente por huésped.';

COMMENT ON COLUMN guest_email_verifications.email IS 'Correo nuevo que espera su código; pasa a ser el correo de la cuenta al confirmarlo.';

COMMENT ON COLUMN guest_email_verifications.code_hash IS 'Hash del código de 6 dígitos enviado al correo nuevo; el código nunca se guarda en claro.';

COMMENT ON COLUMN guest_email_verifications.expires_at IS 'Instante en que el código deja de servir (10 minutos después del envío).';

COMMENT ON COLUMN guest_email_verifications.last_sent_at IS 'Último envío de un código; otro envío espera 60 segundos desde aquí.';

COMMENT ON COLUMN guest_email_verifications.attempts IS 'Códigos equivocados ingresados; con 5 la solicitud vence y hay que pedir otro código.';

CREATE TABLE guests (
    id bigint NOT NULL,
    full_name character varying(200),
    document_type character varying(20) NOT NULL,
    document_number character varying(20) NOT NULL,
    phone character varying(30),
    guest_type character varying(20) NOT NULL,
    current_environment_id bigint,
    emergency_contact_name character varying(200),
    emergency_contact_relationship character varying(50),
    emergency_contact_phone character varying(30),
    status character varying(20) DEFAULT 'PENDING_ACTIVATION'::character varying NOT NULL,
    status_before_inactive character varying(20),
    personal_data_completed_at timestamp with time zone,
    documents_step_completed_at timestamp with time zone,
    version bigint DEFAULT 0 NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_guests_document_type CHECK ((document_type IN ('DNI', 'CE', 'PASSPORT'))),
    CONSTRAINT ck_guests_status CHECK ((status IN ('PENDING_ACTIVATION', 'ONBOARDING', 'ACTIVE', 'INACTIVE'))),
    CONSTRAINT ck_guests_status_before_inactive CHECK (((status_before_inactive IS NULL) OR (status_before_inactive IN ('PENDING_ACTIVATION', 'ONBOARDING', 'ACTIVE')))),
    CONSTRAINT ck_guests_type CHECK ((guest_type IN ('CONTRACT', 'TEMPORARY')))
);

COMMENT ON TABLE guests IS 'Huéspedes con contrato y temporales (RF-02). Se desactivan, nunca se borran (RN-12).';

COMMENT ON COLUMN guests.id IS 'Identificador del huésped (BIGINT de la secuencia guests_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN guests.full_name IS 'Nombre completo; NULL hasta que el huésped completa sus datos personales en el onboarding (RN-33).';

COMMENT ON COLUMN guests.document_type IS 'DNI, CE o PASSPORT; con document_number es la clave de la persona (RN-18).';

COMMENT ON COLUMN guests.document_number IS 'Número de documento; único junto con document_type (RN-18).';

COMMENT ON COLUMN guests.phone IS 'Teléfono de contacto; el administrador puede darlo al registrar y el huésped lo completa en el onboarding (RN-31, RN-33).';

COMMENT ON COLUMN guests.guest_type IS 'CONTRACT (con contrato) o TEMPORARY (estadía corta).';

COMMENT ON COLUMN guests.current_environment_id IS 'Ambiente que ocupa hoy, si lo hay.';

COMMENT ON COLUMN guests.emergency_contact_name IS 'Nombre del contacto de emergencia, opcional (RN-29).';

COMMENT ON COLUMN guests.emergency_contact_relationship IS 'Parentesco del contacto de emergencia, opcional (RN-29).';

COMMENT ON COLUMN guests.emergency_contact_phone IS 'Celular del contacto de emergencia, opcional (RN-29).';

COMMENT ON COLUMN guests.status IS 'PENDING_ACTIVATION, ONBOARDING, ACTIVE o INACTIVE (RN-36).';

COMMENT ON COLUMN guests.status_before_inactive IS 'Estado que tenía al desactivarlo, para restaurarlo al reactivar (CU-28); NULL si no está inactivo.';

COMMENT ON COLUMN guests.personal_data_completed_at IS 'Cuándo el huésped completó el paso de datos personales (RN-33); NULL si aún no.';

COMMENT ON COLUMN guests.documents_step_completed_at IS 'Cuándo el huésped subió o saltó el paso de documentos (RN-37); NULL si aún no.';

COMMENT ON COLUMN guests.version IS 'Versión para el bloqueo optimista: dos ediciones simultáneas no se pisan.';

COMMENT ON COLUMN guests.created_date IS 'Fecha de creación del registro (auditoría).';

COMMENT ON COLUMN guests.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN guests.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN guests.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

CREATE SEQUENCE guests_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE payment_proofs (
    id bigint NOT NULL,
    payment_id bigint NOT NULL,
    kind character varying(10) NOT NULL,
    declared_amount numeric(10,2) NOT NULL,
    verified_amount numeric(10,2),
    method character varying(50),
    file_key character varying(255),
    original_name character varying(255),
    content_type character varying(100),
    size_bytes bigint,
    uploaded_by character varying(20) NOT NULL,
    received_on date,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_payment_proofs_declared_amount CHECK ((declared_amount > (0)::numeric)),
    CONSTRAINT ck_payment_proofs_file CHECK (((((kind)::text = 'VOUCHER'::text) AND (file_key IS NOT NULL) AND (original_name IS NOT NULL) AND (content_type IN ('image/jpeg', 'image/png', 'application/pdf')) AND (size_bytes > 0)) OR (((kind)::text = 'CASH'::text) AND (file_key IS NULL) AND (original_name IS NULL) AND (content_type IS NULL) AND (size_bytes IS NULL)))),
    CONSTRAINT ck_payment_proofs_kind CHECK ((kind IN ('VOUCHER', 'CASH'))),
    CONSTRAINT ck_payment_proofs_uploaded_by CHECK ((uploaded_by IN ('GUEST', 'ADMIN'))),
    CONSTRAINT ck_payment_proofs_verified_amount CHECK (((verified_amount IS NULL) OR (verified_amount >= (0)::numeric)))
);

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

CREATE SEQUENCE payment_proofs_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE payment_reviews (
    id bigint NOT NULL,
    payment_id bigint NOT NULL,
    decision character varying(20) NOT NULL,
    reason character varying(500),
    reviewed_at timestamp with time zone NOT NULL,
    reviewed_by_id bigint NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_payment_reviews_decision CHECK ((decision IN ('APPROVED', 'REJECTED'))),
    CONSTRAINT ck_payment_reviews_reason CHECK ((((decision)::text <> 'REJECTED'::text) OR ((reason IS NOT NULL) AND (length(btrim((reason)::text)) > 0))))
);

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

CREATE SEQUENCE payment_reviews_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE payments (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    contract_id bigint,
    stay_id bigint,
    period date,
    amount numeric(10,2) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    rejection_reason character varying(500),
    reviewed_at timestamp with time zone,
    reviewed_by_id bigint,
    recorded_by character varying(20) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    submitted_at timestamp with time zone NOT NULL,
    verified_amount numeric(10,2),
    CONSTRAINT ck_payments_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT ck_payments_period_first_day CHECK (((period IS NULL) OR (EXTRACT(day FROM period) = (1)::numeric))),
    CONSTRAINT ck_payments_recorded_by CHECK ((recorded_by IN ('GUEST', 'ADMIN'))),
    CONSTRAINT ck_payments_rejection_reason CHECK (((status NOT IN ('REJECTED', 'SUPERSEDED')) OR ((rejection_reason IS NOT NULL) AND (length(btrim((rejection_reason)::text)) > 0)))),
    CONSTRAINT ck_payments_review CHECK (((((status)::text = 'PENDING'::text) AND (reviewed_at IS NULL) AND (reviewed_by_id IS NULL)) OR (((status)::text <> 'PENDING'::text) AND (reviewed_at IS NOT NULL) AND (reviewed_by_id IS NOT NULL)))),
    CONSTRAINT ck_payments_status CHECK ((status IN ('PENDING', 'APPROVED', 'REJECTED', 'SUPERSEDED'))),
    CONSTRAINT ck_payments_target CHECK ((((contract_id IS NOT NULL) AND (period IS NOT NULL) AND (stay_id IS NULL)) OR ((stay_id IS NOT NULL) AND (contract_id IS NULL) AND (period IS NULL)))),
    CONSTRAINT ck_payments_verified_amount CHECK (((((status)::text = 'APPROVED'::text) AND (verified_amount IS NOT NULL) AND (verified_amount >= (0)::numeric)) OR (((status)::text <> 'APPROVED'::text) AND (verified_amount IS NULL))))
);

COMMENT ON TABLE payments IS 'Pagos de un período de un contrato o de una estadía (RN-02, RN-03), con sus comprobantes en payment_proofs (RN-22) y sus decisiones en payment_reviews. Un rechazado se corrige y se envía de nuevo (RN-05). No se borran.';

COMMENT ON COLUMN payments.id IS 'Identificador del pago (BIGINT de la secuencia payments_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN payments.guest_id IS 'Huésped que paga (RN-09: solo ve los suyos).';

COMMENT ON COLUMN payments.contract_id IS 'Contrato al que pertenece el período; NULL en el pago de una estadía.';

COMMENT ON COLUMN payments.stay_id IS 'Estadía que paga un huésped temporal (RN-03); NULL en el pago de un período de contrato.';

COMMENT ON COLUMN payments.period IS 'Primer día del mes que se paga (RN-02, sin prorrateo); NULL en el pago de una estadía.';

COMMENT ON COLUMN payments.amount IS 'Suma de los montos declarados de sus comprobantes, en soles (RN-16).';

COMMENT ON COLUMN payments.status IS 'PENDING, APPROVED o REJECTED; SUPERSEDED es un rechazo de antes de TAR-239 que otro pago del mismo período o estadía reemplazó: queda como historia y se muestra como REJECTED.';

COMMENT ON COLUMN payments.rejection_reason IS 'Motivo del último rechazo mientras el pago sigue rechazado (RN-05, D-34d); la historia de motivos está en payment_reviews.';

COMMENT ON COLUMN payments.reviewed_at IS 'Cuándo se decidió por última vez; nulo mientras está pendiente (también después de una corrección que lo envía de nuevo).';

COMMENT ON COLUMN payments.reviewed_by_id IS 'Administrador de la última decisión (TAR-230); nulo mientras está pendiente.';

COMMENT ON COLUMN payments.recorded_by IS 'GUEST (subió su voucher) o ADMIN (lo registró en su nombre, RN-23); lo fija el servidor.';

COMMENT ON COLUMN payments.version IS 'Versión para el bloqueo optimista: dos resoluciones simultáneas no se pisan.';

COMMENT ON COLUMN payments.created_date IS 'Fecha de creación del registro (auditoría); es cuándo se subió el voucher.';

COMMENT ON COLUMN payments.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN payments.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN payments.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN payments.submitted_at IS 'Último envío del pago (TAR-239): al crearlo, y otra vez cuando el huésped corrige uno rechazado. Ordena la cola de revisión.';

COMMENT ON COLUMN payments.verified_amount IS 'Suma de los montos verificados de sus comprobantes al aprobar (RN-16); nula mientras no está aprobado.';

CREATE SEQUENCE payments_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE refresh_tokens (
    id bigint NOT NULL,
    session_id bigint NOT NULL,
    token_hash character varying(64) NOT NULL,
    issued_at timestamp with time zone NOT NULL,
    rotated_at timestamp with time zone,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint
);

COMMENT ON TABLE refresh_tokens IS 'Refresh tokens de cada sesión (TAR-228); a lo sumo uno sin rotar por sesión. Uno ya rotado que vuelve a presentarse cierra la sesión.';

COMMENT ON COLUMN refresh_tokens.id IS 'Identificador interno del refresh token.';

COMMENT ON COLUMN refresh_tokens.session_id IS 'Sesión (familia de rotación) a la que pertenece.';

COMMENT ON COLUMN refresh_tokens.token_hash IS 'SHA-256 de la parte secreta del refresh token; el token nunca se guarda en claro.';

COMMENT ON COLUMN refresh_tokens.issued_at IS 'Cuándo se emitió.';

COMMENT ON COLUMN refresh_tokens.rotated_at IS 'Cuándo se cambió por uno nuevo; desde 30 segundos después, presentarlo es una reutilización.';

COMMENT ON COLUMN refresh_tokens.created_date IS 'Auditoría: cuándo se creó la fila.';

COMMENT ON COLUMN refresh_tokens.last_modified_date IS 'Auditoría: última modificación de la fila.';

COMMENT ON COLUMN refresh_tokens.created_by_id IS 'Auditoría: usuario que creó la fila.';

COMMENT ON COLUMN refresh_tokens.last_modified_by_id IS 'Auditoría: usuario que modificó la fila por última vez.';

CREATE SEQUENCE refresh_tokens_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE refunds (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    from_payment_id bigint NOT NULL,
    amount numeric(10,2) NOT NULL,
    refunded_on date NOT NULL,
    method character varying(50) NOT NULL,
    note character varying(500),
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_refunds_amount CHECK ((amount > (0)::numeric)),
    CONSTRAINT ck_refunds_method CHECK ((length(btrim((method)::text)) > 0)),
    CONSTRAINT ck_refunds_note CHECK (((note IS NULL) OR (length(btrim((note)::text)) > 0)))
);

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

CREATE SEQUENCE refunds_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE sessions (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    sid_hash character varying(64) NOT NULL,
    security_version bigint NOT NULL,
    temporary boolean NOT NULL,
    absolute_expires_at timestamp with time zone NOT NULL,
    last_used_at timestamp with time zone NOT NULL,
    revoked_at timestamp with time zone,
    revoked_reason character varying(20),
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    CONSTRAINT ck_sessions_revoked CHECK (((revoked_at IS NULL) = (revoked_reason IS NULL))),
    CONSTRAINT ck_sessions_revoked_reason CHECK ((revoked_reason IN ('LOGOUT', 'REUSE', 'PASSWORD_CHANGE', 'INVALIDATED'))),
    CONSTRAINT ck_sessions_security_version CHECK ((security_version >= 0))
);

COMMENT ON TABLE sessions IS 'Sesión abierta con login o activación (TAR-228); varias por cuenta, una por dispositivo. El access token la nombra con el claim sid.';

COMMENT ON COLUMN sessions.id IS 'Identificador interno de la sesión.';

COMMENT ON COLUMN sessions.user_id IS 'Cuenta dueña de la sesión.';

COMMENT ON COLUMN sessions.sid_hash IS 'SHA-256 del sid (TAR-227) que llevan el access token y el refresh token; el sid nunca se guarda en claro.';

COMMENT ON COLUMN sessions.security_version IS 'users.security_version con la que se emitió; si la cuenta cambió de contraseña o rol o se desactivó, el refresh ya no la renueva.';

COMMENT ON COLUMN sessions.temporary IS 'Abierta con una contraseña temporal (RF-12): solo puede elegir la contraseña y no tiene refresh token hasta hacerlo.';

COMMENT ON COLUMN sessions.absolute_expires_at IS 'Tope de la sesión: 30 días desde el login (o el vencimiento de la contraseña temporal); ningún refresh lo extiende.';

COMMENT ON COLUMN sessions.last_used_at IS 'Último login o refresh; con 7 días sin uso el refresh deja de renovarla.';

COMMENT ON COLUMN sessions.revoked_at IS 'Cuándo se cerró la sesión; desde entonces su access token y su refresh token se rechazan.';

COMMENT ON COLUMN sessions.revoked_reason IS 'Por qué se cerró: LOGOUT, REUSE (se presentó un refresh ya rotado), PASSWORD_CHANGE (otra sesión cambió la contraseña) o INVALIDATED (la cuenta cambió o se desactivó, o la sesión venció).';

COMMENT ON COLUMN sessions.created_date IS 'Auditoría: cuándo se abrió la sesión.';

COMMENT ON COLUMN sessions.last_modified_date IS 'Auditoría: última modificación de la fila.';

COMMENT ON COLUMN sessions.created_by_id IS 'Auditoría: usuario que creó la fila (vacío, se abre sin sesión previa).';

COMMENT ON COLUMN sessions.last_modified_by_id IS 'Auditoría: usuario que modificó la fila por última vez.';

CREATE SEQUENCE sessions_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE stays (
    id bigint NOT NULL,
    guest_id bigint NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    agreed_amount numeric(10,2) NOT NULL,
    version bigint DEFAULT 0 NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    created_by_id bigint,
    last_modified_by_id bigint,
    environment_id bigint,
    CONSTRAINT ck_stays_agreed_amount CHECK ((agreed_amount > (0)::numeric)),
    CONSTRAINT ck_stays_dates CHECK ((end_date >= start_date))
);

COMMENT ON TABLE stays IS 'Estadías de los huéspedes temporales (RN-13): cada visita es una fila con sus fechas y su monto acordado. No se borran.';

COMMENT ON COLUMN stays.id IS 'Identificador de la estadía (BIGINT de la secuencia stays_seq); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN stays.guest_id IS 'Huésped temporal al que pertenece la estadía.';

COMMENT ON COLUMN stays.start_date IS 'Primer día de la estadía, inclusive.';

COMMENT ON COLUMN stays.end_date IS 'Último día de la estadía, inclusive; no es anterior a start_date.';

COMMENT ON COLUMN stays.agreed_amount IS 'Monto acordado en soles, mayor que 0.';

COMMENT ON COLUMN stays.version IS 'Versión para el bloqueo optimista: dos ediciones simultáneas no se pisan.';

COMMENT ON COLUMN stays.created_date IS 'Fecha de creación del registro (auditoría).';

COMMENT ON COLUMN stays.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN stays.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN stays.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN stays.environment_id IS 'Ambiente que ocupa la estadía durante sus fechas (RN-20, TAR-237); nulo para una estadía registrada antes («Sin ambiente»).';

CREATE SEQUENCE stays_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

CREATE TABLE users (
    id bigint NOT NULL,
    email character varying(200),
    document_type character varying(20) NOT NULL,
    document_number character varying(20) NOT NULL,
    password_hash character varying(255) NOT NULL,
    role character varying(20) NOT NULL,
    guest_id bigint,
    must_change_password boolean DEFAULT true NOT NULL,
    failed_login_attempts smallint DEFAULT 0 NOT NULL,
    locked_until timestamp with time zone,
    reset_token_hash character varying(255),
    reset_token_expires_at timestamp with time zone,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_date timestamp with time zone DEFAULT now() NOT NULL,
    last_modified_date timestamp with time zone DEFAULT now() NOT NULL,
    password_changed_at timestamp with time zone,
    created_by_id bigint,
    last_modified_by_id bigint,
    name character varying(100),
    security_version bigint DEFAULT 0 NOT NULL,
    CONSTRAINT ck_users_security_version CHECK ((security_version >= 0))
);

COMMENT ON TABLE users IS 'Cuentas de acceso al sistema (ADMIN y huéspedes).';

COMMENT ON COLUMN users.id IS 'Identificador del usuario (BIGINT de la secuencia users_seq); el BFF no lo muestra tal cual.';

COMMENT ON COLUMN users.email IS 'Correo de la cuenta, opcional (D-33, RN-19); si existe, único sin distinguir mayúsculas.';

COMMENT ON COLUMN users.document_type IS 'Tipo de documento con el que se inicia sesión (el login es por documento, RF-08).';

COMMENT ON COLUMN users.document_number IS 'Número del documento con el que se inicia sesión; único junto con document_type.';

COMMENT ON COLUMN users.password_hash IS 'Hash bcrypt de la contraseña; la contraseña en claro nunca se guarda.';

COMMENT ON COLUMN users.role IS 'ADMIN o GUEST.';

COMMENT ON COLUMN users.guest_id IS 'Huésped asociado; NULL para ADMIN.';

COMMENT ON COLUMN users.must_change_password IS 'TRUE hasta que el usuario cambia la contraseña inicial (RF-12).';

COMMENT ON COLUMN users.failed_login_attempts IS 'Intentos fallidos consecutivos. Reservado con locked_until para el bloqueo temporal (aún sin uso en el código).';

COMMENT ON COLUMN users.locked_until IS 'Hasta cuándo está bloqueada la cuenta tras demasiados intentos fallidos (aún sin uso en el código).';

COMMENT ON COLUMN users.reset_token_hash IS 'Hash del token de recuperación de contraseña (aún sin uso); el token en claro nunca se guarda.';

COMMENT ON COLUMN users.reset_token_expires_at IS 'Vencimiento del token de recuperación de contraseña (aún sin uso).';

COMMENT ON COLUMN users.status IS 'ACTIVE o INACTIVE; una cuenta INACTIVE no puede iniciar sesión.';

COMMENT ON COLUMN users.created_date IS 'Fecha de creación del registro (auditoría).';

COMMENT ON COLUMN users.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';

COMMENT ON COLUMN users.password_changed_at IS 'Instante del último cambio o restablecimiento de contraseña; un token emitido antes ya no es válido (TAR-125). NULL si nunca se cambió.';

COMMENT ON COLUMN users.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN users.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

COMMENT ON COLUMN users.name IS 'Nombre completo de un administrador, que escribe al activar su cuenta (A0.5, TAR-127); nulo mientras no la activa y en las cuentas de huésped, cuyo nombre vive en guests.';

COMMENT ON COLUMN users.security_version IS 'Versión de seguridad de la cuenta; un JWT solo es válido si conserva esta versión. Se incrementa al cambiar contraseña, rol o desactivar la cuenta.';

CREATE SEQUENCE users_seq
    START WITH 1
    INCREMENT BY 50
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER TABLE ONLY access_grants
    ADD CONSTRAINT access_grants_pkey PRIMARY KEY (id);

ALTER TABLE ONLY contracts
    ADD CONSTRAINT contracts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY environments
    ADD CONSTRAINT environments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY expenses
    ADD CONSTRAINT expenses_pkey PRIMARY KEY (id);

ALTER TABLE ONLY fines
    ADD CONSTRAINT fines_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_consents
    ADD CONSTRAINT guest_consents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_document_actions
    ADD CONSTRAINT guest_document_actions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_document_deletions
    ADD CONSTRAINT guest_document_deletions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_document_purges
    ADD CONSTRAINT guest_document_purges_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT guest_documents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY guest_email_verifications
    ADD CONSTRAINT guest_email_verifications_pkey PRIMARY KEY (guest_id);

ALTER TABLE ONLY guests
    ADD CONSTRAINT guests_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payment_proofs
    ADD CONSTRAINT payment_proofs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payment_reviews
    ADD CONSTRAINT payment_reviews_pkey PRIMARY KEY (id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_pkey PRIMARY KEY (id);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT refunds_pkey PRIMARY KEY (id);

ALTER TABLE ONLY sessions
    ADD CONSTRAINT sessions_pkey PRIMARY KEY (id);

ALTER TABLE ONLY stays
    ADD CONSTRAINT stays_pkey PRIMARY KEY (id);

ALTER TABLE ONLY access_grants
    ADD CONSTRAINT uq_access_grants_token_hash UNIQUE (token_hash);

ALTER TABLE ONLY environments
    ADD CONSTRAINT uq_environments_code UNIQUE (code);

ALTER TABLE ONLY guest_consents
    ADD CONSTRAINT uq_guest_consents_guest_version UNIQUE (guest_id, consent_version);

COMMENT ON CONSTRAINT uq_guest_consents_guest_version ON guest_consents IS 'Un consentimiento por huésped y versión del texto (D-49): aceptar de nuevo la misma versión no deja otro registro.';

ALTER TABLE ONLY guest_document_actions
    ADD CONSTRAINT uq_guest_document_actions_document UNIQUE (document_id);

ALTER TABLE ONLY guest_document_deletions
    ADD CONSTRAINT uq_guest_document_deletions_document UNIQUE (document_id);

ALTER TABLE ONLY guest_document_purges
    ADD CONSTRAINT uq_guest_document_purges_storage_key UNIQUE (storage_key);

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT uq_guest_documents_storage_key UNIQUE (storage_key);

ALTER TABLE ONLY guests
    ADD CONSTRAINT uq_guests_document UNIQUE (document_type, document_number);

ALTER TABLE ONLY payment_proofs
    ADD CONSTRAINT uq_payment_proofs_file_key UNIQUE (file_key);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash);

ALTER TABLE ONLY sessions
    ADD CONSTRAINT uq_sessions_sid_hash UNIQUE (sid_hash);

ALTER TABLE ONLY users
    ADD CONSTRAINT uq_users_document UNIQUE (document_type, document_number);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_email_key UNIQUE (email);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

CREATE INDEX ix_contracts_created_date_id ON contracts USING btree (created_date DESC, id DESC);

CREATE INDEX ix_contracts_environment ON contracts USING btree (environment_id);

CREATE INDEX ix_contracts_guest ON contracts USING btree (guest_id);

CREATE INDEX ix_expenses_month_created_date_id ON expenses USING btree (month, created_date, id);

CREATE INDEX ix_fines_month ON fines USING btree (month);

CREATE INDEX ix_guest_consents_guest ON guest_consents USING btree (guest_id, accepted_at DESC, id DESC);

CREATE INDEX ix_guest_document_actions_guest ON guest_document_actions USING btree (guest_id, created_date DESC, id DESC);

CREATE INDEX ix_guest_document_deletions_created ON guest_document_deletions USING btree (created_date, id);

CREATE INDEX ix_guest_documents_guest ON guest_documents USING btree (guest_id, created_date, id);

CREATE INDEX ix_payment_proofs_payment ON payment_proofs USING btree (payment_id, id);

CREATE INDEX ix_payment_reviews_payment ON payment_reviews USING btree (payment_id, reviewed_at, id);

CREATE INDEX ix_payments_contract_period ON payments USING btree (contract_id, period);

CREATE INDEX ix_payments_guest_status_period ON payments USING btree (guest_id, status, period);

CREATE INDEX ix_payments_history ON payments USING btree (guest_id, created_date DESC, id DESC);

CREATE INDEX ix_payments_period_status ON payments USING btree (period, status);

CREATE INDEX ix_payments_queue ON payments USING btree (status, submitted_at, id);

CREATE INDEX ix_payments_stay ON payments USING btree (stay_id);

CREATE INDEX ix_payments_submitted ON payments USING btree (submitted_at, id);

CREATE INDEX ix_refresh_tokens_session ON refresh_tokens USING btree (session_id);

CREATE INDEX ix_refunds_from_payment ON refunds USING btree (from_payment_id);

CREATE INDEX ix_refunds_guest ON refunds USING btree (guest_id);

CREATE INDEX ix_refunds_refunded_on ON refunds USING btree (refunded_on, created_date, id);

CREATE INDEX ix_sessions_user ON sessions USING btree (user_id);

CREATE INDEX ix_stays_end_date_start_date ON stays USING btree (end_date, start_date);

CREATE INDEX ix_stays_environment_dates ON stays USING btree (environment_id, start_date, end_date) WHERE (environment_id IS NOT NULL);

CREATE INDEX ix_stays_guest ON stays USING btree (guest_id, start_date DESC, id DESC);

CREATE INDEX ix_stays_start_date ON stays USING btree (start_date);

CREATE UNIQUE INDEX uq_access_grants_live ON access_grants USING btree (guest_id) WHERE ((used_at IS NULL) AND (revoked_at IS NULL));

CREATE UNIQUE INDEX uq_payments_live_period ON payments USING btree (contract_id, period) WHERE (status IN ('PENDING', 'APPROVED', 'REJECTED'));

CREATE UNIQUE INDEX uq_payments_live_stay ON payments USING btree (stay_id) WHERE (status IN ('PENDING', 'APPROVED', 'REJECTED'));

CREATE UNIQUE INDEX uq_users_email_lower ON users USING btree (lower((email)::text));

CREATE UNIQUE INDEX uq_users_guest ON users USING btree (guest_id) WHERE (guest_id IS NOT NULL);

CREATE UNIQUE INDEX uq_users_reset_token_hash ON users USING btree (reset_token_hash) WHERE (reset_token_hash IS NOT NULL);

CREATE TRIGGER trg_guest_consents_immutable BEFORE DELETE OR UPDATE ON guest_consents FOR EACH ROW EXECUTE FUNCTION reject_onboarding_record_change();

CREATE TRIGGER trg_guest_document_actions_immutable BEFORE DELETE OR UPDATE ON guest_document_actions FOR EACH ROW EXECUTE FUNCTION reject_onboarding_record_change();

CREATE TRIGGER trg_guest_documents_delete_with_evidence BEFORE DELETE ON guest_documents FOR EACH ROW EXECUTE FUNCTION require_guest_document_action();

CREATE TRIGGER trg_guest_documents_immutable BEFORE UPDATE ON guest_documents FOR EACH ROW EXECUTE FUNCTION reject_onboarding_record_change();

ALTER TABLE ONLY access_grants
    ADD CONSTRAINT access_grants_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY access_grants
    ADD CONSTRAINT access_grants_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY contracts
    ADD CONSTRAINT contracts_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY contracts
    ADD CONSTRAINT contracts_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY environments
    ADD CONSTRAINT environments_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY environments
    ADD CONSTRAINT environments_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY expenses
    ADD CONSTRAINT expenses_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY expenses
    ADD CONSTRAINT expenses_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY fines
    ADD CONSTRAINT fines_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY fines
    ADD CONSTRAINT fines_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY access_grants
    ADD CONSTRAINT fk_access_grants_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY contracts
    ADD CONSTRAINT fk_contracts_environment FOREIGN KEY (environment_id) REFERENCES environments(id);

ALTER TABLE ONLY contracts
    ADD CONSTRAINT fk_contracts_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY expenses
    ADD CONSTRAINT fk_expenses_environment FOREIGN KEY (environment_id) REFERENCES environments(id);

ALTER TABLE ONLY fines
    ADD CONSTRAINT fk_fines_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY guest_consents
    ADD CONSTRAINT fk_guest_consents_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY guest_document_actions
    ADD CONSTRAINT fk_guest_document_actions_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT fk_guest_documents_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY guests
    ADD CONSTRAINT fk_guests_environment FOREIGN KEY (current_environment_id) REFERENCES environments(id);

ALTER TABLE ONLY payment_proofs
    ADD CONSTRAINT fk_payment_proofs_payment FOREIGN KEY (payment_id) REFERENCES payments(id);

ALTER TABLE ONLY payment_reviews
    ADD CONSTRAINT fk_payment_reviews_payment FOREIGN KEY (payment_id) REFERENCES payments(id);

ALTER TABLE ONLY payment_reviews
    ADD CONSTRAINT fk_payment_reviews_reviewed_by FOREIGN KEY (reviewed_by_id) REFERENCES users(id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT fk_payments_contract FOREIGN KEY (contract_id) REFERENCES contracts(id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT fk_payments_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT fk_payments_reviewed_by FOREIGN KEY (reviewed_by_id) REFERENCES users(id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT fk_payments_stay FOREIGN KEY (stay_id) REFERENCES stays(id);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT fk_refunds_from_payment FOREIGN KEY (from_payment_id) REFERENCES payments(id);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT fk_refunds_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY stays
    ADD CONSTRAINT fk_stays_environment FOREIGN KEY (environment_id) REFERENCES environments(id);

ALTER TABLE ONLY stays
    ADD CONSTRAINT fk_stays_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY users
    ADD CONSTRAINT fk_users_guest FOREIGN KEY (guest_id) REFERENCES guests(id);

ALTER TABLE ONLY guest_consents
    ADD CONSTRAINT guest_consents_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_consents
    ADD CONSTRAINT guest_consents_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_document_actions
    ADD CONSTRAINT guest_document_actions_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_document_actions
    ADD CONSTRAINT guest_document_actions_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_document_deletions
    ADD CONSTRAINT guest_document_deletions_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_document_deletions
    ADD CONSTRAINT guest_document_deletions_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_document_purges
    ADD CONSTRAINT guest_document_purges_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_document_purges
    ADD CONSTRAINT guest_document_purges_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT guest_documents_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_documents
    ADD CONSTRAINT guest_documents_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY guest_email_verifications
    ADD CONSTRAINT guest_email_verifications_guest_id_fkey FOREIGN KEY (guest_id) REFERENCES guests(id) ON DELETE CASCADE;

ALTER TABLE ONLY guests
    ADD CONSTRAINT guests_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY guests
    ADD CONSTRAINT guests_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY payment_proofs
    ADD CONSTRAINT payment_proofs_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY payment_proofs
    ADD CONSTRAINT payment_proofs_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY payment_reviews
    ADD CONSTRAINT payment_reviews_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY payment_reviews
    ADD CONSTRAINT payment_reviews_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT payments_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY payments
    ADD CONSTRAINT payments_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY refresh_tokens
    ADD CONSTRAINT refresh_tokens_session_id_fkey FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE;

ALTER TABLE ONLY refunds
    ADD CONSTRAINT refunds_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY refunds
    ADD CONSTRAINT refunds_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY sessions
    ADD CONSTRAINT sessions_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY sessions
    ADD CONSTRAINT sessions_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY sessions
    ADD CONSTRAINT sessions_user_id_fkey FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY stays
    ADD CONSTRAINT stays_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY stays
    ADD CONSTRAINT stays_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_created_by_id_fkey FOREIGN KEY (created_by_id) REFERENCES users(id);

ALTER TABLE ONLY users
    ADD CONSTRAINT users_last_modified_by_id_fkey FOREIGN KEY (last_modified_by_id) REFERENCES users(id);

SET check_function_bodies = true;

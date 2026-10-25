-- TAR-187 (RN-37): el administrador borra o reemplaza un documento del huésped solo por una solicitud ARCO del
-- titular o por un error de carga, y cada acción deja evidencia de quién, cuándo y por qué.
--
-- Qué decide la base y qué la aplicación:
--  * La evidencia (guest_document_actions) solo se inserta: el mismo disparador de V16 rechaza UPDATE y DELETE.
--    Nunca guarda el nombre, la clave ni el contenido del archivo; solo su tipo y su tamaño.
--  * Un documento sigue sin modificarse, pero ahora puede borrarse, y solo si su evidencia ya está escrita: un
--    disparador rechaza el DELETE de una fila de guest_documents sin una acción de ese documento. La aplicación
--    inserta la evidencia y borra la fila en la misma transacción, con la fila del huésped y la del documento
--    bloqueadas (FOR UPDATE, en ese orden).
--  * Un documento recibe a lo sumo una acción (uq_guest_document_actions_document): de dos acciones simultáneas
--    sobre él, solo una queda.
--  * guest_document_purges guarda las claves de los objetos de R2 que ya no tienen fila (el archivo antiguo de un
--    reemplazo) hasta que se borran del bucket; la aplicación reintenta los pendientes.
--  * guest_document_deletions guarda la intención de un borrado (quién, cuándo, por qué) en su propia transacción
--    antes de borrar el objeto de R2: si la transacción que borra la fila y escribe la evidencia falla después, la
--    intención sigue ahí y el reintento del administrador o la purga de la aplicación terminan el borrado y
--    escriben la evidencia con ese quién y ese cuándo. Al consolidarse en evidencia, la intención se borra.
--  * TRUNCATE no activa los disparadores de fila y queda solo para las pruebas.

DROP TRIGGER trg_guest_documents_immutable ON guest_documents;

CREATE TRIGGER trg_guest_documents_immutable BEFORE UPDATE ON guest_documents
    FOR EACH ROW EXECUTE FUNCTION reject_onboarding_record_change();

CREATE SEQUENCE guest_document_actions_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE guest_document_actions (
    id                        BIGINT PRIMARY KEY,
    guest_id                  BIGINT NOT NULL,
    document_id               BIGINT NOT NULL,
    replacement_document_id   BIGINT NULL,
    action                    VARCHAR(20) NOT NULL,
    reason                    VARCHAR(20) NOT NULL,
    requested_at              DATE NULL,
    request_channel           VARCHAR(20) NULL,
    note                      VARCHAR(500) NULL,
    content_type              VARCHAR(100) NOT NULL,
    size_bytes                BIGINT NOT NULL,
    created_date              TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date        TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id             BIGINT NOT NULL REFERENCES users (id),
    last_modified_by_id       BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_guest_document_actions_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT uq_guest_document_actions_document UNIQUE (document_id),
    CONSTRAINT ck_guest_document_actions_action CHECK (action IN ('DELETE', 'REPLACE')),
    CONSTRAINT ck_guest_document_actions_replacement CHECK ((action = 'REPLACE') = (replacement_document_id IS NOT NULL)),
    CONSTRAINT ck_guest_document_actions_reason CHECK (
        (reason = 'ARCO_REQUEST' AND requested_at IS NOT NULL AND request_channel IS NOT NULL)
        OR (reason = 'UPLOAD_ERROR' AND requested_at IS NULL AND request_channel IS NULL)),
    CONSTRAINT ck_guest_document_actions_channel CHECK (request_channel IN ('WHATSAPP', 'EMAIL', 'IN_PERSON', 'OTHER')),
    CONSTRAINT ck_guest_document_actions_note CHECK (note IS NULL OR btrim(note) <> ''),
    CONSTRAINT ck_guest_document_actions_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT ck_guest_document_actions_size_bytes CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);

CREATE INDEX ix_guest_document_actions_guest ON guest_document_actions (guest_id, created_date DESC, id DESC);
CREATE TRIGGER trg_guest_document_actions_immutable BEFORE UPDATE OR DELETE ON guest_document_actions
    FOR EACH ROW EXECUTE FUNCTION reject_onboarding_record_change();

CREATE FUNCTION require_guest_document_action() RETURNS trigger AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM guest_document_actions WHERE document_id = OLD.id AND guest_id = OLD.guest_id) THEN
        RAISE EXCEPTION 'Un documento del huésped solo se borra con su evidencia (RN-37)';
    END IF;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_guest_documents_delete_with_evidence BEFORE DELETE ON guest_documents
    FOR EACH ROW EXECUTE FUNCTION require_guest_document_action();

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

CREATE SEQUENCE guest_document_purges_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE guest_document_purges (
    id                    BIGINT PRIMARY KEY,
    storage_key           VARCHAR(255) NOT NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT uq_guest_document_purges_storage_key UNIQUE (storage_key)
);

COMMENT ON TABLE guest_document_purges IS 'Objetos de R2 de documentos del huésped que ya no tienen fila (el archivo antiguo de un reemplazo, RN-37) y falta borrar del bucket; la aplicación los reintenta hasta lograrlo y entonces borra la fila.';
COMMENT ON COLUMN guest_document_purges.id IS 'Identificador del pendiente (BIGINT de la secuencia guest_document_purges_seq); interno, el BFF no lo muestra.';
COMMENT ON COLUMN guest_document_purges.storage_key IS 'Clave aleatoria del objeto en el bucket R2, bajo guest-documents/; nunca la URL firmada. Nunca se registra en logs.';
COMMENT ON COLUMN guest_document_purges.created_date IS 'Fecha de creación del registro (auditoría): cuándo quedó pendiente el borrado.';
COMMENT ON COLUMN guest_document_purges.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN guest_document_purges.created_by_id IS 'Usuario con sesión que dejó pendiente el borrado (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN guest_document_purges.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

CREATE SEQUENCE guest_document_deletions_seq START WITH 1 INCREMENT BY 50;

-- Sin clave foránea a guests: la intención se inserta en una transacción propia mientras la del borrado tiene la
-- fila del huésped bloqueada (FOR UPDATE), y la comprobación de la clave foránea esperaría ese bloqueo.
CREATE TABLE guest_document_deletions (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    document_id           BIGINT NOT NULL,
    reason                VARCHAR(20) NOT NULL,
    requested_at          DATE NULL,
    request_channel       VARCHAR(20) NULL,
    note                  VARCHAR(500) NULL,
    content_type          VARCHAR(100) NOT NULL,
    size_bytes            BIGINT NOT NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NOT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT uq_guest_document_deletions_document UNIQUE (document_id),
    CONSTRAINT ck_guest_document_deletions_reason CHECK (
        (reason = 'ARCO_REQUEST' AND requested_at IS NOT NULL AND request_channel IS NOT NULL)
        OR (reason = 'UPLOAD_ERROR' AND requested_at IS NULL AND request_channel IS NULL)),
    CONSTRAINT ck_guest_document_deletions_channel CHECK (request_channel IN ('WHATSAPP', 'EMAIL', 'IN_PERSON', 'OTHER')),
    CONSTRAINT ck_guest_document_deletions_note CHECK (note IS NULL OR btrim(note) <> ''),
    CONSTRAINT ck_guest_document_deletions_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT ck_guest_document_deletions_size_bytes CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);

CREATE INDEX ix_guest_document_deletions_created ON guest_document_deletions (created_date, id);

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

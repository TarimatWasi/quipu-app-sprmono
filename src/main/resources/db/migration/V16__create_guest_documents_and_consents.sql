-- TAR-137 (RF-14, RF-20, RN-14, RN-33, RN-37; CU-11, CU-25, CU-27): lo que el huésped deja en su onboarding además
-- de sus datos personales. Los documentos son opcionales (a lo sumo 5, JPG, PNG o PDF de hasta 5 MB) y solo el
-- administrador los abre. El consentimiento es lo que cierra el onboarding: queda la versión aceptada, cuándo y el
-- SHA-256 del texto que se mostró.
--
-- Qué decide la base y qué la aplicación:
--  * El máximo de 5 documentos lo cuenta la aplicación con la fila del huésped bloqueada (FOR UPDATE), así dos
--    subidas simultáneas no pasan las dos del límite.
--  * Documentos y consentimientos no se modifican ni se borran: la aplicación solo los inserta.

CREATE SEQUENCE guest_documents_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE guest_documents (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    storage_key           VARCHAR(255) NOT NULL,
    original_name         VARCHAR(255) NOT NULL,
    content_type          VARCHAR(100) NOT NULL,
    size_bytes            BIGINT NOT NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_guest_documents_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT uq_guest_documents_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_guest_documents_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT ck_guest_documents_size_bytes CHECK (size_bytes > 0 AND size_bytes <= 5242880)
);

CREATE INDEX ix_guest_documents_guest ON guest_documents (guest_id, created_date, id);

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

CREATE SEQUENCE guest_consents_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE guest_consents (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    consent_version       VARCHAR(50) NOT NULL,
    accepted_at           TIMESTAMPTZ NOT NULL,
    text_sha256           VARCHAR(64) NOT NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_guest_consents_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT ck_guest_consents_text_sha256 CHECK (text_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_guest_consents_guest ON guest_consents (guest_id, accepted_at DESC, id DESC);

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

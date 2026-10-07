-- TAR-34 (RF-02): huéspedes. El administrador registra solo el documento y el tipo de huésped (RN-31); el resto lo
-- completa el propio huésped en su onboarding (RF-20), por eso el nombre y el teléfono admiten NULL hasta entonces.
-- Un huésped se desactiva, nunca se borra (RN-12).

CREATE TABLE guests (
    id                              UUID PRIMARY KEY,
    full_name                       VARCHAR(200) NULL,
    document_type                   VARCHAR(20) NOT NULL,
    document_number                 VARCHAR(20) NOT NULL,
    email                           VARCHAR(200) NULL,
    phone                           VARCHAR(30) NULL,
    guest_type                      VARCHAR(20) NOT NULL,
    current_environment_id          UUID NULL,
    stay_start_date                 DATE NULL,
    stay_end_date                   DATE NULL,
    agreed_amount                   NUMERIC(10, 2) NULL,
    emergency_contact_name          VARCHAR(200) NULL,
    emergency_contact_relationship  VARCHAR(50) NULL,
    emergency_contact_phone         VARCHAR(30) NULL,
    status                          VARCHAR(20) NOT NULL DEFAULT 'PENDING_ACTIVATION',
    status_before_inactive          VARCHAR(20) NULL,
    personal_data_completed_at      TIMESTAMPTZ NULL,
    documents_step_completed_at     TIMESTAMPTZ NULL,
    version                         BIGINT NOT NULL DEFAULT 0,
    created_date                    TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date              TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by                      VARCHAR(200) NULL,
    last_modified_by                VARCHAR(200) NULL,
    CONSTRAINT uq_guests_document UNIQUE (document_type, document_number),
    CONSTRAINT fk_guests_environment FOREIGN KEY (current_environment_id) REFERENCES environments (id),
    CONSTRAINT ck_guests_document_type CHECK (document_type IN ('DNI', 'CE', 'PASSPORT')),
    CONSTRAINT ck_guests_type CHECK (guest_type IN ('CONTRACT', 'TEMPORARY')),
    CONSTRAINT ck_guests_status CHECK (status IN ('PENDING_ACTIVATION', 'ONBOARDING', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_guests_status_before_inactive CHECK (
        status_before_inactive IS NULL OR status_before_inactive IN ('PENDING_ACTIVATION', 'ONBOARDING', 'ACTIVE')),
    CONSTRAINT ck_guests_stay_dates CHECK (stay_end_date IS NULL OR stay_start_date IS NULL OR stay_end_date >= stay_start_date),
    CONSTRAINT ck_guests_agreed_amount CHECK (agreed_amount IS NULL OR agreed_amount > 0)
);

-- El correo del huésped es opcional (RN-19) y, si existe, único sin distinguir mayúsculas como el de la cuenta.
CREATE UNIQUE INDEX uq_guests_email_lower ON guests (lower(email)) WHERE email IS NOT NULL;

-- Una cuenta pertenece a un solo huésped y un huésped tiene una sola cuenta (RF-11).
ALTER TABLE users ADD CONSTRAINT fk_users_guest FOREIGN KEY (guest_id) REFERENCES guests (id);
CREATE UNIQUE INDEX uq_users_guest ON users (guest_id) WHERE guest_id IS NOT NULL;

COMMENT ON TABLE guests IS 'Huéspedes con contrato y temporales (RF-02). Se desactivan, nunca se borran (RN-12).';
COMMENT ON COLUMN guests.id IS 'Identificador del huésped (UUID generado por la aplicación).';
COMMENT ON COLUMN guests.full_name IS 'Nombre completo; NULL hasta que el huésped completa sus datos personales en el onboarding (RN-33).';
COMMENT ON COLUMN guests.document_type IS 'DNI, CE o PASSPORT; con document_number es la clave de la persona (RN-18).';
COMMENT ON COLUMN guests.document_number IS 'Número de documento; único junto con document_type (RN-18).';
COMMENT ON COLUMN guests.email IS 'Correo de contacto, opcional (RN-19); único sin distinguir mayúsculas.';
COMMENT ON COLUMN guests.phone IS 'Teléfono de contacto; el administrador puede darlo al registrar y el huésped lo completa en el onboarding (RN-31, RN-33).';
COMMENT ON COLUMN guests.guest_type IS 'CONTRACT (con contrato) o TEMPORARY (estadía corta).';
COMMENT ON COLUMN guests.current_environment_id IS 'Ambiente que ocupa hoy, si lo hay.';
COMMENT ON COLUMN guests.stay_start_date IS 'Inicio de la estadía; solo para huéspedes temporales.';
COMMENT ON COLUMN guests.stay_end_date IS 'Fin de la estadía; solo para huéspedes temporales.';
COMMENT ON COLUMN guests.agreed_amount IS 'Monto acordado de la estadía, mayor que 0; solo para huéspedes temporales.';
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
COMMENT ON COLUMN guests.created_by IS 'Quién creó el registro (auditoría).';
COMMENT ON COLUMN guests.last_modified_by IS 'Quién modificó el registro por última vez (auditoría).';

-- Baseline del esquema (TAR-233, 2026-10-28): reúne en una sola migración las V1 a V23 anteriores y crea
-- directamente el esquema final, sin pasos intermedios. Se aplica sobre bases vacías (cuenta nueva de Neon);
-- ninguna base con datos la había aplicado. Desde el primer despliegue es inmutable: todo cambio va en una V2
-- nueva (BE-SPR-DAT-05).
--
-- Convenciones de todas las tablas:
--  * Los ids son BIGINT de una secuencia por tabla que avanza de 50 en 50 (Hibernate los pide por bloques); el
--    BFF los muestra enmascarados o no los muestra.
--  * Auditoría de Spring Data: created_date, last_modified_date y created_by_id / last_modified_by_id, clave
--    foránea hacia users (NULL si no hubo un usuario con sesión).
--  * version es el bloqueo optimista de las tablas que se editan.
--
-- No crea usuarios ni datos: el primer ADMIN lo crea AdminBootstrap al arrancar.

-- =====================================================================================================
-- Cuentas (RF-08, RF-11, RF-12, RF-16)
-- =====================================================================================================

CREATE SEQUENCE users_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE users (
    id                       BIGINT PRIMARY KEY,
    email                    VARCHAR(200) NULL UNIQUE,
    document_type            VARCHAR(20) NOT NULL,
    document_number          VARCHAR(20) NOT NULL,
    password_hash            VARCHAR(255) NOT NULL,
    role                     VARCHAR(20) NOT NULL,
    guest_id                 BIGINT NULL,
    must_change_password     BOOLEAN NOT NULL DEFAULT TRUE,
    failed_login_attempts    SMALLINT NOT NULL DEFAULT 0,
    locked_until             TIMESTAMPTZ NULL,
    reset_token_hash         VARCHAR(255) NULL,
    reset_token_expires_at   TIMESTAMPTZ NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    password_changed_at      TIMESTAMPTZ NULL,
    created_by_id            BIGINT NULL REFERENCES users (id),
    last_modified_by_id      BIGINT NULL REFERENCES users (id),
    CONSTRAINT uq_users_document UNIQUE (document_type, document_number)
);

-- RF-16 busca la cuenta por correo sin distinguir mayúsculas: el correo es único también así.
CREATE UNIQUE INDEX uq_users_email_lower ON users (lower(email));
-- El restablecimiento busca la cuenta por el hash del código en un endpoint público; parcial porque la mayoría
-- de las filas no tiene código.
CREATE UNIQUE INDEX uq_users_reset_token_hash ON users (reset_token_hash) WHERE reset_token_hash IS NOT NULL;
-- La clave foránea fk_users_guest y el índice uq_users_guest se crean después de guests.

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

-- =====================================================================================================
-- Ambientes (RF-01): se desactivan, nunca se borran (RN-12). La ocupación se deriva de los contratos (RN-20).
-- =====================================================================================================

CREATE SEQUENCE environments_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE environments (
    id                       BIGINT PRIMARY KEY,
    code                     VARCHAR(50) NOT NULL,
    type                     VARCHAR(20) NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version                  BIGINT NOT NULL DEFAULT 0,
    created_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id            BIGINT NULL REFERENCES users (id),
    last_modified_by_id      BIGINT NULL REFERENCES users (id),
    CONSTRAINT uq_environments_code UNIQUE (code),
    CONSTRAINT ck_environments_type CHECK (type IN ('ROOM', 'CABIN')),
    CONSTRAINT ck_environments_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
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

-- =====================================================================================================
-- Egresos (RF-07): junto con las multas, el único borrado físico permitido (RN-24).
-- =====================================================================================================

CREATE SEQUENCE expenses_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE expenses (
    id                       BIGINT PRIMARY KEY,
    type                     VARCHAR(20) NOT NULL,
    environment_id           BIGINT NULL,
    amount                   NUMERIC(10, 2) NOT NULL,
    month                    DATE NOT NULL,
    description              TEXT NULL,
    created_date             TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date       TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id            BIGINT NULL REFERENCES users (id),
    last_modified_by_id      BIGINT NULL REFERENCES users (id),
    version                  BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_expenses_environment FOREIGN KEY (environment_id) REFERENCES environments (id),
    CONSTRAINT ck_expenses_type CHECK (type IN ('UTILITIES', 'MAINTENANCE', 'CLEANING_SUPPLIES', 'TAXES_FEES')),
    CONSTRAINT ck_expenses_amount CHECK (amount > 0),
    CONSTRAINT ck_expenses_month CHECK (month = date_trunc('month', month)::date)
);

-- El listado se lee por último índice, por mes y en el orden (created_date, id) (BE-SPR-WEB-08).
CREATE INDEX ix_expenses_month_created_date_id ON expenses (month, created_date, id);

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

-- =====================================================================================================
-- Huéspedes (RF-02): el administrador registra el documento y el tipo (RN-31); el resto lo completa el huésped
-- en su onboarding (RF-20). Se desactivan, nunca se borran (RN-12).
-- =====================================================================================================

CREATE SEQUENCE guests_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE guests (
    id                              BIGINT PRIMARY KEY,
    full_name                       VARCHAR(200) NULL,
    document_type                   VARCHAR(20) NOT NULL,
    document_number                 VARCHAR(20) NOT NULL,
    email                           VARCHAR(200) NULL,
    phone                           VARCHAR(30) NULL,
    guest_type                      VARCHAR(20) NOT NULL,
    current_environment_id          BIGINT NULL,
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
    created_by_id                   BIGINT NULL REFERENCES users (id),
    last_modified_by_id             BIGINT NULL REFERENCES users (id),
    CONSTRAINT uq_guests_document UNIQUE (document_type, document_number),
    CONSTRAINT fk_guests_environment FOREIGN KEY (current_environment_id) REFERENCES environments (id),
    CONSTRAINT ck_guests_document_type CHECK (document_type IN ('DNI', 'CE', 'PASSPORT')),
    CONSTRAINT ck_guests_type CHECK (guest_type IN ('CONTRACT', 'TEMPORARY')),
    CONSTRAINT ck_guests_status CHECK (status IN ('PENDING_ACTIVATION', 'ONBOARDING', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_guests_status_before_inactive CHECK (
        status_before_inactive IS NULL OR status_before_inactive IN ('PENDING_ACTIVATION', 'ONBOARDING', 'ACTIVE'))
);

-- El correo del huésped es opcional (RN-19) y, si existe, único sin distinguir mayúsculas como el de la cuenta.
CREATE UNIQUE INDEX uq_guests_email_lower ON guests (lower(email)) WHERE email IS NOT NULL;

-- Una cuenta pertenece a un solo huésped y un huésped tiene una sola cuenta (RF-11).
ALTER TABLE users ADD CONSTRAINT fk_users_guest FOREIGN KEY (guest_id) REFERENCES guests (id);
CREATE UNIQUE INDEX uq_users_guest ON users (guest_id) WHERE guest_id IS NOT NULL;

COMMENT ON TABLE guests IS 'Huéspedes con contrato y temporales (RF-02). Se desactivan, nunca se borran (RN-12).';
COMMENT ON COLUMN guests.id IS 'Identificador del huésped (BIGINT de la secuencia guests_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN guests.full_name IS 'Nombre completo; NULL hasta que el huésped completa sus datos personales en el onboarding (RN-33).';
COMMENT ON COLUMN guests.document_type IS 'DNI, CE o PASSPORT; con document_number es la clave de la persona (RN-18).';
COMMENT ON COLUMN guests.document_number IS 'Número de documento; único junto con document_type (RN-18).';
COMMENT ON COLUMN guests.email IS 'Correo de contacto, opcional (RN-19); único sin distinguir mayúsculas.';
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

-- =====================================================================================================
-- Estadías de los huéspedes temporales (RN-13, RN-17, CU-31): cada visita es una fila; no se borran.
-- =====================================================================================================

CREATE SEQUENCE stays_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE stays (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    start_date            DATE NOT NULL,
    end_date              DATE NOT NULL,
    agreed_amount         NUMERIC(10, 2) NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_stays_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT ck_stays_dates CHECK (end_date >= start_date),
    CONSTRAINT ck_stays_agreed_amount CHECK (agreed_amount > 0)
);

-- El listado de un huésped va de la estadía más reciente a la más antigua.
CREATE INDEX ix_stays_guest ON stays (guest_id, start_date DESC, id DESC);
-- Las estadías que empiezan en un mes (resumen financiero).
CREATE INDEX ix_stays_start_date ON stays (start_date);
-- Las estadías vigentes en un día (panel): end_date >= día es la cota selectiva; start_date <= día se filtra en
-- el índice.
CREATE INDEX ix_stays_end_date_start_date ON stays (end_date, start_date);

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

-- =====================================================================================================
-- Contratos (RF-03, RF-09; RN-01, RN-07, RN-20, RN-21)
--
-- status guarda solo ACTIVE o TERMINATED; vencido se deriva de end_date en cada lectura (America/Lima). No hay
-- índice único parcial por ambiente: dejaría el ambiente ocupado después de vencer el contrato. La ocupación
-- (RN-20) la valida la aplicación con el ambiente bloqueado. Finalizar exige terminated_at (CHECK, sin
-- disparador).
-- =====================================================================================================

CREATE SEQUENCE contracts_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE contracts (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    environment_id        BIGINT NOT NULL,
    start_date            DATE NOT NULL,
    end_date              DATE NULL,
    monthly_amount        NUMERIC(10, 2) NOT NULL,
    payment_day           SMALLINT NOT NULL,
    grace_days            SMALLINT NOT NULL,
    file_key              VARCHAR(255) NOT NULL,
    original_name         VARCHAR(255) NOT NULL,
    size_bytes            BIGINT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    version               BIGINT NOT NULL DEFAULT 0,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    terminated_at         TIMESTAMPTZ NULL,
    CONSTRAINT fk_contracts_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT fk_contracts_environment FOREIGN KEY (environment_id) REFERENCES environments (id),
    CONSTRAINT ck_contracts_dates CHECK (end_date IS NULL OR end_date > start_date),
    CONSTRAINT ck_contracts_monthly_amount CHECK (monthly_amount > 0),
    CONSTRAINT ck_contracts_payment_day CHECK (payment_day BETWEEN 1 AND 31),
    CONSTRAINT ck_contracts_grace_days CHECK (grace_days BETWEEN 0 AND 31),
    CONSTRAINT ck_contracts_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT ck_contracts_status CHECK (status IN ('ACTIVE', 'TERMINATED')),
    CONSTRAINT ck_contracts_terminated_at CHECK ((status = 'TERMINATED') = (terminated_at IS NOT NULL))
);

CREATE INDEX ix_contracts_guest ON contracts (guest_id);
CREATE INDEX ix_contracts_environment ON contracts (environment_id);
-- El listado va del contrato más reciente al más antiguo, leído por último índice.
CREATE INDEX ix_contracts_created_date_id ON contracts (created_date DESC, id DESC);

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

-- =====================================================================================================
-- Pagos (RF-05, RF-06, RF-10; RN-02, RN-03, RN-04, RN-05, RN-15, RN-22, RN-23, RN-28)
--
-- Un pago es un voucher de un período de un contrato o de una estadía. Nace PENDING y se aprueba o rechaza una
-- sola vez. Un solo pago vivo (PENDING o APPROVED) por contrato y período y por estadía: índices únicos
-- parciales, para que dos peticiones simultáneas no dejen dos filas. Los estados por período y la deuda se
-- calculan al consultar.
-- =====================================================================================================

CREATE SEQUENCE payments_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE payments (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    contract_id           BIGINT NULL,
    stay_id               BIGINT NULL,
    period                DATE NULL,
    amount                NUMERIC(10, 2) NOT NULL,
    payment_method        VARCHAR(50) NULL,
    voucher_key           VARCHAR(255) NOT NULL,
    original_name         VARCHAR(255) NOT NULL,
    content_type          VARCHAR(100) NOT NULL,
    size_bytes            BIGINT NOT NULL,
    status                VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    rejection_reason      VARCHAR(500) NULL,
    reviewed_at           TIMESTAMPTZ NULL,
    reviewed_by_id        BIGINT NULL,
    recorded_by           VARCHAR(20) NOT NULL,
    version               BIGINT NOT NULL DEFAULT 0,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_payments_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT fk_payments_contract FOREIGN KEY (contract_id) REFERENCES contracts (id),
    CONSTRAINT fk_payments_stay FOREIGN KEY (stay_id) REFERENCES stays (id),
    CONSTRAINT fk_payments_reviewed_by FOREIGN KEY (reviewed_by_id) REFERENCES users (id),
    -- Un período de un contrato, o una estadía; nunca los dos ni ninguno.
    CONSTRAINT ck_payments_target CHECK (
        (contract_id IS NOT NULL AND period IS NOT NULL AND stay_id IS NULL)
        OR (stay_id IS NOT NULL AND contract_id IS NULL AND period IS NULL)),
    CONSTRAINT ck_payments_period_first_day CHECK (period IS NULL OR EXTRACT(DAY FROM period) = 1),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_size_bytes CHECK (size_bytes > 0),
    CONSTRAINT uq_payments_voucher_key UNIQUE (voucher_key),
    CONSTRAINT ck_payments_content_type CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    CONSTRAINT ck_payments_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_payments_recorded_by CHECK (recorded_by IN ('GUEST', 'ADMIN')),
    -- Todo rechazo lleva un motivo que no es solo espacios (RN-05).
    CONSTRAINT ck_payments_rejection_reason CHECK (
        status <> 'REJECTED' OR (rejection_reason IS NOT NULL AND length(btrim(rejection_reason)) > 0)),
    -- Un pago resuelto dice quién y cuándo; uno pendiente no.
    CONSTRAINT ck_payments_review CHECK (
        (status = 'PENDING' AND reviewed_at IS NULL AND reviewed_by_id IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND reviewed_at IS NOT NULL AND reviewed_by_id IS NOT NULL))
);

-- RN-02, RN-15: un período tiene a lo sumo UN pago vivo. Un solo índice y no uno por estado: con dos, aprobar el
-- pendiente mientras otra petición sube uno nuevo dejaba un APPROVED y un PENDING del mismo período.
CREATE UNIQUE INDEX uq_payments_live_period ON payments (contract_id, period) WHERE status IN ('PENDING', 'APPROVED');
-- RN-03: una estadía tiene un único pago vivo.
CREATE UNIQUE INDEX uq_payments_live_stay ON payments (stay_id) WHERE status IN ('PENDING', 'APPROVED');

-- La deuda de un huésped (RN-25) lee sus pagos por estado y período.
CREATE INDEX ix_payments_guest_status_period ON payments (guest_id, status, period);
-- La cola de revisión por estado, del voucher más antiguo al más reciente, leída por último índice.
CREATE INDEX ix_payments_queue ON payments (status, created_date, id);
-- La cola sin filtro de estado, en el mismo orden.
CREATE INDEX ix_payments_created ON payments (created_date, id);
-- El historial de un huésped va del más reciente al más antiguo.
CREATE INDEX ix_payments_history ON payments (guest_id, created_date DESC, id DESC);
-- Los pagos de una lista de estadías o de contratos en un período, de todo estado (panel); los únicos parciales
-- solo cubren PENDING y APPROVED.
CREATE INDEX ix_payments_stay ON payments (stay_id);
CREATE INDEX ix_payments_contract_period ON payments (contract_id, period);
-- La suma mensual de los pagos aprobados de los contratos (resumen financiero). No es parcial por estado: el
-- estado llega como parámetro y el plan genérico de la sentencia preparada no usaría un predicado con estado.
CREATE INDEX ix_payments_period_status ON payments (period, status);

COMMENT ON TABLE payments IS 'Pagos con voucher (RN-04, RN-22): de un período de un contrato o de una estadía. Se resuelven una sola vez y no se borran.';
COMMENT ON COLUMN payments.id IS 'Identificador del pago (BIGINT de la secuencia payments_seq); el BFF lo muestra enmascarado como UUID.';
COMMENT ON COLUMN payments.guest_id IS 'Huésped que paga (RN-09: solo ve los suyos).';
COMMENT ON COLUMN payments.contract_id IS 'Contrato al que pertenece el período; NULL en el pago de una estadía.';
COMMENT ON COLUMN payments.stay_id IS 'Estadía que paga un huésped temporal (RN-03); NULL en el pago de un período de contrato.';
COMMENT ON COLUMN payments.period IS 'Primer día del mes que se paga (RN-02, sin prorrateo); NULL en el pago de una estadía.';
COMMENT ON COLUMN payments.amount IS 'Monto declarado en soles, mayor que 0; el administrador lo compara con el voucher (RN-16).';
COMMENT ON COLUMN payments.payment_method IS 'Medio de pago en texto libre, por ejemplo Yape o transferencia (RN-28); opcional.';
COMMENT ON COLUMN payments.voucher_key IS 'Clave del objeto del voucher en el bucket R2; nunca la URL firmada, que se firma al pedirlo y caduca.';
COMMENT ON COLUMN payments.original_name IS 'Nombre del archivo que subió quien registró el pago, saneado.';
COMMENT ON COLUMN payments.content_type IS 'Tipo del archivo (image/jpeg, image/png o application/pdf), deducido del contenido y nunca del que declara el cliente.';
COMMENT ON COLUMN payments.size_bytes IS 'Tamaño del voucher en bytes, mayor que 0.';
COMMENT ON COLUMN payments.status IS 'PENDING (por revisar), APPROVED o REJECTED (RN-04, RN-05).';
COMMENT ON COLUMN payments.rejection_reason IS 'Motivo del rechazo, obligatorio si el pago está REJECTED (RN-05); el huésped lo ve solo en la app.';
COMMENT ON COLUMN payments.reviewed_at IS 'Cuándo el administrador aprobó o rechazó el pago; NULL mientras está PENDING.';
COMMENT ON COLUMN payments.reviewed_by_id IS 'Administrador que aprobó o rechazó el pago; NULL mientras está PENDING.';
COMMENT ON COLUMN payments.recorded_by IS 'GUEST (subió su voucher) o ADMIN (lo registró en su nombre, RN-23); lo fija el servidor.';
COMMENT ON COLUMN payments.version IS 'Versión para el bloqueo optimista: dos resoluciones simultáneas no se pisan.';
COMMENT ON COLUMN payments.created_date IS 'Fecha de creación del registro (auditoría); es cuándo se subió el voucher.';
COMMENT ON COLUMN payments.last_modified_date IS 'Fecha de la última modificación del registro (auditoría).';
COMMENT ON COLUMN payments.created_by_id IS 'Usuario con sesión que creó el registro (auditoría); NULL si no hubo usuario.';
COMMENT ON COLUMN payments.last_modified_by_id IS 'Usuario con sesión que modificó el registro por última vez (auditoría); NULL si no hubo usuario.';

-- =====================================================================================================
-- Multas (RF-19, RN-30, RN-24): ingreso directo del mes; no se editan y se borran libremente.
-- =====================================================================================================

CREATE SEQUENCE fines_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE fines (
    id                    BIGINT PRIMARY KEY,
    guest_id              BIGINT NOT NULL,
    amount                NUMERIC(10, 2) NOT NULL,
    month                 DATE NOT NULL,
    description           TEXT NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT fk_fines_guest FOREIGN KEY (guest_id) REFERENCES guests (id),
    CONSTRAINT ck_fines_amount CHECK (amount > 0),
    CONSTRAINT ck_fines_month CHECK (month = date_trunc('month', month)::date),
    CONSTRAINT ck_fines_description CHECK (char_length(description) <= 500)
);

CREATE INDEX ix_fines_month ON fines (month);

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

-- =====================================================================================================
-- Accesos del huésped (RF-11, RF-17, RN-32): enlace de activación o contraseña temporal, 30 minutos, un solo uso.
-- Solo se guarda el SHA-256 del secreto. Un solo acceso vivo por huésped (índice único parcial); generar uno
-- nuevo revoca el anterior con la fila del huésped bloqueada. Consumir es un UPDATE condicionado.
-- =====================================================================================================

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

-- =====================================================================================================
-- Onboarding del huésped: documentos y consentimientos (RF-14, RF-20, RN-14, RN-33, RN-37)
--
-- Son evidencia (Ley 29733): la aplicación solo los inserta y un disparador rechaza UPDATE (y DELETE en los
-- consentimientos). El máximo de 5 documentos lo cuenta la aplicación con la fila del huésped bloqueada.
-- TRUNCATE no activa los disparadores de fila y queda solo para las pruebas.
-- =====================================================================================================

CREATE FUNCTION reject_onboarding_record_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Los registros de % no se modifican ni se borran', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

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
-- Un documento no se modifica; su borrado lo controla trg_guest_documents_delete_with_evidence (más abajo).
CREATE TRIGGER trg_guest_documents_immutable BEFORE UPDATE ON guest_documents
    FOR EACH ROW EXECUTE FUNCTION reject_onboarding_record_change();

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
    CONSTRAINT ck_guest_consents_text_sha256 CHECK (text_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT uq_guest_consents_guest_version UNIQUE (guest_id, consent_version)
);

CREATE INDEX ix_guest_consents_guest ON guest_consents (guest_id, accepted_at DESC, id DESC);
CREATE TRIGGER trg_guest_consents_immutable BEFORE UPDATE OR DELETE ON guest_consents
    FOR EACH ROW EXECUTE FUNCTION reject_onboarding_record_change();

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
COMMENT ON CONSTRAINT uq_guest_consents_guest_version ON guest_consents IS 'Un consentimiento por huésped y versión del texto (D-49): aceptar de nuevo la misma versión no deja otro registro.';

-- =====================================================================================================
-- Borrado y reemplazo de documentos del huésped (RN-37): solo por solicitud ARCO o error de carga, con evidencia
--
--  * La evidencia (guest_document_actions) solo se inserta; nunca guarda el nombre, la clave ni el contenido del
--    archivo. Un documento recibe a lo sumo una acción (uq_guest_document_actions_document).
--  * Un documento solo se borra si su evidencia ya está escrita (trg_guest_documents_delete_with_evidence). La
--    aplicación inserta la evidencia y borra la fila en la misma transacción, con la fila del huésped y la del
--    documento bloqueadas (FOR UPDATE, en ese orden).
--  * guest_document_purges guarda las claves de R2 sin fila (el archivo antiguo de un reemplazo) hasta borrarlas.
--  * guest_document_deletions guarda la intención de un borrado en su propia transacción antes de borrar el
--    objeto de R2; al consolidarse en evidencia, la intención se borra.
-- =====================================================================================================

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

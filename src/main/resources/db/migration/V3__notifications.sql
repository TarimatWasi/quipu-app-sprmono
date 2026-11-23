-- Notificaciones de campana (contrato v0.27.0). Se generan desde los eventos de dominio confirmados
-- (outbox, V2): una por evento y tipo. Guardan solo hechos (tipo, recurso y valores como un monto o
-- un mes); el texto se arma al leer, con los datos actuales, así que no persiste ningún dato personal.

CREATE TABLE notifications (
    id          bigint GENERATED ALWAYS AS IDENTITY,
    event_id    uuid NOT NULL,
    type        character varying(40) NOT NULL,
    audience    character varying(20) NOT NULL,
    guest_id    bigint,
    target_kind character varying(20) NOT NULL,
    target_id   bigint NOT NULL,
    facts       jsonb NOT NULL,
    created_at  timestamp with time zone NOT NULL,
    CONSTRAINT notifications_pkey PRIMARY KEY (id),
    CONSTRAINT uq_notifications_event_type UNIQUE (event_id, type),
    CONSTRAINT ck_notifications_type CHECK (type IN ('PAYMENT_PROOF_SUBMITTED', 'ONBOARDING_COMPLETED', 'GUEST_DOCUMENT_UPLOADED', 'CONTRACT_EXPIRING', 'PAYMENT_OVERDUE', 'PAYMENT_APPROVED', 'PAYMENT_REJECTED', 'REFUND_CREATED', 'FINE_APPLIED', 'CONTRACT_SIGNED', 'CONTRACT_TERMINATED', 'PAYMENT_DUE')),
    CONSTRAINT ck_notifications_audience CHECK (audience IN ('ADMINISTRATORS', 'GUEST')),
    CONSTRAINT ck_notifications_guest CHECK (audience <> 'GUEST' OR guest_id IS NOT NULL),
    CONSTRAINT ck_notifications_target_kind CHECK (target_kind IN ('PAYMENT', 'GUEST', 'CONTRACT', 'FINE', 'REFUND', 'DOCUMENT')),
    CONSTRAINT ck_notifications_facts CHECK (jsonb_typeof(facts) = 'object')
);

COMMENT ON TABLE notifications IS 'Notificaciones de campana: una por evento de dominio y tipo. Solo hechos; el texto se arma al leer. Se borran a los 90 días, de a pocas mientras llegan nuevas.';

COMMENT ON COLUMN notifications.id IS 'Identificador (identidad); el BFF lo muestra enmascarado como UUID.';

COMMENT ON COLUMN notifications.event_id IS 'Evento de dominio que la generó (id de CloudEvents en audit_event); con el tipo, clave de idempotencia de la entrega del outbox.';

COMMENT ON COLUMN notifications.type IS 'Qué pasó (NotificationType del contrato); fija la audiencia y el texto.';

COMMENT ON COLUMN notifications.audience IS 'ADMINISTRATORS (bandeja compartida, lectura por cuenta) o GUEST (la del huésped guest_id).';

COMMENT ON COLUMN notifications.guest_id IS 'Huésped al que va (GUEST) o del que trata (ADMINISTRATORS, si aplica). Sin clave foránea: la notificación no bloquea al huésped.';

COMMENT ON COLUMN notifications.target_kind IS 'Tipo del recurso que abre (PAYMENT, GUEST, CONTRACT, FINE, REFUND o DOCUMENT).';

COMMENT ON COLUMN notifications.target_id IS 'Id interno del recurso que abre; el BFF lo enmascara para la sesión.';

COMMENT ON COLUMN notifications.facts IS 'Valores que el texto necesita (montos, meses, fechas) por nombre; nunca texto libre ni datos personales.';

COMMENT ON COLUMN notifications.created_at IS 'Cuándo ocurrió el hecho (el del evento, no el de la entrega).';

CREATE INDEX ix_notifications_administrators ON notifications USING btree (created_at DESC, id DESC) WHERE audience = 'ADMINISTRATORS';

CREATE INDEX ix_notifications_guest ON notifications USING btree (guest_id, created_at DESC, id DESC) WHERE audience = 'GUEST';

CREATE INDEX ix_notifications_created_at ON notifications USING btree (created_at);

CREATE TABLE notification_reads (
    notification_id bigint NOT NULL,
    user_id         bigint NOT NULL,
    read_at         timestamp with time zone NOT NULL,
    CONSTRAINT notification_reads_pkey PRIMARY KEY (notification_id, user_id),
    CONSTRAINT notification_reads_notification_id_fkey FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE CASCADE
);

COMMENT ON TABLE notification_reads IS 'Quién leyó qué notificación y cuándo: cada administrador tiene su propio estado en la bandeja compartida.';

COMMENT ON COLUMN notification_reads.notification_id IS 'Notificación leída; se borra con ella.';

COMMENT ON COLUMN notification_reads.user_id IS 'Cuenta que la leyó. Sin clave foránea: el estado de lectura no bloquea la cuenta.';

COMMENT ON COLUMN notification_reads.read_at IS 'La primera vez que la cuenta la leyó; marcarla otra vez no la cambia.';

-- Plataforma de eventos de dominio: outbox de Spring Modulith y traza de auditoría.
-- Un caso de uso publica sus hechos con @PublishEvent dentro de su transacción; la fila del outbox
-- se escribe en esa misma transacción y los listeners (auditoría, notificaciones) la consumen
-- después del commit, con reintento. Las invalidaciones en vivo (SSE) no pasan por aquí.

-- Outbox: DDL v2 de spring-modulith-events-jdbc 2.1.2 para PostgreSQL, sin cambios (la aplicación
-- no lo crea: spring.modulith.events.jdbc.schema-initialization.enabled=false).
CREATE TABLE event_publication (
    id                     uuid NOT NULL,
    listener_id            text NOT NULL,
    event_type             text NOT NULL,
    serialized_event       text NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamp with time zone,
    CONSTRAINT event_publication_pkey PRIMARY KEY (id)
);

CREATE INDEX event_publication_serialized_event_hash_idx ON event_publication USING hash (serialized_event);

CREATE INDEX event_publication_by_completion_date_idx ON event_publication USING btree (completion_date);

COMMENT ON TABLE event_publication IS 'Outbox de eventos de dominio (Spring Modulith): una fila por evento y listener persistente; se escribe en la transacción del caso de uso y se borra cuando el listener termina (completion-mode delete).';

COMMENT ON COLUMN event_publication.id IS 'Identificador de la publicación (uno por evento y listener).';

COMMENT ON COLUMN event_publication.listener_id IS 'Método del listener que debe consumir el evento (clase.método(tipo)); renombrarlo deja huérfanas sus publicaciones pendientes.';

COMMENT ON COLUMN event_publication.event_type IS 'Clase del evento de dominio; renombrarla impide leer sus publicaciones pendientes.';

COMMENT ON COLUMN event_publication.serialized_event IS 'El evento en JSON (Jackson): solo identificadores y valores, nunca datos personales (QP-EVT-02).';

COMMENT ON COLUMN event_publication.publication_date IS 'Cuándo se publicó el evento (dentro de la transacción del caso de uso).';

COMMENT ON COLUMN event_publication.completion_date IS 'Cuándo terminó el listener; NULL mientras está pendiente o falló.';

COMMENT ON COLUMN event_publication.status IS 'Estado de Modulith: PUBLISHED, PROCESSING, COMPLETED, FAILED o RESUBMITTED.';

COMMENT ON COLUMN event_publication.completion_attempts IS 'Intentos de entrega al listener.';

COMMENT ON COLUMN event_publication.last_resubmission_date IS 'Último reenvío de una publicación fallida; NULL si nunca se reenvió.';

-- Traza de auditoría con forma de CloudEvents 1.0: se exporta tal cual a cualquier consumidor.
CREATE TABLE audit_event (
    id            uuid NOT NULL,
    spec_version  character varying(8) NOT NULL,
    source        character varying(60) NOT NULL,
    type          character varying(160) NOT NULL,
    subject       character varying(120) NOT NULL,
    occurred_at   timestamp with time zone NOT NULL,
    actor_user_id bigint,
    guest_id      bigint,
    data          jsonb NOT NULL,
    recorded_at   timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT audit_event_pkey PRIMARY KEY (id),
    CONSTRAINT ck_audit_event_spec_version CHECK (spec_version = '1.0'),
    CONSTRAINT ck_audit_event_source CHECK (source ~ '^/quipu/[a-z]+$'),
    CONSTRAINT ck_audit_event_type CHECK (type ~ '^com\.tarimatwasi\.quipu\.[a-z]+\.[a-z0-9-]+\.v[0-9]+$'),
    CONSTRAINT ck_audit_event_subject CHECK (subject ~ '^[a-z-]+/[0-9]+$'),
    CONSTRAINT ck_audit_event_data CHECK (jsonb_typeof(data) = 'object')
);

COMMENT ON TABLE audit_event IS 'Traza de auditoría: un hecho confirmado por fila, con forma de CloudEvents 1.0. Solo se inserta (trigger y permisos); un evento entregado dos veces se registra una sola vez.';

COMMENT ON COLUMN audit_event.id IS 'CloudEvents id: identificador del evento, único por ocurrencia; clave de idempotencia de la entrega del outbox.';

COMMENT ON COLUMN audit_event.spec_version IS 'CloudEvents specversion: siempre 1.0.';

COMMENT ON COLUMN audit_event.source IS 'CloudEvents source: /quipu/<módulo> que confirmó el hecho.';

COMMENT ON COLUMN audit_event.type IS 'CloudEvents type: com.tarimatwasi.quipu.<módulo>.<evento>.v<versión del esquema del evento>.';

COMMENT ON COLUMN audit_event.subject IS 'CloudEvents subject: <tipo>/<id interno> del recurso del hecho; el id nunca sale sin enmascarar al BFF.';

COMMENT ON COLUMN audit_event.occurred_at IS 'CloudEvents time: cuándo ocurrió el hecho (reloj de la aplicación, dentro de su transacción).';

COMMENT ON COLUMN audit_event.actor_user_id IS 'Extensión actorid: cuenta que causó el hecho; NULL para el sistema. Sin clave foránea: la traza sobrevive a la cuenta.';

COMMENT ON COLUMN audit_event.guest_id IS 'Extensión guestid: huésped al que se refiere el hecho, si lo hay; responde solicitudes ARCO (Ley 29733). Sin clave foránea.';

COMMENT ON COLUMN audit_event.data IS 'CloudEvents data (application/json): el hecho sin sus metadatos. Solo identificadores y valores, nunca texto libre ni datos personales.';

COMMENT ON COLUMN audit_event.recorded_at IS 'Cuándo se escribió la fila (entrega del outbox, después del commit).';

CREATE INDEX ix_audit_event_occurred_at ON audit_event USING btree (occurred_at DESC);

CREATE INDEX ix_audit_event_subject ON audit_event USING btree (subject, occurred_at DESC);

CREATE INDEX ix_audit_event_guest ON audit_event USING btree (guest_id, occurred_at DESC) WHERE guest_id IS NOT NULL;

CREATE INDEX ix_audit_event_actor ON audit_event USING btree (actor_user_id, occurred_at DESC) WHERE actor_user_id IS NOT NULL;

CREATE TRIGGER trg_audit_event_immutable BEFORE DELETE OR UPDATE ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_onboarding_record_change();

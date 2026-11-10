-- TAR-228: sesiones con refresh token y rotación. Una fila por sesión abierta (login, activación);
-- el access token (JWT de 15 minutos) nombra su sesión con el claim sid, y la sesión se renueva con
-- un refresh token opaco que rota en cada uso. Nada de esto guarda un secreto en claro: del sid y
-- del refresh token solo queda su SHA-256.

CREATE SEQUENCE sessions_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE sessions (
    id                    BIGINT PRIMARY KEY,
    user_id               BIGINT NOT NULL REFERENCES users (id),
    sid_hash              VARCHAR(64) NOT NULL,
    security_version      BIGINT NOT NULL,
    temporary             BOOLEAN NOT NULL,
    absolute_expires_at   TIMESTAMPTZ NOT NULL,
    last_used_at          TIMESTAMPTZ NOT NULL,
    revoked_at            TIMESTAMPTZ NULL,
    revoked_reason        VARCHAR(20) NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT uq_sessions_sid_hash UNIQUE (sid_hash),
    CONSTRAINT ck_sessions_security_version CHECK (security_version >= 0),
    CONSTRAINT ck_sessions_revoked CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
    CONSTRAINT ck_sessions_revoked_reason
        CHECK (revoked_reason IN ('LOGOUT', 'REUSE', 'PASSWORD_CHANGE', 'INVALIDATED'))
);

CREATE INDEX ix_sessions_user ON sessions (user_id);

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

CREATE SEQUENCE refresh_tokens_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE refresh_tokens (
    id                    BIGINT PRIMARY KEY,
    session_id            BIGINT NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    token_hash            VARCHAR(64) NOT NULL,
    issued_at             TIMESTAMPTZ NOT NULL,
    rotated_at            TIMESTAMPTZ NULL,
    created_date          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_modified_date    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_by_id         BIGINT NULL REFERENCES users (id),
    last_modified_by_id   BIGINT NULL REFERENCES users (id),
    CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash)
);

CREATE INDEX ix_refresh_tokens_session ON refresh_tokens (session_id);

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

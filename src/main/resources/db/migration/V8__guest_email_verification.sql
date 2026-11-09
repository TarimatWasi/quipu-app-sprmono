CREATE TABLE guest_email_verifications (
    guest_id BIGINT PRIMARY KEY REFERENCES guests (id) ON DELETE CASCADE,
    email VARCHAR(200) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    last_sent_at TIMESTAMPTZ NOT NULL,
    attempts SMALLINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_guest_email_verification_attempts CHECK (attempts BETWEEN 0 AND 5)
);

-- Carry forward legacy contact addresses only where the account had no email. The account email
-- remains authoritative whenever both old fields were populated.
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM guests g LEFT JOIN users u ON g.id = u.guest_id
    WHERE (u.id IS NULL OR u.email IS NULL) AND g.email IS NOT NULL
      AND EXISTS (SELECT 1 FROM users other WHERE lower(other.email) = lower(g.email))
  ) THEN
    RAISE EXCEPTION 'A guest legacy email is already owned by another account; resolve it before migration';
  END IF;
  IF EXISTS (
    SELECT 1 FROM guests g LEFT JOIN users u ON g.id = u.guest_id
    WHERE g.email IS NOT NULL AND u.id IS NULL
  ) THEN
    RAISE EXCEPTION 'A guest with a legacy email has no account; create its account before migration';
  END IF;
END $$;

UPDATE users u
SET email = g.email
FROM guests g
WHERE g.id = u.guest_id
  AND u.email IS NULL
  AND g.email IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM users other WHERE lower(other.email) = lower(g.email));

DROP INDEX uq_guests_email_lower;
ALTER TABLE guests DROP COLUMN email;

COMMENT ON TABLE guest_email_verifications IS 'Solicitud pendiente de confirmar el correo del huésped; el correo vigente no cambia hasta validar el código (TAR-349).';
COMMENT ON COLUMN guest_email_verifications.guest_id IS 'Huésped que pidió el cambio; a lo sumo una solicitud pendiente por huésped.';
COMMENT ON COLUMN guest_email_verifications.email IS 'Correo nuevo que espera su código; pasa a ser el correo de la cuenta al confirmarlo.';
COMMENT ON COLUMN guest_email_verifications.code_hash IS 'Hash del código de 6 dígitos enviado al correo nuevo; el código nunca se guarda en claro.';
COMMENT ON COLUMN guest_email_verifications.expires_at IS 'Instante en que el código deja de servir (10 minutos después del envío).';
COMMENT ON COLUMN guest_email_verifications.last_sent_at IS 'Último envío de un código; otro envío espera 60 segundos desde aquí.';
COMMENT ON COLUMN guest_email_verifications.attempts IS 'Códigos equivocados ingresados; con 5 la solicitud vence y hay que pedir otro código.';

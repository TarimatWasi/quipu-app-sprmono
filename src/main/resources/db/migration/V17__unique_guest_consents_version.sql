-- TAR-137 (D-49, RN-14): un huésped acepta cada versión del texto de consentimiento una sola vez. La aplicación
-- ya no inserta un segundo consentimiento de la misma versión (lo consulta con la fila del huésped bloqueada) y la
-- base lo garantiza. La versión del texto es la columna consent_version.
--
-- No se borra ni se modifica ninguna fila: la tabla es inmutable por disparador. Si ya hubiera dos consentimientos
-- de un huésped con la misma versión, esta migración falla y hay que resolverlo a mano antes de desplegar.

ALTER TABLE guest_consents
    ADD CONSTRAINT uq_guest_consents_guest_version UNIQUE (guest_id, consent_version);

COMMENT ON CONSTRAINT uq_guest_consents_guest_version ON guest_consents IS 'Un consentimiento por huésped y versión del texto (D-49): aceptar de nuevo la misma versión no deja otro registro.';

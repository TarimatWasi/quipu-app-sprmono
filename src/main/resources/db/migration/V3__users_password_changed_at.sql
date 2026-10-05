-- TAR-125: cuándo se cambió o restableció por última vez la contraseña. El JWT que se emitió antes de ese
-- instante deja de valer, de modo que quien se llevó una cookie no sigue dentro después de un cambio o un
-- restablecimiento. NULL: la cuenta nunca la cambió y todo token válido sirve.
ALTER TABLE users ADD COLUMN password_changed_at TIMESTAMPTZ NULL;

COMMENT ON COLUMN users.password_changed_at IS 'Instante del último cambio o restablecimiento de contraseña; un token emitido antes ya no es válido (TAR-125). NULL si nunca se cambió.';

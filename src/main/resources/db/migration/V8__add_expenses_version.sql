-- TAR-171 (BE-SPR-DAT-13): bloqueo optimista en los egresos. Dos ediciones simultáneas ya no se pisan;
-- el borrado sigue siendo libre (RN-24). Las filas existentes quedan en la versión 0.

ALTER TABLE expenses ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

COMMENT ON TABLE expenses IS 'Egresos operativos del negocio, en conjunto y sin reparto entre integrantes (RF-07, RN-11); se editan con bloqueo optimista y se eliminan libremente (RN-24).';
COMMENT ON COLUMN expenses.version IS 'Versión para el bloqueo optimista: dos ediciones simultáneas no se pisan.';

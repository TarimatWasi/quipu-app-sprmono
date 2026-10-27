-- TAR-194: V13 creó un disparador transitorio que completaba terminated_at cuando una versión de la aplicación
-- anterior a V13 finalizaba un contrato cambiando solo status. Ya no queda ninguna versión pre-V13 desplegable y
-- la aplicación siempre guarda ambas columnas, así que se retira. La columna y el CHECK ck_contracts_terminated_at
-- se mantienen: finalizar sin terminated_at ahora falla.

DROP TRIGGER trg_contracts_fill_terminated_at ON contracts;
DROP FUNCTION contracts_fill_terminated_at();

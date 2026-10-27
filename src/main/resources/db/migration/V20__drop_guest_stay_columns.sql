-- TAR-182 (RN-13, RN-17, CU-31): fase de contraer de las estadías. V11 creó la tabla stays y copió las estadías;
-- las columnas stay_start_date, stay_end_date y agreed_amount de guests quedaron para la aplicación anterior, que
-- ya no se despliega. La aplicación actual ni las lee ni las escribe, así que se retiran junto con sus CHECK
-- ck_guests_stay_dates y ck_guests_agreed_amount (V6).
--
-- Antes de borrarlas se copia a stays, con el mismo criterio que V11, cada huésped temporal con los tres datos
-- completos que todavía no tiene estadía (los que escribió la aplicación anterior durante el despliegue de V11).
--
-- Supuesto: las filas con datos parciales (alguno de los tres nulo) se descartan porque nunca formaron una estadía
-- válida; tampoco se copian los datos de un huésped que no es temporal. Solo se informa cuántas filas con algún
-- dato quedan sin estadía, sin documentos, nombres ni ids.
--
-- Se bloquean guests y luego stays para que ninguna escritura concurrente cree una estadía duplicada ni deje datos
-- entre la copia y el borrado; spring.flyway.init-sqls limita la espera.
--
-- Rollback: no es reversible sin pérdida. Una migración nueva puede volver a crear las columnas y los CHECK y
-- rellenarlas desde la estadía más reciente de cada huésped; las filas parciales descartadas no se recuperan.

LOCK TABLE guests IN ACCESS EXCLUSIVE MODE;
LOCK TABLE stays IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO stays (id, guest_id, start_date, end_date, agreed_amount, created_date, last_modified_date)
SELECT nextval('stays_seq'), g.id, g.stay_start_date, g.stay_end_date, g.agreed_amount, now(), now()
FROM guests g
WHERE g.guest_type = 'TEMPORARY'
  AND g.stay_start_date IS NOT NULL
  AND g.stay_end_date IS NOT NULL
  AND g.agreed_amount IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM stays s WHERE s.guest_id = g.id)
ORDER BY g.id;

DO $$
DECLARE
    discarded BIGINT;
BEGIN
    SELECT count(*) INTO discarded
    FROM guests g
    WHERE (g.stay_start_date IS NOT NULL OR g.stay_end_date IS NOT NULL OR g.agreed_amount IS NOT NULL)
      AND NOT EXISTS (SELECT 1 FROM stays s WHERE s.guest_id = g.id);
    RAISE NOTICE 'V20: % huéspedes con datos de estadía incompletos o no temporales se descartan', discarded;
END
$$;

ALTER TABLE guests
    DROP CONSTRAINT IF EXISTS ck_guests_stay_dates,
    DROP CONSTRAINT IF EXISTS ck_guests_agreed_amount,
    DROP COLUMN IF EXISTS stay_start_date,
    DROP COLUMN IF EXISTS stay_end_date,
    DROP COLUMN IF EXISTS agreed_amount;

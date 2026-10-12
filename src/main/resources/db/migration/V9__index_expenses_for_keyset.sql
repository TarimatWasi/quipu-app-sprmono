-- TAR-176 (BE-SPR-WEB-08): el listado de egresos se lee por último índice, por mes y en el orden
-- (created_date, id). Este índice sirve a esa lectura sin ordenar el mes en cada página y reemplaza
-- al de solo month, que queda cubierto por su prefijo.

CREATE INDEX ix_expenses_month_created_date_id ON expenses (month, created_date, id);

DROP INDEX ix_expenses_month;

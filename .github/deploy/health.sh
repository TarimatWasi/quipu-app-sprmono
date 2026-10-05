#!/usr/bin/env bash
# Comprobación de salud posterior al despliegue (TAR-72): <HEALTH_URL>/actuator/health debe
# responder 200 con "status":"UP". El plan gratuito de Render puede tardar 60-90 s en despertar.
# Si no se cumple, el script falla y el workflow queda en rojo; la reversión es lanzar el workflow
# con el commit anterior (ver la "Política de entornos y promoción").
set -euo pipefail

base="${HEALTH_URL:?falta HEALTH_URL}"
attempts="${HEALTH_ATTEMPTS:-24}"
pause="${HEALTH_PAUSE_SECONDS:-10}"
url="${base%/}/actuator/health"

for ((i = 1; i <= attempts; i++)); do
  code="$(curl -s -o /tmp/health.body -w '%{http_code}' --max-time 30 "$url" || true)"
  if [ "$code" = "200" ] && grep -q '"status":"UP"' /tmp/health.body; then
    echo "Salud correcta en el intento $i: $url"
    exit 0
  fi
  echo "Intento $i/$attempts: HTTP $code"
  sleep "$pause"
done

echo "El servicio no quedó sano después de $attempts intentos: $url" >&2
exit 1

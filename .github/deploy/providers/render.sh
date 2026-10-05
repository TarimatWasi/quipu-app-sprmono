#!/usr/bin/env bash
# Adaptador de Render (TAR-72): pide a Render desplegar el commit y espera a que quede "live".
# Variables: RENDER_API_KEY, RENDER_SERVICE_ID, COMMIT_SHA. La clave nunca se imprime.
# Con un commit concreto Render responde 202 sin id: el despliegue creado se localiza después por
# commit y por fecha de creación.
set -euo pipefail

: "${RENDER_API_KEY:?falta RENDER_API_KEY}"
: "${RENDER_SERVICE_ID:?falta RENDER_SERVICE_ID}"
: "${COMMIT_SHA:?falta COMMIT_SHA}"

api="https://api.render.com/v1/services/${RENDER_SERVICE_ID}/deploys"
attempts="${RENDER_ATTEMPTS:-60}"
pause="${RENDER_PAUSE_SECONDS:-15}"

call() {
  curl -fsS --max-time 60 -H "Authorization: Bearer ${RENDER_API_KEY}" -H 'Accept: application/json' "$@"
}

# Un margen cubre la diferencia de relojes entre este equipo y Render.
since="$(date -u -d '-30 seconds' +%Y-%m-%dT%H:%M:%SZ)"
call -X POST -H 'Content-Type: application/json' \
  -d "{\"clearCache\":\"do_not_clear\",\"commitId\":\"${COMMIT_SHA}\"}" "$api" > /dev/null

deploy_id=""
for ((i = 1; i <= 12 && ${#deploy_id} == 0; i++)); do
  deploy_id="$(call "${api}?limit=10" | jq -r --arg sha "$COMMIT_SHA" --arg since "$since" \
    '[.[].deploy | select(.commit.id == $sha and .createdAt >= $since)] | first | .id // empty')"
  [ -n "$deploy_id" ] || sleep 5
done
if [ -z "$deploy_id" ]; then
  echo "Render no mostró el despliegue de ${COMMIT_SHA} creado después de ${since}" >&2
  exit 1
fi
echo "Render creó el despliegue ${deploy_id}"

for ((i = 1; i <= attempts; i++)); do
  status="$(call "${api}/${deploy_id}" | jq -er .status)"
  echo "Estado ${i}/${attempts}: ${status}"
  case "$status" in
    live) exit 0 ;;
    build_failed | update_failed | pre_deploy_failed | canceled | deactivated)
      echo "El despliegue ${deploy_id} terminó en ${status}" >&2
      exit 1
      ;;
  esac
  sleep "$pause"
done

echo "El despliegue ${deploy_id} no llegó a live a tiempo" >&2
exit 1

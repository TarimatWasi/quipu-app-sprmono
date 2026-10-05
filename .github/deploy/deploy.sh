#!/usr/bin/env bash
# Despliegue por pipeline, independiente del proveedor (TAR-72).
#
# El workflow solo conoce este script. Cada proveedor es un adaptador en providers/<nombre>.sh que
# recibe las mismas variables y termina con éxito solo cuando el commit está en línea:
#   DEPLOY_PROVIDER  nombre del adaptador (render, ...)
#   COMMIT_SHA       commit completo a desplegar
#   HEALTH_URL       base del servicio; después del despliegue se consulta <HEALTH_URL>/actuator/health
# Más las credenciales propias del proveedor (para render: RENDER_API_KEY y RENDER_SERVICE_ID).
# Cambiar de proveedor es escribir otro adaptador y cambiar la variable DEPLOY_PROVIDER.
set -euo pipefail

provider="${DEPLOY_PROVIDER:?falta DEPLOY_PROVIDER}"
commit="${COMMIT_SHA:?falta COMMIT_SHA}"
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! [[ "$provider" =~ ^[a-z][a-z0-9_-]*$ ]]; then
  echo "DEPLOY_PROVIDER inválido: $provider" >&2
  exit 2
fi
if ! [[ "$commit" =~ ^[0-9a-f]{40}$ ]]; then
  echo "COMMIT_SHA debe ser un commit completo de 40 caracteres" >&2
  exit 2
fi
adapter="$here/providers/$provider.sh"
if [ ! -f "$adapter" ]; then
  echo "No hay adaptador para el proveedor '$provider' en $here/providers" >&2
  exit 2
fi

echo "Desplegando $commit con el proveedor $provider"
bash "$adapter"
bash "$here/health.sh"
echo "Despliegue de $commit completo"

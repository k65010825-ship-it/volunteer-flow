#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
ulimit -f 1024
[[ $# == 5 ]] || { printf 'Usage: PROJECT COMPOSE ENV OUTPUT api|kafka\n' >&2; exit 2; }
project=$1 compose_file=$2 compose_env=$3 output=$4 mode=$5
[[ $project =~ ^vf5[0-9a-f]{32}$ && $mode =~ ^(api|kafka)$ ]] || exit 2
compose() { timeout 15 docker compose -p "$project" -f "$compose_file" --env-file "$compose_env" "$@"; }
for ((attempt=1; attempt<=60; attempt++)); do
  if [[ $mode == kafka ]]; then
    if compose exec -T kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server kafka:9092 > "$output" 2>/dev/null; then exit 0; fi
  else
    ready=true
    for service in backend-a backend-b; do
      if ! compose exec -T "$service" curl --fail --silent --max-time 4 \
          http://localhost:8080/actuator/health/readiness > "${output}-${service}.json" 2>/dev/null; then ready=false; fi
    done
    if [[ $ready == true ]]; then printf '{"bothBackendsReady":true}\n' > "$output"; exit 0; fi
  fi
  sleep 2
done
printf 'Readiness deadline exceeded\n' >&2
exit 1

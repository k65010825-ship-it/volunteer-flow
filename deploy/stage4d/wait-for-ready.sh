#!/usr/bin/env bash
set -Eeuo pipefail

if (($# < 5 || $# > 8)); then
  printf 'Usage: wait-for-ready.sh PROJECT COMPOSE_FILE ENV_FILE PORT OUTPUT [DOCKER] [CURL] [SLEEP]\n' >&2
  exit 2
fi

compose_project="$1"
compose_file="$2"
compose_env="$3"
app_port="$4"
readiness_output="$5"
docker_command="${6:-docker}"
curl_command="${7:-curl}"
sleep_command="${8:-sleep}"

compose() {
  "$docker_command" compose \
    --project-name "$compose_project" \
    --file "$compose_file" \
    --env-file "$compose_env" \
    "$@"
}

consecutive_proxy_successes=0
for attempt in $(seq 1 60); do
  backend_a_id=$(compose ps --quiet backend-a)
  backend_b_id=$(compose ps --quiet backend-b)
  backend_a_health='missing'
  backend_b_health='missing'
  if [[ -n "$backend_a_id" ]]; then
    backend_a_health=$("$docker_command" inspect --format '{{.State.Health.Status}}' "$backend_a_id" 2>/dev/null || printf 'missing')
  fi
  if [[ -n "$backend_b_id" ]]; then
    backend_b_health=$("$docker_command" inspect --format '{{.State.Health.Status}}' "$backend_b_id" 2>/dev/null || printf 'missing')
  fi

  if [[ "$backend_a_health" == healthy && "$backend_b_health" == healthy ]] \
      && "$curl_command" --fail --silent --show-error \
        "http://127.0.0.1:${app_port}/actuator/health/readiness" > "$readiness_output"; then
    consecutive_proxy_successes=$((consecutive_proxy_successes + 1))
    if ((consecutive_proxy_successes >= 3)); then
      exit 0
    fi
  else
    consecutive_proxy_successes=0
  fi
  "$sleep_command" 2
done

exit 1

#!/usr/bin/env bash
set -Eeuo pipefail

run_id=''
remote_root=''
compose_project=''
compose_env=''
commit=''
while (($#)); do
  case "$1" in
    --run-id) run_id="$2"; shift 2 ;;
    --remote-root) remote_root="$2"; shift 2 ;;
    --compose-project) compose_project="$2"; shift 2 ;;
    --compose-env) compose_env="$2"; shift 2 ;;
    --commit) commit="$2"; shift 2 ;;
    *) printf 'Unknown argument\n' >&2; exit 2 ;;
  esac
done

uuid_pattern='^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
[[ "$run_id" =~ $uuid_pattern ]] || { printf 'Invalid run ID\n' >&2; exit 2; }
[[ "$remote_root" == "/opt/volunteerflow-stage4d/$run_id" ]] || { printf 'Invalid remote root\n' >&2; exit 2; }
[[ "$compose_project" == "vf4d${run_id//-/}" ]] || { printf 'Invalid Compose project\n' >&2; exit 2; }
[[ -f "$compose_env" ]] || { printf 'Protected environment is missing\n' >&2; exit 2; }

repo="$remote_root/repo"
compose_file="$repo/deploy/stage4d/docker-compose.yml"
output="$remote_root/output"
evidence="$remote_root/evidence"
mkdir -p "$output" "$evidence"
chmod 700 "$remote_root" "$output" "$evidence"
chmod 600 "$compose_env"

export STAGE4D_RUN_ID="$run_id"
export STAGE4D_OUTPUT_DIR="$output"
export STAGE4D_APP_PORT="${STAGE4D_APP_PORT:-8088}"
export STAGE4D_FIXTURE_ACTION=snapshot
export STAGE4D_DATASET=normal
export EXPECTED_RATE_LIMIT_ALLOWED="${EXPECTED_RATE_LIMIT_ALLOWED:-3}"
export EXPECTED_RATE_LIMIT_REJECTED="${EXPECTED_RATE_LIMIT_REJECTED:-1}"

source "$repo/deploy/stage4d/lib/dotenv.sh"

read_env() {
  local name="$1" line value count
  count=$(grep -Ec "^${name}=" "$compose_env" || true)
  [[ "$count" == '1' ]] || return 1
  line=$(grep -E "^${name}=" "$compose_env")
  value=${line#*=}
  if [[ "$value" == \"* || "$value" == *\" ]]; then
    return 1
  fi
  if [[ "$value" == \'* || "$value" == *\' ]]; then
    [[ "$value" == \'*\' && "$value" == *\' ]] || return 1
    value=${value:1:${#value}-2}
    value=$(decode_quoted_env_value "$value") || return 1
  fi
  printf '%s' "$value"
}

real_redis_host=$(read_env REDIS_HOST)
real_redis_port=$(read_env REDIS_PORT)
db_host=$(read_env DB_HOST)
db_port=$(read_env DB_PORT)
app_port=$(read_env STAGE4D_APP_PORT 2>/dev/null || printf '%s' "$STAGE4D_APP_PORT")
minimum_available_kb=$(read_env STAGE4D_MIN_AVAILABLE_KB 2>/dev/null || printf '%s' '5242880')
mysql_container=$(read_env STAGE4D_MYSQL_CONTAINER 2>/dev/null || printf '%s' 'mysql')
redis_container=$(read_env STAGE4D_REDIS_CONTAINER 2>/dev/null || printf '%s' 'redis-web')
[[ "$minimum_available_kb" =~ ^[0-9]+$ && "$minimum_available_kb" -ge 3145728 ]] || {
  printf 'STAGE4D_MIN_AVAILABLE_KB must be an integer of at least 3145728\n' >&2
  exit 2
}
container_name_pattern='^[A-Za-z0-9][A-Za-z0-9_.-]*$'
[[ "$mysql_container" =~ $container_name_pattern ]] || { printf 'Invalid MySQL container name\n' >&2; exit 2; }
[[ "$redis_container" =~ $container_name_pattern ]] || { printf 'Invalid Redis container name\n' >&2; exit 2; }
export REDIS_HOST="$real_redis_host"
export REDIS_PORT="$real_redis_port"

compose() {
  docker compose --project-name "$compose_project" --file "$compose_file" --env-file "$compose_env" "$@"
}

wait_ready() {
  bash "$repo/deploy/stage4d/wait-for-ready.sh" \
    "$compose_project" \
    "$compose_file" \
    "$compose_env" \
    "$app_port" \
    "$evidence/readiness-latest.json"
}

fixture_action() {
  local action="$1" dataset="$2"
  export STAGE4D_FIXTURE_ACTION="$action"
  export STAGE4D_DATASET="$dataset"
  compose --profile tools run --rm fixture > "$evidence/fixture-${action}-${dataset}.log" 2>&1
}

run_k6() {
  local script="$1" dataset="$2"
  local scenario="${script%.js}"
  export STAGE4D_DATASET="$dataset"
  compose --profile tools run --rm k6 run \
    --out "json=/output/k6-raw-${scenario}-${dataset}.json" \
    "/scripts/$script" > "$evidence/k6-${scenario}-${dataset}.log" 2>&1
}

restore_redis() {
  export REDIS_HOST="$real_redis_host"
  export REDIS_PORT="$real_redis_port"
  compose up --detach --force-recreate backend-a backend-b > "$evidence/redis-restore.log" 2>&1
  wait_ready
  rm -f "$remote_root/outage-active"
}

cleanup_fixtures() {
  local dataset result=0
  for dataset in outage normal; do
    if [[ -f "$remote_root/snapshot-${dataset}" ]]; then
      fixture_action cleanup "$dataset" || result=1
      fixture_action assert-snapshot "$dataset" || result=1
    fi
  done
  return "$result"
}

collect_logs() {
  compose logs --no-color backend-a backend-b frontend-nginx 2>&1 \
    | grep -Eiv 'authorization|cookie|db_password|jwt_secret|rate_limit_key_secret|accessToken' \
    > "$evidence/container.log" || true
  compose logs --no-color frontend-nginx > "$evidence/nginx-access.log" 2>&1 || true
}

scan_evidence_for_secrets() {
  local name value
  for name in DB_PASSWORD JWT_SECRET REDIS_PASSWORD RATE_LIMIT_KEY_SECRET BOOTSTRAP_ADMIN_PASSWORD; do
    if ! grep -q "^${name}=" "$compose_env"; then
      continue
    fi
    if ! value=$(read_env "$name" 2>/dev/null); then
      printf 'FAIL\n' > "$evidence/evidence-secret-scan.txt"
      return 1
    fi
    if [[ -n "$value" ]] && grep -R -F -q -- "$value" "$evidence"; then
      printf 'FAIL\n' > "$evidence/evidence-secret-scan.txt"
      return 1
    fi
  done
  if grep -R -E -q -- '-----BEGIN (OPENSSH|RSA|EC) PRIVATE KEY-----|Bearer[[:space:]]+eyJ|eyJ[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}\.[A-Za-z0-9_-]{16,}' "$evidence"; then
    printf 'FAIL\n' > "$evidence/evidence-secret-scan.txt"
    return 1
  fi
  printf 'PASS\n' > "$evidence/evidence-secret-scan.txt"
}

preflight() {
  command -v docker >/dev/null
  command -v curl >/dev/null
  command -v timeout >/dev/null
  command -v lscpu >/dev/null
  command -v free >/dev/null
  docker compose version > "$evidence/docker-compose-version.txt"
  cat /etc/os-release > "$evidence/vm-os-release.txt"
  uname -a > "$evidence/vm-kernel.txt"
  lscpu > "$evidence/vm-cpu.txt"
  free -b > "$evidence/vm-memory.txt"
  df -PT "$remote_root" > "$evidence/vm-disk.txt"
  docker version > "$evidence/docker-version.txt"
  : > "$evidence/service-containers.jsonl"
  docker inspect --format '{"id":"{{.Id}}","name":"{{.Name}}","image":"{{.Config.Image}}","running":{{.State.Running}}}' \
    "$mysql_container" >> "$evidence/service-containers.jsonl"
  docker inspect --format '{"id":"{{.Id}}","name":"{{.Name}}","image":"{{.Config.Image}}","running":{{.State.Running}}}' \
    "$redis_container" >> "$evidence/service-containers.jsonl"
  docker inspect --format 'image={{.Config.Image}} id={{.Id}} running={{.State.Running}}' \
    "$mysql_container" > "$evidence/mysql-version.txt"
  docker exec "$mysql_container" mysqld --version >> "$evidence/mysql-version.txt"
  docker inspect --format 'image={{.Config.Image}} id={{.Id}} running={{.State.Running}}' \
    "$redis_container" > "$evidence/redis-version.txt"
  docker exec "$redis_container" redis-server --version >> "$evidence/redis-version.txt"
  local available_kb
  available_kb=$(df -Pk "$remote_root" | awk 'NR==2 {print $4}')
  [[ "$available_kb" =~ ^[0-9]+$ && "$available_kb" -ge "$minimum_available_kb" ]]
  timeout 5 bash -c "</dev/tcp/${db_host}/${db_port}"
  timeout 5 bash -c "</dev/tcp/${real_redis_host}/${real_redis_port}"
  if command -v ss >/dev/null && ss -ltn | awk '{print $4}' | grep -Eq "(^|:)${app_port}$"; then
    printf 'Stage 4D public port is already occupied\n' >&2
    return 1
  fi
  printf '{"docker":true,"disk":true,"mysql":true,"redis":true,"port":true,"metadata":true}\n' > "$evidence/preflight.json"
}

primary_flow() {
  printf 'preflight' > "$evidence/current-step.txt"
  preflight
  printf 'compose-config' > "$evidence/current-step.txt"
  compose --profile tools config --no-interpolate > "$evidence/compose-config.yml"
  printf 'build' > "$evidence/current-step.txt"
  compose --profile tools build backend-a backend-b frontend-nginx fixture > "$evidence/build.log" 2>&1
  printf 'stack-readiness' > "$evidence/current-step.txt"
  compose up --detach backend-a backend-b frontend-nginx > "$evidence/stack-up.log" 2>&1
  wait_ready
  cp "$evidence/readiness-latest.json" "$evidence/readiness-initial.json"

  printf 'normal-fixture' > "$evidence/current-step.txt"
  fixture_action snapshot normal
  touch "$remote_root/snapshot-normal"
  fixture_action seed normal
  printf 'normal-registration' > "$evidence/current-step.txt"
  run_k6 registration.js normal
  fixture_action verify normal
  sleep 11
  printf 'rate-limit' > "$evidence/current-step.txt"
  run_k6 rate-limit.js normal

  printf 'outage-fixture' > "$evidence/current-step.txt"
  fixture_action snapshot outage
  touch "$remote_root/snapshot-outage"
  fixture_action seed outage
  printf 'outage-injection' > "$evidence/current-step.txt"
  export REDIS_HOST=127.0.0.1
  export REDIS_PORT=6379
  touch "$remote_root/outage-active"
  compose up --detach --force-recreate backend-a backend-b > "$evidence/redis-outage.log" 2>&1
  wait_ready
  printf 'outage-registration' > "$evidence/current-step.txt"
  run_k6 registration.js outage
  fixture_action verify outage
  printf 'outage-login-fallback' > "$evidence/current-step.txt"
  run_k6 outage-login-fallback.js outage
  printf 'outage-dynamic-checkin' > "$evidence/current-step.txt"
  run_k6 outage-dynamic-checkin.js outage

  printf 'redis-recovery' > "$evidence/current-step.txt"
  restore_redis
  cp "$evidence/readiness-latest.json" "$evidence/readiness-recovered.json"
  run_k6 recovered-shared-rate-limit.js outage
  run_k6 recovery.js outage

  printf 'nginx-upstream-evidence' > "$evidence/current-step.txt"
  local ip_a ip_b
  ip_a=$(compose exec -T frontend-nginx getent hosts backend-a | awk 'NR==1 {print $1}')
  ip_b=$(compose exec -T frontend-nginx getent hosts backend-b | awk 'NR==1 {print $1}')
  compose logs --no-color frontend-nginx > "$evidence/nginx-upstream.log" 2>&1
  grep -q "upstream_addr=${ip_a}:8080" "$evidence/nginx-upstream.log"
  grep -q "upstream_addr=${ip_b}:8080" "$evidence/nginx-upstream.log"
  printf 'backend-a=%s\nbackend-b=%s\nupstream_addr=verified\n' "$ip_a" "$ip_b" > "$evidence/upstream-addresses.txt"
}

primary_status='success'
set +e
(set -Eeuo pipefail; primary_flow)
primary_exit=$?
set -e
if [[ "$primary_exit" -ne 0 ]]; then
  primary_status='failed'
fi
primary_step=$(cat "$evidence/current-step.txt" 2>/dev/null || printf 'initialization')

recovery_status='success'
if [[ -f "$remote_root/outage-active" ]]; then
  if ! restore_redis; then
    recovery_status='failed'
  fi
fi

collect_logs

cleanup_status='success'
if ! cleanup_fixtures; then
  cleanup_status='failed'
fi

for pattern in \
  'manifest-*.json' \
  'verification-*.json' \
  'baseline-*.json' \
  '*-summary.json' \
  'k6-raw-*.json'; do
  find "$output" -maxdepth 1 -type f -name "$pattern" -exec cp {} "$evidence/" \;
done
rm -f "$output"/tokens-*.json

if ! scan_evidence_for_secrets; then
  primary_status='failed'
  primary_step='evidence-secret-scan'
fi

if ! compose down --remove-orphans --rmi local > "$evidence/stack-down.log" 2>&1; then
  cleanup_status='failed'
fi

cat > "$evidence/run-status.json" <<EOF
{
  "runId": "$run_id",
  "commit": "$commit",
  "primaryStatus": "$primary_status",
  "primaryStep": "$primary_step",
  "primaryExit": $primary_exit,
  "recoveryStatus": "$recovery_status",
  "cleanupStatus": "$cleanup_status"
}
EOF

final_success=true
if [[ "$primary_status" != success || "$recovery_status" != success || "$cleanup_status" != success ]]; then
  final_success=false
fi

if [[ "$final_success" == true ]]; then
  rm -f "$compose_env"
  rm -rf "$repo" "$remote_root/source.tar" "$output"
  exit 0
fi
# Keep the chmod-600 environment only on failure so an operator can restore Redis and clean fixtures.
exit 1

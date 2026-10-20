#!/usr/bin/env bash
# Official python:3.13.12-slim-bookworm index, registry-1.docker.io HEAD 2026-10-20.
# A preloaded matching image is accepted; never substitute a mutable tag.
STAGE5_PYTHON_IMAGE='python@sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6'

stage5_python_preflight() {
  local evidence=$1 identity
  timeout 15 docker version --format '{{.Server.Version}}' >/dev/null 2>&1 &&
    timeout 15 docker compose version >/dev/null 2>&1 || { printf 'Docker/Compose unavailable\n' >&2; return 1; }
  if ! timeout 15 docker image inspect "$STAGE5_PYTHON_IMAGE" >/dev/null 2>&1; then
    timeout --signal=TERM --kill-after=10 180 docker pull --quiet "$STAGE5_PYTHON_IMAGE" >/dev/null 2>&1 || {
      printf 'Pinned Python image unavailable; pull failed; explicitly preload matching digest or review connectivity\n' >&2; return 1;
    }
  fi
  identity=$(timeout 15 docker image inspect --format '{{.Id}}|{{json .RepoDigests}}' "$STAGE5_PYTHON_IMAGE") || return 1
  [[ $identity =~ ^sha256:[0-9a-f]{64}\| ]] || { printf 'Invalid Python image identity\n' >&2; return 1; }
  printf 'requested=%s\nresolved=%s\nuid=%s gid=%s\n' "$STAGE5_PYTHON_IMAGE" "$identity" "$(id -u)" "$(id -g)" > "$evidence/python-runtime.txt"
}

stage5_bridge_allowed() {
  local root=$1 project=$2; shift 2
  local -a args=("$@")
  local key value db='' redis_host='' redis_port='' redis_db=2 mysql_container='' redis_container='' arg index
  # Parse only non-secret identity fields. Never source dotenv or evaluate a request.
  while IFS='=' read -r key value || [[ -n $key ]]; do
    key=${key#$'\xef\xbb\xbf'}
    value=${value%$'\r'}
    case "$key" in DB_NAME) db=$value ;; REDIS_HOST) redis_host=$value ;; REDIS_PORT) redis_port=$value ;;
      REDIS_DATABASE) redis_db=$value ;; STAGE5_MYSQL_CONTAINER) mysql_container=$value ;; STAGE5_REDIS_CONTAINER) redis_container=$value ;; esac
  done < "$root/stage5.env"
  case "${args[0]:-}" in
    mysql)
      [[ ${args[*]} == 'mysql --version' || ${args[*]} == "mysql --defaults-extra-file=$root/private/mysql-private.cnf --connect-timeout=5 --batch --raw --skip-column-names $db" ]] ;;
    mysqldump)
      [[ ${args[*]} == 'mysqldump --version' ]] && return 0
      [[ ${args[1]:-} == "--defaults-extra-file=$root/private/mysql-private.cnf" && ${args[-1]} == "$db" ]] || return 1
      for arg in "${args[@]:2:${#args[@]}-3}"; do
        case "$arg" in --single-transaction|--skip-comments|--no-tablespaces|--set-gtid-purged=OFF|--hex-blob|--order-by-primary|--skip-extended-insert|--no-create-info|--skip-add-locks|--skip-disable-keys|--routines|--triggers) ;; *) return 1 ;; esac
      done ;;
    redis-cli)
      [[ ${args[*]} == 'redis-cli --version' ]] && return 0
      [[ ${args[*]:0:8} == "redis-cli --raw -h $redis_host -p $redis_port -n $redis_db" ]] || return 1
      case "${args[8]:-}" in
        PING) ((${#args[@]} == 9)) ;;
        --scan) [[ ${args[*]:8} == "--scan --pattern volunteerflow:$project:*" ]] ;;
        DEL)
          ((${#args[@]} >= 10 && ${#args[@]} <= 109)) || return 1
          for arg in "${args[@]:9}"; do [[ $arg == "volunteerflow:$project:"* && ! $arg =~ [[:space:]] ]] || return 1; done ;;
        *) return 1 ;;
      esac ;;
    docker)
      [[ ${args[*]} == 'docker compose version' || ${args[*]} == 'docker version --format {{.Server.Version}}' ]] && return 0
      if [[ ${args[1]:-} == inspect ]]; then
        [[ ${#args[@]} == 5 && ${args[2]} == --format &&
          ${args[3]} == '{"id":"{{.Id}}","name":"{{.Name}}","image":"{{.Config.Image}}","running":{{.State.Running}}}' &&
          ( ${args[4]} == "$mysql_container" || ${args[4]} == "$redis_container" ) ]]; return
      fi
      [[ ${args[*]:0:8} == "docker compose -p $project -f $root/repo/deploy/stage5/docker-compose.yml --env-file $root/stage5.env" &&
        ${args[8]:-} == exec && ${args[9]:-} == -T ]] || return 1
      case "${args[10]:-}" in
        backend-a|backend-b) [[ ${args[*]:11} == 'curl --config -' ]] ;;
        kafka)
          case "${args[11]:-}" in /opt/kafka/bin/kafka-topics.sh|/opt/kafka/bin/kafka-console-producer.sh|/opt/kafka/bin/kafka-consumer-groups.sh) ;; *) return 1 ;; esac
          for ((index=12; index<${#args[@]}; index++)); do
            case "${args[index]}" in --topic|--group) ((index+=1)); [[ ${args[index]:-} == "$project."* || ${args[index]:-} == "$project-"* ]] || return 1 ;; esac
          done ;;
        *) return 1 ;;
      esac ;;
    *) return 1 ;;
  esac
}

stage5_bridge_request() {
  local root=$1 project=$2 bridge=$3 remaining=${4:-300} code=1 auth='' request_timeout file
  local -a args=()
  [[ -f $bridge/request.ready && ! -L $bridge/request.ready ]] || return 1
  for file in argv stdin auth deadline; do [[ -f $bridge/$file && ! -L $bridge/$file ]] || return 1; done
  mapfile -d '' -t args < "$bridge/argv"
  request_timeout=$(< "$bridge/deadline")
  if [[ $request_timeout =~ ^[0-9]{1,3}$ && $remaining =~ ^[0-9]{1,3}$ ]] &&
      ((request_timeout>=1 && request_timeout<=300 && remaining>=1 && remaining<=600)) &&
      ((${#args[@]} <= 128)) && stage5_bridge_allowed "$root" "$project" "${args[@]}"; then
    ((request_timeout <= remaining)) || request_timeout=$remaining
    # Redis password is inherited by redis-cli only, not env(1)/Docker argv or logs.
    auth=$(< "$bridge/auth")
    if ( while IFS= read -r key; do export -n "$key"; done < <(compgen -e)
         export PATH STAGE5_RUN_COMPACT="${project#vf5}" LANG=C.UTF-8
         [[ ${args[0]} != redis-cli ]] || export REDISCLI_AUTH="$auth"
         timeout --signal=TERM --kill-after=5 "$request_timeout" "${args[@]}" < "$bridge/stdin" > "$bridge/stdout" 2> "$bridge/stderr" ); then code=0; else code=$?; fi
  else
    : > "$bridge/stdout"; : > "$bridge/stderr"
  fi
  rm -f -- "$bridge/request.ready"
  printf '%s\n' "$code" > "$bridge/response.tmp"
  mv -- "$bridge/response.tmp" "$bridge/response.ready"
}

stage5_python_driver() (
  umask 077
  local root=$1 script=$2 expected_hash=$3 run=$4 project=$5 action=$6 bridge="$1/private/bridge" cidfile="$1/python-$6.cid" pid rc=0 cid='' remaining action_deadline=$((SECONDS+600))
  [[ $expected_hash =~ ^[0-9a-f]{64}$ ]] &&
    printf '%s  %s\n' "$expected_hash" "$script" | sha256sum --check --status || { printf 'Acceptance driver hash mismatch\n' >&2; return 1; }
  [[ $action =~ ^(preflight|backup|migration-check|topics|seed|normal|outage|recover|duplicate|reconcile|metrics-before|metrics-after|cleanup|assert-clean|scan)$ ]] || return 2
  [[ ! -e $cidfile && ! -L $cidfile ]] || { printf 'Runtime container receipt already exists\n' >&2; return 1; }
  printf '%s  acceptance.py\n' "$expected_hash" > "$root/evidence/acceptance.sha256"
  mkdir -m 700 -- "$bridge" || return 1
  stage5_runtime_cleanup() {
    local original_rc=$? cleanup_status=success
    trap - EXIT
    set +e
    if [[ -f $cidfile && ! -L $cidfile ]]; then
      cid=$(< "$cidfile")
      # Remove only this invocation's exact container, including a killed Docker client.
      if [[ $cid =~ ^[a-f0-9]{64}$ ]]; then
        if timeout 15 docker container inspect "$cid" >/dev/null 2>&1; then
          timeout 20 docker rm -f "$cid" >/dev/null 2>&1 || cleanup_status=failed
        elif ! timeout 15 docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then cleanup_status=failed; fi
      else cleanup_status=failed; fi
    fi
    rm -f -- "$bridge/argv" "$bridge/stdin" "$bridge/auth" "$bridge/deadline" "$bridge/stdout" "$bridge/stderr" "$bridge/request.ready" "$bridge/response.tmp" "$bridge/response.ready" "$bridge/container.cid"
    rmdir -- "$bridge" || cleanup_status=failed
    [[ $cleanup_status != success ]] || rm -f -- "$cidfile"
    printf '%s\n' "$cleanup_status" > "$root/evidence/python-runtime-cleanup-$action.txt"
    ((original_rc != 0)) && exit "$original_rc"
    [[ $cleanup_status == success ]] || exit 1
    exit 0
  }
  trap stage5_runtime_cleanup EXIT
  timeout --signal=TERM --kill-after=10 600 docker run --rm --pull never --cidfile "$cidfile" \
    --name "$project-python-$action" --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
    --cpus 1 --memory 768m --pids-limit 64 --user "$(id -u):$(id -g)" --log-driver none \
    --mount "type=bind,src=$script,dst=/acceptance.py,readonly" \
    --mount "type=bind,src=$root/stage5.env,dst=$root/stage5.env,readonly" \
    --mount "type=bind,src=$root/private,dst=$root/private" \
    --mount "type=bind,src=$root/evidence,dst=$root/evidence" \
    "$STAGE5_PYTHON_IMAGE" python -B /acceptance.py --bridge "$bridge" --run-id "$run" \
      --root "$root" --env-file "$root/stage5.env" --project "$project" "$action" &
  pid=$!
  while kill -0 "$pid" 2>/dev/null; do
    remaining=$((action_deadline-SECONDS))
    if ((remaining<1)); then kill "$pid" 2>/dev/null; wait "$pid"; return 124; fi
    if [[ -f $bridge/request.ready ]]; then stage5_bridge_request "$root" "$project" "$bridge" "$remaining" || { kill "$pid" 2>/dev/null; wait "$pid"; return 1; }; fi
    sleep 0.05
  done
  wait "$pid" || rc=$?
  return "$rc"
)

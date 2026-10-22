#!/usr/bin/env bash
# Host-only broker. Transport is inherited kernel pipes, NEVER container paths.
stage5_require_root() { [[ $(id -u) == 0 ]] || { printf 'Stage5 requires the approved root host operator\n' >&2; return 1; }; }

stage5_validate_bash_version() {
  local value=$1
  ((${#value} <= 128)) && [[ $value != *$'\n'* && $value != *$'\r'* && $value != *$'\t'* &&
    ! $value =~ [[:cntrl:]] &&
    $value =~ ^[0-9]+\.[0-9]+\.[0-9]+\([0-9]+\)-[A-Za-z0-9._-]+$ ]]
}

stage5_require_host_tools() {
  local tool
  for tool in bash timeout docker mysql mysqldump sha256sum realpath tar id base64 head cmp chown mkfifo; do
    command -v "$tool" >/dev/null || {
      printf 'Missing deploy-essential tool: %s\n' "$tool" >&2
      return 1
    }
  done
}

stage5_publish_evidence() {
  local root=$1 name=$2 input=$3
  [[ $name =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,150}$ && ( $input == "$root/host-private/"* || $input == "$root/.evidence."* ) &&
     -f $input && ! -L $input && $(wc -c < "$input") -le 1048576 ]] || return 1
  chmod 444 "$input" && mv -fT -- "$input" "$root/evidence/$name"
}

stage5_capture_evidence() (
  umask 077
  local root=$1 name=$2 capture rc=0; shift 2
  capture=$(mktemp "$root/.evidence.XXXXXXXX") || return 1
  if (ulimit -f 1024; "$@") > "$capture" 2>&1; then rc=0; else rc=$?; fi
  stage5_publish_evidence "$root" "$name" "$capture" || return 1
  return "$rc"
)

stage5_env_value() {
  local env_file=$1 wanted=$2 key value
  while IFS='=' read -r key value || [[ -n $key ]]; do
    key=${key#$'\xef\xbb\xbf'}; value=${value%$'\r'}
    [[ $key != "$wanted" ]] || { printf '%s' "$value"; return 0; }
  done < "$env_file"
}

stage5_host_prepare() {
  stage5_require_root || return 1
  local root=$1 key value host='' port='' user='' password='' flyway='' maintenance=''
  [[ ! -e $root/host-private && ! -L $root/host-private ]] || return 1
  mkdir -m 700 -- "$root/host-private" "$root/host-private/empty-home" || return 1
  # These are host-owned original bytes, never writable or replaceable by Python.
  [[ $(wc -c < "$root/stage5.env") -le 65536 ]] &&
    LC_ALL=C tr -d '\000' < "$root/stage5.env" | cmp -s -- "$root/stage5.env" - || return 1
  while IFS='=' read -r key value || [[ -n $key ]]; do
    key=${key#$'\xef\xbb\xbf'}; value=${value%$'\r'}
    [[ -n $key && $key != \#* ]] || continue
    [[ $key =~ ^[A-Z][A-Z0-9_]*$ && ! $value =~ [[:space:][:cntrl:]] &&
       $value != *'"'* && $value != *"'"* && $value != *'\'* && $value != *'$'* && $value != *'#'* ]] || return 1
    case "$key" in DB_HOST) host=$value ;; DB_PORT) port=$value ;; DB_USERNAME) user=$value ;;
      DB_PASSWORD) password=$value ;; SPRING_FLYWAY_ENABLED) flyway=$value ;; STAGE5_MAINTENANCE_APPROVED) maintenance=$value ;; esac
  done < "$root/stage5.env"
  [[ -n $host && -n $user && -n $password && $port =~ ^[0-9]{1,5}$ && $flyway == false && $maintenance == true ]] || return 1
  printf '[client]\nhost="%s"\nport="%s"\nuser="%s"\npassword="%s"\n' "$host" "$port" "$user" "$password" > "$root/host-private/mysql.cnf"
  chmod 600 "$root/host-private/mysql.cnf"
  cp -- "$root/stage5.env" "$root/container.env"
  chmod 444 "$root/container.env"
  # Parent remains root:700; readable file mount does not expose its parent.
  chown 0:0 "$root/host-private" "$root/host-private/mysql.cnf" "$root/container.env"
  chown 65534:65534 "$root/private" "$root/evidence"
  chmod 700 "$root/private" "$root/evidence"
}

stage5_bridge_allowed() {
  local root=$1 project=$2; shift 2
  local -a args=("$@")
  local db redis_host redis_port redis_db mysql_container redis_container redis_id arg index
  db=$(stage5_env_value "$root/stage5.env" DB_NAME)
  redis_host=$(stage5_env_value "$root/stage5.env" REDIS_HOST)
  redis_port=$(stage5_env_value "$root/stage5.env" REDIS_PORT)
  redis_db=$(stage5_env_value "$root/stage5.env" REDIS_DATABASE); redis_db=${redis_db:-2}
  mysql_container=$(stage5_env_value "$root/stage5.env" STAGE5_MYSQL_CONTAINER)
  redis_container=$(stage5_env_value "$root/stage5.env" STAGE5_REDIS_CONTAINER)
  case "${args[0]:-}" in
    mysql)
      [[ ${args[*]} == 'mysql --version' || ${args[*]} == "mysql --defaults-file=$root/host-private/mysql.cnf --connect-timeout=5 --binary-mode=1 --local-infile=0 --batch --raw --skip-column-names $db" ]] ;;
    mysqldump)
      [[ ${args[*]} == 'mysqldump --version' ]] && return 0
      [[ ${args[1]:-} == "--defaults-file=$root/host-private/mysql.cnf" && ${args[-1]} == "$db" ]] || return 1
      for arg in "${args[@]:2:${#args[@]}-3}"; do
        case "$arg" in --single-transaction|--skip-comments|--no-tablespaces|--set-gtid-purged=OFF|--hex-blob|--order-by-primary|--skip-extended-insert|--no-create-info|--skip-add-locks|--skip-disable-keys|--routines|--triggers) ;; *) return 1 ;; esac
      done ;;
    redis-cli)
      [[ $redis_container =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,100}$ ]] || return 1
      [[ ${args[1]:-} == --container-id && ${args[2]:-} =~ ^[0-9a-f]{64}$ ]] || return 1
      redis_id=${args[2]}
      [[ ${args[*]} == "redis-cli --container-id $redis_id --version" ]] && return 0
      [[ ${args[*]:0:10} == "redis-cli --container-id $redis_id --raw -h $redis_host -p $redis_port -n $redis_db" ]] || return 1
      case "${args[10]:-}" in PING) ((${#args[@]}==11)) ;;
        --scan) [[ ${args[*]:10} == "--scan --pattern volunteerflow:$project:*" ]] ;;
        DEL) ((${#args[@]}>=12 && ${#args[@]}<=111)) || return 1
          for arg in "${args[@]:11}"; do [[ $arg == "volunteerflow:$project:"* && ! $arg =~ [[:space:]] ]] || return 1; done ;;
        *) return 1 ;; esac ;;
    docker)
      [[ ${args[*]} == 'docker compose version' || ${args[*]} == 'docker version --format {{.Server.Version}}' ]] && return 0
      if [[ ${args[1]:-} == inspect ]]; then
        [[ ${#args[@]} == 5 && ${args[2]} == --format &&
          ${args[3]} == '{"id":"{{.Id}}","name":"{{.Name}}","image":"{{.Config.Image}}","running":{{.State.Running}}}' &&
          ( ${args[4]} == "$mysql_container" || ${args[4]} == "$redis_container" ) ]]; return
      fi
      [[ ${args[*]:0:8} == "docker compose -p $project -f $root/repo/deploy/stage5/docker-compose.yml --env-file $root/stage5.env" && ${args[8]:-} == exec && ${args[9]:-} == -T ]] || return 1
      case "${args[10]:-}" in backend-a|backend-b) [[ ${args[*]:11} == 'curl --config -' ]] ;;
        *) return 1 ;; esac ;;
    *) return 1 ;;
  esac
}

# Format validation is separate from scope: every topic/group comparison below is
# literal equality, NEVER a Kafka regex selector or a caller-supplied CLI option.
stage5_project_safe() { [[ $1 =~ ^vf5[0-9a-f]{32}$ ]]; }

stage5_kafka_action() {
  local root=$1 project=$2 action=$3 input=$4 record key value; shift 4
  STAGE5_KAFKA_ARGS=(); STAGE5_KAFKA_LIMIT=30
  stage5_project_safe "$project" || return 1
  local -a prefix=(docker compose -p "$project" -f "$root/repo/deploy/stage5/docker-compose.yml" --env-file "$root/stage5.env" exec -T kafka)
  case "$action" in
    kafka-list-topics)
      (($#==0)) && [[ ! -s $input ]] || return 1
      STAGE5_KAFKA_ARGS=("${prefix[@]}" /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list) ;;
    kafka-describe-group)
      (($#==1)) && [[ $1 == "$project-notification" && ! -s $input ]] || return 1
      STAGE5_KAFKA_ARGS=("${prefix[@]}" /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --group "$project-notification" --describe) ;;
    kafka-produce-duplicate)
      (($#==1)) && [[ $1 == "$project.registration-events.v1" && $(wc -c < "$input") -le 131072 && $(wc -l < "$input") == 1 ]] || return 1
      LC_ALL=C tr -d '\000' < "$input" | cmp -s -- "$input" - || return 1
      record=$(< "$input")
      [[ $record != *$'\n'* && $record != *$'\r'* && $record == *$'\t'* ]] || return 1
      key=${record%%$'\t'*}; value=${record#*$'\t'}
      [[ $key =~ ^[0-9]{1,20}:[0-9]{1,20}$ && $value == \{*\} && $value != *$'\t'* ]] || return 1
      STAGE5_KAFKA_LIMIT=45
      STAGE5_KAFKA_ARGS=("${prefix[@]}" /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic "$project.registration-events.v1" --property parse.key=true --property $'key.separator=\t' --producer-property acks=all --producer-property delivery.timeout.ms=30000) ;;
    *) return 1 ;;
  esac
}

# Trusted orchestration only. No pipe action permits create/delete/alter/config.
stage5_prepare_topics() (
  stage5_require_root && stage5_project_safe "$2" || return 1
  local root=$1 project=$2 suffix topic key
  while IFS= read -r key; do export -n "$key"; done < <(compgen -e)
  export PATH LANG=C.UTF-8 HOME="$root/host-private/empty-home" STAGE5_RUN_COMPACT="${project#vf5}"
  for suffix in activity-events.v1 registration-events.v1 checkin-events.v1; do
    for topic in "$project.$suffix" "$project.$suffix.DLT"; do
      timeout --signal=TERM --kill-after=1 30 docker compose -p "$project" -f "$root/repo/deploy/stage5/docker-compose.yml" --env-file "$root/stage5.env" exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic "$topic" --partitions 1 --replication-factor 1 || return 1
    done
  done
)

stage5_pipe_read_frame() {
  local fd=$1 deadline=$2 char remaining count=0
  STAGE5_FRAME=''
  # -d '' makes NUL observable (empty character), instead of silently discarding it.
  while ((count<=196608)); do
    remaining=$((deadline-SECONDS)); ((remaining>0)) || return 1
    IFS= read -r -d '' -n 1 -t "$remaining" -u "$fd" char || return 1
    [[ -n $char ]] || return 1
    [[ $char != $'\n' ]] || return 0
    [[ $char =~ [[:print:]] ]] || return 1
    STAGE5_FRAME+=$char; ((count+=1))
  done
  return 1
}

stage5_pipe_decode() {
  local encoded=$1 destination=$2 cap=$3 canonical
  ((${#encoded} <= (cap+2)/3*4)) || return 1
  [[ $encoded =~ ^([A-Za-z0-9+/]{4})*([A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$ ]] || return 1
  printf '%s' "$encoded" | base64 --decode > "$destination" 2>/dev/null || return 1
  [[ $(wc -c < "$destination") -le $cap ]] || return 1
  canonical=$(base64 --wrap=0 "$destination") || return 1
  [[ $canonical == "$encoded" ]]
}

stage5_sql_safe() {
  local input=$1 code
  [[ $(wc -c < "$input") -le 131072 ]] || return 1
  LC_ALL=C tr -d '\000' < "$input" | cmp -s -- "$input" - || return 1
  # Driver SQL needs no backslash escapes; reject them anywhere, not only line start.
  if LC_ALL=C grep -q '\\' "$input"; then return 1; else code=$?; fi
  ((code==1)) || return 1
  # Reject BOM outright: mysql can strip it before recognizing client commands.
  if LC_ALL=C grep -Fq $'\xef\xbb\xbf' "$input"; then return 1; else code=$?; fi
  ((code==1)) || return 1
  # Conservatively reject client-command tokens even after comments, and the
  # DELIMITER prefix regardless of suffix (mysql recognizes its first 9 bytes).
  if LC_ALL=C grep -Eiq '(^|[^A-Za-z0-9_])(delimiter|((source|system|tee|pager|nopager|notee|edit|connect|charset|use)([^A-Za-z0-9_]|$)))|into[[:space:]]+(out|dump)file|load[[:space:]_]+(data|file)|install[[:space:]]+plugin' "$input"; then return 1; else code=$?; fi
  ((code==1))
}

stage5_pipe_command() (
  local root=$1 project=$2 work=$3 deadline=$4 requested=$5 output_cap=$6 key auth='' redis_id='' redis_container='' approved_id='' inspect_limit out_fifo err_fifo out_fd='' err_fd='' out_pid='' err_pid='' rc=0 remaining out_rc=1 err_rc=1
  shift 6; local -a args=("$@")
  out_fifo="$work/.stdout.pipe"
  err_fifo="$work/.stderr.pipe"
  stage5_pipe_command_cleanup() {
    local pid
    if [[ $out_fd =~ ^[0-9]+$ ]]; then exec {out_fd}>&- 2>/dev/null || true; fi
    if [[ $err_fd =~ ^[0-9]+$ ]]; then exec {err_fd}>&- 2>/dev/null || true; fi
    out_fd=''; err_fd=''
    for pid in "$out_pid" "$err_pid"; do
      [[ $pid =~ ^[0-9]+$ ]] || continue
      if kill -0 "$pid" 2>/dev/null; then kill "$pid" 2>/dev/null || true; fi
      wait "$pid" 2>/dev/null || true
    done
    rm -f -- "$out_fifo" "$err_fifo"
  }
  trap stage5_pipe_command_cleanup EXIT
  trap 'exit 1' HUP INT TERM
  remaining=$((deadline-SECONDS)); ((remaining>0)) || return 1
  ((requested<=remaining)) || requested=$remaining
  # Private FIFOs make both collectors ordinary children that remain directly
  # waitable by this shell on Bash 4.2 while still draining concurrently.
  [[ ! -e $out_fifo && ! -L $out_fifo && ! -e $err_fifo && ! -L $err_fifo ]] || return 1
  mkfifo -m 600 -- "$out_fifo" "$err_fifo" || return 1
  remaining=$((deadline-SECONDS)); ((remaining>0)) || return 1
  timeout --signal=TERM --kill-after=1 "$remaining" head -c "$((output_cap+1))" < "$out_fifo" > "$work/stdout" &
  out_pid=$!
  timeout --signal=TERM --kill-after=1 "$remaining" head -c 65537 < "$err_fifo" > "$work/stderr" &
  err_pid=$!
  exec {out_fd}> "$out_fifo" || return 1
  exec {err_fd}> "$err_fifo" || return 1
  if ( while IFS= read -r key; do export -n "$key"; done < <(compgen -e)
       export PATH LANG=C.UTF-8 HOME="$root/host-private/empty-home" STAGE5_RUN_COMPACT="${project#vf5}"
       if [[ ${args[0]} == redis-cli ]]; then
         redis_id=${args[2]}
         [[ ${args[1]:-} == --container-id && $redis_id =~ ^[0-9a-f]{64}$ ]] || exit 1
         redis_container=$(stage5_env_value "$root/stage5.env" STAGE5_REDIS_CONTAINER)
         [[ $redis_container =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,100}$ ]] || exit 1
         remaining=$((deadline-SECONDS)); ((remaining>0)) || exit 1
         inspect_limit=$remaining; ((inspect_limit<=15)) || inspect_limit=15
         approved_id=$(timeout --signal=TERM --kill-after=1 "$inspect_limit" docker inspect --format '{{.Id}}' "$redis_container" 2>/dev/null) || exit 1
         [[ $approved_id =~ ^[0-9a-f]{64}$ && $approved_id == "$redis_id" ]] || exit 1
         auth=$(stage5_env_value "$root/stage5.env" REDIS_PASSWORD)
         export REDISCLI_AUTH="$auth"
         args=(docker exec --env REDISCLI_AUTH "$redis_id" redis-cli "${args[@]:3}")
       fi
       remaining=$((deadline-SECONDS)); ((remaining>0)) || exit 1
       ((requested<=remaining)) || requested=$remaining
       timeout --signal=TERM --kill-after=1 "$requested" "${args[@]}" < "$work/stdin" >&"$out_fd" 2>&"$err_fd" ); then rc=0; else rc=$?; fi
  exec {out_fd}>&-; out_fd=''
  exec {err_fd}>&-; err_fd=''
  wait "$out_pid"; out_rc=$?; out_pid=''
  wait "$err_pid"; err_rc=$?; err_pid=''
  rm -f -- "$out_fifo" "$err_fifo"
  ((rc==0 && out_rc==0 && err_rc==0)) &&
    [[ $(wc -c < "$work/stdout") -le $output_cap && $(wc -c < "$work/stderr") -le 65536 ]]
)

stage5_pipe_serve() (
  umask 077
  stage5_require_root || return 1
  local root=$1 project=$2 read_fd=$3 write_fd=$4 deadline=$5 sequence=1 expected read_deadline work request_work remaining output_cap requested arg
  local -a fields=() args=()
  work=$(mktemp -d "$root/host-private/pipe.XXXXXXXX") || return 1
  # Nothing under this directory is mounted or writable/readable by the container.
  while ((sequence<=10000)); do
    ((SECONDS<deadline)) || return 1
    read_deadline=$((SECONDS+10)); ((read_deadline<=deadline)) || read_deadline=$deadline
    stage5_pipe_read_frame "$read_fd" "$read_deadline" || return 1
    IFS='|' read -r -a fields <<< "$STAGE5_FRAME|END"
    ((${#fields[@]}==8)) && [[ ${fields[0]} == VF5 && ${fields[1]} == 1 && ${fields[7]} == END ]] || return 1
    printf -v expected '%08d' "$sequence"
    [[ ${fields[2]} == "$expected" ]] || return 1
    if [[ ${fields[3]} == done ]]; then
      [[ ${fields[4]} == 0 && -z ${fields[5]} && -z ${fields[6]} ]] || return 1
      rmdir -- "$work" || return 1
      return 0
    fi
    [[ ${fields[4]} =~ ^[1-9][0-9]{0,2}$ ]] && ((fields[4]<=300)) || return 1
    request_work="$work/$expected"; mkdir -m 700 -- "$request_work" || return 1
    stage5_pipe_decode "${fields[5]}" "$request_work/argv" 8192 &&
      stage5_pipe_decode "${fields[6]}" "$request_work/stdin" 131072 || return 1
    args=()
    while IFS= read -r -d '' arg; do
      ((${#args[@]}<128)) || return 1
      args+=("$arg")
    done < "$request_work/argv"
    if ((${#args[@]})); then printf '%s\0' "${args[@]}" | cmp -s -- "$request_work/argv" - || return 1
    else [[ ! -s $request_work/argv ]] || return 1; fi
    requested=${fields[4]}
    case "${fields[3]}" in
      exec) ((${#args[@]}>=1)) && stage5_bridge_allowed "$root" "$project" "${args[@]}" || return 1 ;;
      kafka-list-topics|kafka-produce-duplicate|kafka-describe-group)
        stage5_kafka_action "$root" "$project" "${fields[3]}" "$request_work/stdin" "${args[@]}" || return 1
        ((requested<=STAGE5_KAFKA_LIMIT)) || requested=$STAGE5_KAFKA_LIMIT
        args=("${STAGE5_KAFKA_ARGS[@]}") ;;
      *) return 1 ;;
    esac
    [[ ${args[0]} != mysql ]] || stage5_sql_safe "$request_work/stdin" || return 1
    output_cap=16777216; [[ ${args[0]} != mysqldump ]] || output_cap=67108864
    stage5_pipe_command "$root" "$project" "$request_work" "$deadline" "$requested" "$output_cap" "${args[@]}" || return 1
    printf 'VF5|1|%s|result|0|' "$expected" > "$request_work/response"
    base64 --wrap=0 "$request_work/stdout" >> "$request_work/response" || return 1
    printf '|\n' >> "$request_work/response"
    remaining=$((deadline-SECONDS)); ((remaining>0)) || return 1
    timeout --signal=TERM --kill-after=1 "$remaining" cat "$request_work/response" >&"$write_fd" || return 1
    rm -f -- "$request_work/argv" "$request_work/stdin" "$request_work/stdout" "$request_work/stderr" "$request_work/response"
    rmdir -- "$request_work" || return 1
    ((sequence+=1))
  done
  return 1
)

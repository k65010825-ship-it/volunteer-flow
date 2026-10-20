#!/usr/bin/env bash
# Official python:3.13.12-slim-bookworm index, registry HEAD verified 2026-10-20.
STAGE5_PYTHON_IMAGE='python@sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6'
source "$(dirname "${BASH_SOURCE[0]}")/pipe-bridge.sh"

stage5_python_preflight() {
  local evidence=$1 identity
  stage5_require_root || return 1
  timeout 15 docker version --format '{{.Server.Version}}' >/dev/null 2>&1 &&
    timeout 15 docker compose version >/dev/null 2>&1 || { printf 'Docker/Compose unavailable\n' >&2; return 1; }
  if ! timeout 15 docker image inspect "$STAGE5_PYTHON_IMAGE" >/dev/null 2>&1; then
    timeout --signal=TERM --kill-after=10 180 docker pull --quiet "$STAGE5_PYTHON_IMAGE" >/dev/null 2>&1 || {
      printf 'Pinned Python image unavailable; explicitly preload matching digest or review connectivity\n' >&2; return 1;
    }
  fi
  identity=$(timeout 15 docker image inspect --format '{{.Id}}|{{json .RepoDigests}}' "$STAGE5_PYTHON_IMAGE") || return 1
  [[ $identity =~ ^sha256:[0-9a-f]{64}\| ]] || return 1
  # Called before any container exists, including standalone DLT scratch preflight.
  printf 'requested=%s\nresolved=%s\nhostUid=0 containerUid=65534 containerGid=65534\n' "$STAGE5_PYTHON_IMAGE" "$identity" > "$evidence/python-runtime.txt"
  chmod 444 "$evidence/python-runtime.txt"
}

stage5_python_driver() (
  umask 077
  stage5_require_root || return 1
  local root=$1 script=$2 expected_hash=$3 run=$4 project=$5 action=$6 cidfile="$1/python-$6.cid" pid='' rc=0 cid='' work read_fd write_fd source_fd action_deadline=$((SECONDS+600))
  [[ $expected_hash =~ ^[0-9a-f]{64}$ ]] &&
    printf '%s  %s\n' "$expected_hash" "$script" | sha256sum --check --status || { printf 'Acceptance driver hash mismatch\n' >&2; return 1; }
  [[ $action =~ ^(preflight|backup|migration-check|topics|seed|normal|outage|recover|duplicate|reconcile|metrics-before|metrics-after|cleanup|assert-clean|scan)$ ]] || return 2
  [[ ! -e $cidfile && ! -L $cidfile && -f $root/host-private/mysql.cnf && -f $root/container.env ]] || return 1
  work=$(mktemp -d "$root/host-private/runtime-$action.XXXXXXXX") || return 1
  printf '%s  acceptance.py\n' "$expected_hash" > "$work/acceptance.sha256"
  stage5_publish_evidence "$root" acceptance.sha256 "$work/acceptance.sha256" || return 1
  stage5_runtime_cleanup() {
    local original_rc=$? cleanup_status=success stop_deadline
    trap - EXIT; set +e
    if [[ -n $pid ]] && kill -0 "$pid" 2>/dev/null; then
      kill "$pid" 2>/dev/null; stop_deadline=$((SECONDS+10))
      while kill -0 "$pid" 2>/dev/null && ((SECONDS<stop_deadline)); do sleep 0.05; done
      kill -KILL "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
    fi
    if [[ -f $cidfile && ! -L $cidfile ]]; then
      cid=$(< "$cidfile")
      if [[ $cid =~ ^[a-f0-9]{64}$ ]]; then
        if timeout 15 docker container inspect "$cid" >/dev/null 2>&1; then
          timeout 20 docker rm -f "$cid" >/dev/null 2>&1 || cleanup_status=failed
        elif ! timeout 15 docker version --format '{{.Server.Version}}' >/dev/null 2>&1; then cleanup_status=failed; fi
      else cleanup_status=failed; fi
    fi
    [[ $cleanup_status != success ]] || rm -f -- "$cidfile"
    printf '%s\n' "$cleanup_status" > "$work/cleanup-status"
    # Atomic replacement never follows a container-created evidence leaf link.
    stage5_publish_evidence "$root" "python-runtime-cleanup-$action.txt" "$work/cleanup-status" || cleanup_status=failed
    ((original_rc!=0)) && exit "$original_rc"
    [[ $cleanup_status == success ]] || exit 1
    exit 0
  }
  trap stage5_runtime_cleanup EXIT
  coproc VF5_DRIVER {
    ulimit -f 1024
    timeout --signal=TERM --kill-after=10 600 docker run --rm -i --pull never --cidfile "$cidfile" \
      --name "$project-python-$action" --network none --read-only --cap-drop ALL --security-opt no-new-privileges \
      --cpus 1 --memory 768m --pids-limit 64 --user 65534:65534 --log-driver none \
      --mount "type=bind,src=$script,dst=/acceptance.py,readonly" \
      --mount "type=bind,src=$root/container.env,dst=$root/stage5.env,readonly" \
      --mount "type=bind,src=$root/private,dst=$root/private" \
      --mount "type=bind,src=$root/evidence,dst=$root/evidence" \
      "$STAGE5_PYTHON_IMAGE" python -B /acceptance.py --pipe --run-id "$run" \
        --root "$root" --env-file "$root/stage5.env" --project "$project" "$action" 2> "$work/docker.stderr"
  }
  pid=$VF5_DRIVER_PID
  exec {read_fd}<&"${VF5_DRIVER[0]}"; exec {write_fd}>&"${VF5_DRIVER[1]}"
  source_fd=${VF5_DRIVER[0]}; exec {source_fd}<&-
  source_fd=${VF5_DRIVER[1]}; exec {source_fd}>&-
  if stage5_pipe_serve "$root" "$project" "$read_fd" "$write_fd" "$action_deadline"; then rc=0; else rc=1; fi
  exec {read_fd}<&-; exec {write_fd}>&-
  ((rc==0)) || return "$rc"
  wait "$pid" || rc=$?
  pid=''
  return "$rc"
)

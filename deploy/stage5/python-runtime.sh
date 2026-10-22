#!/usr/bin/env bash
# Official python:3.13.12-slim-bookworm supply identities, verified 2026-10-22.
STAGE5_PYTHON_IMAGE='python@sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6'
STAGE5_PYTHON_OFFLINE_TAG='python:i-was-a-digest'
STAGE5_PYTHON_INDEX_DIGEST='sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6'
STAGE5_PYTHON_MANIFEST_DIGEST='sha256:3121f8b0804aa3698ab750d9a39ea4a42657a385c9b133722b915e55c51551a6'
STAGE5_PYTHON_CONFIG_DIGEST='sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243'
STAGE5_PYTHON_OFFLINE_ARCHIVE_SHA256='sha256:437e3a92118aa1e01954b202f11ac592d13da1105171a9fc480dc10fc2620f3b'
STAGE5_PYTHON_RUNTIME_IMAGE=''
source "$(dirname "${BASH_SOURCE[0]}")/pipe-bridge.sh"

stage5_python_inspect() {
  local output rc
  output=$(timeout 15 docker image inspect --format '{{.Id}}|{{.Os}}|{{.Architecture}}|{{json .RepoTags}}|{{json .RepoDigests}}' "$1" 2>/dev/null; rc=$?; printf '\034'; exit "$rc") || return 1
  [[ $output == *$'\034' ]] || return 1
  output=${output%$'\034'}
  [[ $output == *$'\n' ]] || return 1
  output=${output%$'\n'}
  [[ $output != *$'\n'* ]] || return 1
  printf '%s' "$output"
}

stage5_python_decode_identity() {
  local identity=$1 separators refs_re
  [[ -n $identity && $identity != *$'\n'* && $identity != *$'\r'* && $identity != *$'\t'* && $identity != *$'\177'* ]] || return 1
  separators=${identity//[^|]/}
  [[ ${#separators} == 4 ]] || return 1
  IFS='|' read -r STAGE5_PYTHON_INSPECT_ID STAGE5_PYTHON_INSPECT_OS STAGE5_PYTHON_INSPECT_ARCH \
    STAGE5_PYTHON_INSPECT_TAGS STAGE5_PYTHON_INSPECT_DIGESTS <<< "$identity"
  [[ $STAGE5_PYTHON_INSPECT_ID == "$STAGE5_PYTHON_CONFIG_DIGEST" &&
     $STAGE5_PYTHON_INSPECT_OS == linux && $STAGE5_PYTHON_INSPECT_ARCH == amd64 ]] || return 1
  refs_re='^\["[A-Za-z0-9._/@:+-]+"(,"[A-Za-z0-9._/@:+-]+")*\]$'
  [[ $STAGE5_PYTHON_INSPECT_TAGS == '[]' || $STAGE5_PYTHON_INSPECT_TAGS == null ||
     $STAGE5_PYTHON_INSPECT_TAGS =~ $refs_re ]] || return 1
  [[ $STAGE5_PYTHON_INSPECT_DIGESTS == '[]' || $STAGE5_PYTHON_INSPECT_DIGESTS == null ||
     $STAGE5_PYTHON_INSPECT_DIGESTS =~ $refs_re ]] || return 1
}

stage5_python_offline_identity_allowed() {
  case "$STAGE5_PYTHON_INSPECT_TAGS" in
    '["python:i-was-a-digest"]'|'["index.docker.io/library/python:i-was-a-digest"]'|'["docker.io/library/python:i-was-a-digest"]') ;;
    *) return 1 ;;
  esac
  [[ $STAGE5_PYTHON_INSPECT_DIGESTS == '[]' ||
     $STAGE5_PYTHON_INSPECT_DIGESTS == '["python@sha256:3121f8b0804aa3698ab750d9a39ea4a42657a385c9b133722b915e55c51551a6"]' ||
     $STAGE5_PYTHON_INSPECT_DIGESTS == '["index.docker.io/library/python@sha256:3121f8b0804aa3698ab750d9a39ea4a42657a385c9b133722b915e55c51551a6"]' ||
     $STAGE5_PYTHON_INSPECT_DIGESTS == '["docker.io/library/python@sha256:3121f8b0804aa3698ab750d9a39ea4a42657a385c9b133722b915e55c51551a6"]' ]] || return 1
}

stage5_python_preflight() {
  local evidence=$1 identity first second resolution
  stage5_require_root || return 1
  timeout 15 docker version --format '{{.Server.Version}}' >/dev/null 2>&1 &&
    timeout 15 docker compose version >/dev/null 2>&1 || { printf 'Docker/Compose unavailable\n' >&2; return 1; }
  if identity=$(stage5_python_inspect "$STAGE5_PYTHON_IMAGE"); then
    stage5_python_decode_identity "$identity" || { printf 'Pinned Python image identity rejected\n' >&2; return 1; }
    resolution=official-digest
  elif first=$(stage5_python_inspect "$STAGE5_PYTHON_OFFLINE_TAG"); then
    second=$(stage5_python_inspect "$STAGE5_PYTHON_OFFLINE_TAG") || {
      printf 'Offline Python image identity rejected\n' >&2; return 1;
    }
    [[ $first == "$second" ]] && stage5_python_decode_identity "$first" &&
      stage5_python_offline_identity_allowed || {
        printf 'Offline Python image identity rejected\n' >&2; return 1;
      }
    identity=$first
    resolution=verified-offline-tag
  else
    timeout --signal=TERM --kill-after=10 180 docker pull --quiet "$STAGE5_PYTHON_IMAGE" >/dev/null 2>&1 || {
      printf 'Pinned Python image unavailable; explicitly preload matching digest or review connectivity\n' >&2; return 1;
    }
    identity=$(stage5_python_inspect "$STAGE5_PYTHON_IMAGE") &&
      stage5_python_decode_identity "$identity" || {
        printf 'Pinned Python image identity rejected\n' >&2; return 1;
      }
    resolution=official-digest
  fi
  STAGE5_PYTHON_RUNTIME_IMAGE=$STAGE5_PYTHON_INSPECT_ID
  # Called before any container exists, including standalone DLT scratch preflight.
  printf 'requested=%s\nresolution=%s\nupstreamIndexDigest=%s\nlinuxAmd64ManifestDigest=%s\nexpectedImageConfigDigest=%s\nofflineArchiveSha256=%s\nruntimeImageId=%s\nobservedRepoTags=%s\nobservedRepoDigests=%s\nhostUid=0 containerUid=65534 containerGid=65534\n' \
    "$STAGE5_PYTHON_IMAGE" "$resolution" "$STAGE5_PYTHON_INDEX_DIGEST" \
    "$STAGE5_PYTHON_MANIFEST_DIGEST" "$STAGE5_PYTHON_CONFIG_DIGEST" \
    "$STAGE5_PYTHON_OFFLINE_ARCHIVE_SHA256" "$STAGE5_PYTHON_RUNTIME_IMAGE" \
    "$STAGE5_PYTHON_INSPECT_TAGS" "$STAGE5_PYTHON_INSPECT_DIGESTS" > "$evidence/python-runtime.txt"
  chmod 444 "$evidence/python-runtime.txt"
}

stage5_python_driver() (
  umask 077
  stage5_require_root || return 1
  local root=$1 script=$2 expected_hash=$3 run=$4 project=$5 action=$6 cidfile="$1/python-$6.cid" pid='' rc=0 cid='' work read_fd write_fd source_fd action_deadline=$((SECONDS+600))
  [[ $expected_hash =~ ^[0-9a-f]{64}$ ]] &&
    printf '%s  %s\n' "$expected_hash" "$script" | sha256sum --check --status || { printf 'Acceptance driver hash mismatch\n' >&2; return 1; }
  [[ $STAGE5_PYTHON_RUNTIME_IMAGE == "$STAGE5_PYTHON_CONFIG_DIGEST" ]] || {
    printf 'Approved Python runtime image unavailable\n' >&2; return 1;
  }
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
      "$STAGE5_PYTHON_RUNTIME_IMAGE" python -B /acceptance.py --pipe --run-id "$run" \
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

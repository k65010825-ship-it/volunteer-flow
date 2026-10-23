#!/usr/bin/env bash
# Service-free behavior contract for stage5_pipe_command collector ownership.
# Run with the Bash executable being qualified. Set STAGE5_REQUIRE_BASH42=1
# when the target runtime must be exactly Bash 4.2.

set -u

bridge=${1:-}
[[ -n $bridge && -f $bridge && ! -L $bridge ]] || {
  printf 'FAIL collector_harness_usage\n' >&2
  exit 2
}

if [[ ${STAGE5_REQUIRE_BASH42:-0} == 1 ]] &&
   ! ((BASH_VERSINFO[0] == 4 && BASH_VERSINFO[1] == 2)); then
  printf 'FAIL unsupported_test_runtime\n' >&2
  printf 'TEST_RUNTIME_BASH=%s\n' "$BASH_VERSION"
  exit 2
fi

test_root=$(mktemp -d "${TMPDIR:-/tmp}/volunteerflow-stage5-collector.XXXXXXXX") || exit 2
case $(uname -s) in
  CYGWIN*|MINGW*|MSYS*) ;;
  *) chmod 700 "$test_root" && [[ $(stat -c '%a' "$test_root") == 700 ]] || exit 2 ;;
esac
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT

# shellcheck source=pipe-bridge.sh
source "$bridge"

mkdir -- "$test_root/host-private" "$test_root/host-private/empty-home" "$test_root/bin" || exit 2
case $(uname -s) in
  CYGWIN*|MINGW*|MSYS*) ;;
  *) chmod 700 "$test_root/host-private" "$test_root/host-private/empty-home" "$test_root/bin" || exit 2 ;;
esac
project=vf5aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
failures=0

cat > "$test_root/bin/collector-fixture" <<'FIXTURE'
#!/usr/bin/env bash
set -u
case ${1:-} in
  fast)
    printf 'fixed-stdout'
    printf 'fixed-stderr' >&2
    ;;
  bigout)
    head -c 16777217 /dev/zero
    ;;
  bigerr)
    head -c 65537 /dev/zero | tr '\000' E >&2
    ;;
  hang)
    printf '%s\n' "$$" > "$2"
    exec sleep 30
    ;;
  *)
    exit 2
    ;;
esac
FIXTURE
chmod 700 "$test_root/bin/collector-fixture" || exit 2
cat > "$test_root/bin/docker" <<'DOCKER'
#!/usr/bin/env bash
if [[ ${1:-} == inspect && ${2:-} == --format && ${3:-} == '{{.Id}}' && ${4:-} == redis-test ]]; then
  printf '%064d\n' 0 | tr '0' b
  exit 0
fi
exit 2
DOCKER
chmod 700 "$test_root/bin/docker" || exit 2
cat > "$test_root/stage5.env" <<'ENV'
STAGE5_REDIS_CONTAINER=redis-test
REDIS_PASSWORD=collector-private-marker
ENV
PATH="$test_root/bin:$PATH"

new_work() {
  local name=$1
  TEST_WORK="$test_root/$name"
  mkdir -- "$TEST_WORK" || return 1
  case $(uname -s) in
    CYGWIN*|MINGW*|MSYS*) ;;
    *) chmod 700 "$TEST_WORK" || return 1 ;;
  esac
  : > "$TEST_WORK/stdin"
}

record_pass() {
  printf 'PASS %s\n' "$1"
}

record_fail() {
  printf 'FAIL %s\n' "$1" >&2
  failures=$((failures+1))
}

fast_ok=1
iteration=1
while ((iteration <= 8)); do
  new_work "fast-$iteration" || exit 2
  deadline=$((SECONDS+5))
  if ! stage5_pipe_command "$test_root" "$project" "$TEST_WORK" "$deadline" 2 32 collector-fixture fast; then
    fast_ok=0
    break
  fi
  [[ $(< "$TEST_WORK/stdout") == fixed-stdout && $(< "$TEST_WORK/stderr") == fixed-stderr &&
     $(wc -c < "$TEST_WORK/stdout") -le 32 && $(wc -c < "$TEST_WORK/stderr") -le 65536 ]] || {
    fast_ok=0
    break
  }
  iteration=$((iteration+1))
done
if ((fast_ok)); then
  record_pass collector_fast_stdout_stderr_concurrent
else
  record_fail collector_fast_stdout_stderr_concurrent
fi

new_work bigout || exit 2
if stage5_pipe_command "$test_root" "$project" "$TEST_WORK" "$((SECONDS+8))" 5 16777216 collector-fixture bigout; then
  record_fail collector_stdout_cap_is_hard
elif [[ $(wc -c < "$TEST_WORK/stdout") -le 16777217 ]]; then
  record_pass collector_stdout_cap_is_hard
else
  record_fail collector_stdout_cap_is_hard
fi

new_work bigerr || exit 2
if stage5_pipe_command "$test_root" "$project" "$TEST_WORK" "$((SECONDS+5))" 2 32 collector-fixture bigerr; then
  record_fail collector_stderr_cap_is_hard
elif [[ $(wc -c < "$TEST_WORK/stderr") -le 65537 ]]; then
  record_pass collector_stderr_cap_is_hard
else
  record_fail collector_stderr_cap_is_hard
fi

new_work hang || exit 2
hang_pid_file="$TEST_WORK/hang.pid"
started=$SECONDS
if stage5_pipe_command "$test_root" "$project" "$TEST_WORK" "$((SECONDS+4))" 1 32 collector-fixture hang "$hang_pid_file"; then
  record_fail collector_deadline_fails_closed
else
  elapsed=$((SECONDS-started))
  hang_pid=''
  [[ -f $hang_pid_file ]] && IFS= read -r hang_pid < "$hang_pid_file"
  if ((elapsed <= 4)) && [[ $hang_pid =~ ^[0-9]+$ ]] && ! kill -0 "$hang_pid" 2>/dev/null; then
    record_pass collector_deadline_fails_closed
  else
    [[ ! $hang_pid =~ ^[0-9]+$ ]] || kill "$hang_pid" 2>/dev/null || true
    record_fail collector_deadline_fails_closed
  fi
fi

new_work redis-mismatch || exit 2
started=$SECONDS
other_id=$(printf '%064d' 0 | tr '0' c)
if stage5_pipe_command "$test_root" "$project" "$TEST_WORK" "$((SECONDS+4))" 2 32 \
    redis-cli --container-id "$other_id" --version; then
  record_fail collector_early_rejection_is_bounded
elif ((SECONDS-started <= 4)) && [[ ! -e $TEST_WORK/.stdout.pipe && ! -e $TEST_WORK/.stderr.pipe ]]; then
  record_pass collector_early_rejection_is_bounded
else
  record_fail collector_early_rejection_is_bounded
fi

# Exercise the real TERM -> EXIT cleanup while both direct collectors are
# still alive after observing EOF. The kill shim delegates ownership probes
# but records any numeric-PID signal attempt, so this test detects cleanup
# that treats PID existence as proof of child ownership.
new_work signal-cleanup || exit 2
collector_base_path=$PATH
slow_bin="$test_root/slow-bin"
mkdir -- "$slow_bin" || exit 2
case $(uname -s) in
  CYGWIN*|MINGW*|MSYS*) ;;
  *) chmod 700 "$slow_bin" || exit 2 ;;
esac
real_head=$(command -v head) || exit 2
cat > "$slow_bin/head" <<'HEAD'
#!/usr/bin/env bash
set -u
"$COLLECTOR_TEST_REAL_HEAD" "$@"
rc=$?
printf '%s\n' "$BASHPID" > "$COLLECTOR_TEST_HEAD_READY.$BASHPID"
stop=$((SECONDS+30))
while ((SECONDS < stop)); do :; done
exit "$rc"
HEAD
chmod 700 "$slow_bin/head" || exit 2
cleanup_kill_log="$TEST_WORK/cleanup-kill.log"
slow_head_ready="$TEST_WORK/head-ready"
kill() {
  if [[ ${1:-} == -0 ]]; then
    builtin kill "$@"
    return
  fi
  printf '%s\n' "$*" >> "$cleanup_kill_log"
  return 0
}
wait() {
  if [[ ${COLLECTOR_TEST_SIGNAL_ON_WAIT:-0} == 1 ]]; then
    unset -f wait
    builtin kill -TERM "$BASHPID"
    return 143
  fi
  builtin wait "$@"
}
started=$SECONDS
COLLECTOR_TEST_REAL_HEAD="$real_head" \
COLLECTOR_TEST_HEAD_READY="$slow_head_ready" \
COLLECTOR_TEST_SIGNAL_ON_WAIT=1 \
PATH="$slow_bin:$PATH" \
stage5_pipe_command "$test_root" "$project" "$TEST_WORK" "$((SECONDS+4))" 2 32 \
    collector-fixture fast
cleanup_rc=$?
unset -f kill
unset -f wait
elapsed=$((SECONDS-started))
signal_cleanup_ok=1
living_collectors=0
[[ $cleanup_rc -ne 0 && $elapsed -le 6 && ! -s $cleanup_kill_log &&
   ! -e $TEST_WORK/.stdout.pipe && ! -e $TEST_WORK/.stderr.pipe ]] || signal_cleanup_ok=0
observed_collectors=0
for ready_file in "$slow_head_ready".*; do
  [[ -f $ready_file ]] || continue
  observed_collectors=$((observed_collectors+1))
  IFS= read -r slow_head_pid < "$ready_file"
  if [[ $slow_head_pid =~ ^[0-9]+$ ]] && builtin kill -0 "$slow_head_pid" 2>/dev/null; then
    signal_cleanup_ok=0
    living_collectors=$((living_collectors+1))
  fi
done
((observed_collectors == 2)) || signal_cleanup_ok=0
if ((signal_cleanup_ok)); then
  record_pass collector_signal_cleanup_waits_without_blind_kill
else
  kill_bytes=0
  [[ ! -f $cleanup_kill_log ]] || kill_bytes=$(wc -c < "$cleanup_kill_log")
  printf 'SIGNAL_CLEANUP_DEBUG rc=%s elapsed=%s kill_bytes=%s living=%s fifo_out=%s fifo_err=%s\n' \
    "$cleanup_rc" "$elapsed" "$kill_bytes" "$living_collectors" \
    "$([[ -e $TEST_WORK/.stdout.pipe ]] && printf present || printf absent)" \
    "$([[ -e $TEST_WORK/.stderr.pipe ]] && printf present || printf absent)" >&2
  record_fail collector_signal_cleanup_waits_without_blind_kill
fi

# Signal the real function immediately before its first writer open. On the
# unsafe implementation both asynchronous children are still blocked opening
# their FIFO readers, so `timeout` has not started and EXIT cleanup hangs.
new_work startup-signal || exit 2
startup_worker="$test_root/startup-signal-worker.sh"
cat > "$startup_worker" <<'WORKER'
#!/usr/bin/env bash
set -uT
bridge=$1
root=$2
project=$3
work=$4
source "$bridge"
worker_pid=$BASHPID
stage_owner=''
startup_signal_before_writer() {
  local command=$BASH_COMMAND ready_count ready_file wait_deadline
  if [[ -z $stage_owner && $BASHPID != "$worker_pid" ]]; then
    stage_owner=$BASHPID
  fi
  if [[ -n $stage_owner && $BASHPID != "$stage_owner" ]]; then
    case $command in
      exec*)
        : > "$work/child-ready.$BASHPID"
        while [[ ! -e $work/release-children ]]; do sleep 0.02; done
        trap - DEBUG
        ;;
    esac
  elif [[ -n $stage_owner && $BASHPID == "$stage_owner" ]]; then
    case $command in
      exec*'{out_fd}'*)
        wait_deadline=$((SECONDS+2))
        while ((SECONDS <= wait_deadline)); do
          ready_count=0
          for ready_file in "$work"/child-ready.*; do
            [[ -f $ready_file ]] && ready_count=$((ready_count+1))
          done
          ((ready_count == 2)) && break
          sleep 0.02
        done
        : > "$work/parent-entered-cleanup"
        trap - DEBUG
        builtin kill -TERM "$BASHPID"
        ;;
    esac
  fi
}
trap startup_signal_before_writer DEBUG
stage5_pipe_command "$root" "$project" "$work" "$((SECONDS+3))" 2 32 \
  collector-fixture fast
WORKER
chmod 700 "$startup_worker" || exit 2
started=$SECONDS
PATH="$collector_base_path" timeout --signal=KILL 8 bash "$startup_worker" "$bridge" "$test_root" "$project" "$TEST_WORK" &
startup_timeout_pid=$!
release_deadline=$((SECONDS+4))
while ((SECONDS <= release_deadline)); do
  [[ -e $TEST_WORK/parent-entered-cleanup ]] && break
  builtin kill -0 "$startup_timeout_pid" 2>/dev/null || break
  sleep 0.02
done
[[ ! -e $TEST_WORK/parent-entered-cleanup ]] || : > "$TEST_WORK/release-children"
if wait "$startup_timeout_pid"; then startup_rc=0; else startup_rc=$?; fi
elapsed=$((SECONDS-started))
startup_children=0
for ready_file in "$TEST_WORK"/child-ready.*; do
  [[ -f $ready_file ]] && startup_children=$((startup_children+1))
done
if [[ $startup_rc -ne 0 && $elapsed -le 6 && $startup_children -eq 2 &&
      -e $TEST_WORK/parent-entered-cleanup &&
      ! -e $TEST_WORK/.stdout.pipe && ! -e $TEST_WORK/.stderr.pipe ]]; then
  record_pass collector_startup_signal_remains_deadline_bounded
else
  printf 'STARTUP_CLEANUP_DEBUG rc=%s elapsed=%s children=%s cleanup_marker=%s fifo_out=%s fifo_err=%s\n' \
    "$startup_rc" "$elapsed" "$startup_children" \
    "$([[ -e $TEST_WORK/parent-entered-cleanup ]] && printf present || printf absent)" \
    "$([[ -e $TEST_WORK/.stdout.pipe ]] && printf present || printf absent)" \
    "$([[ -e $TEST_WORK/.stderr.pipe ]] && printf present || printf absent)" >&2
  record_fail collector_startup_signal_remains_deadline_bounded
fi

printf 'TEST_RUNTIME_BASH=%s\n' "$BASH_VERSION"
((failures==0))

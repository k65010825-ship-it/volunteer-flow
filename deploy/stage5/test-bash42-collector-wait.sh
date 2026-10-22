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

printf 'TEST_RUNTIME_BASH=%s\n' "$BASH_VERSION"
((failures==0))

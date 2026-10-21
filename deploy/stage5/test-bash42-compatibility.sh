#!/usr/bin/env bash
# Offline behavior contract for the Stage 5 NUL-delimited argv decoder.
# Run this script with the Bash executable whose compatibility is being tested.

set -u

bridge=${1:-}
[[ -n $bridge && -f $bridge && ! -L $bridge ]] || {
  printf 'usage: bash test-bash42-compatibility.sh /path/to/pipe-bridge.sh\n' >&2
  exit 2
}

test_root=$(mktemp -d "${TMPDIR:-/tmp}/volunteerflow-stage5-bash42.XXXXXXXX") || exit 2
cleanup() {
  rm -rf -- "$test_root"
}
trap cleanup EXIT

# shellcheck source=pipe-bridge.sh
source "$bridge"

# The decoder is the unit under test. These doubles prevent every external
# service call while retaining the real frame, base64, argv and canonical-NUL
# validation path in stage5_pipe_serve.
stage5_require_root() { return 0; }
stage5_bridge_allowed() { return 0; }
stage5_pipe_command() {
  local work=$3
  shift 6
  printf '%s\0' "$@" > "$test_root/executed.argv"
  : > "$work/stdout"
  : > "$work/stderr"
}

project=vf5aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa
failures=0

write_frame() {
  local argv_file=$1 frame_file=$2 encoded
  encoded=$(base64 --wrap=0 "$argv_file") || return 1
  printf 'VF5|1|00000001|exec|3|%s|\nVF5|1|00000002|done|0||\n' "$encoded" > "$frame_file"
}

run_bridge() {
  local case_root=$1
  mkdir -- "$case_root/host-private" || return 2
  stage5_pipe_serve "$case_root" "$project" 0 1 "$((SECONDS+5))" \
    < "$case_root/frames" > "$case_root/response"
}

happy=$test_root/happy
mkdir -- "$happy"
printf 'mysql\0--version\0\0tail\0' > "$happy/argv"
write_frame "$happy/argv" "$happy/frames" || exit 2
rm -f -- "$test_root/executed.argv"
if run_bridge "$happy"; then
  printf 'mysql\0--version\0\0tail\0' > "$happy/expected"
  if cmp -s -- "$happy/expected" "$test_root/executed.argv"; then
    printf 'PASS bash42_nul_argv_preserves_empty_argument\n'
  else
    printf 'FAIL bash42_nul_argv_preserves_empty_argument\n' >&2
    failures=$((failures+1))
  fi
else
  printf 'FAIL bash42_nul_argv_preserves_empty_argument\n' >&2
  failures=$((failures+1))
fi

unterminated=$test_root/unterminated
mkdir -- "$unterminated"
printf 'mysql\0--version' > "$unterminated/argv"
write_frame "$unterminated/argv" "$unterminated/frames" || exit 2
rm -f -- "$test_root/executed.argv"
if run_bridge "$unterminated"; then
  printf 'FAIL bash42_missing_terminal_nul_is_rejected\n' >&2
  failures=$((failures+1))
elif [[ ! -e $test_root/executed.argv ]]; then
  printf 'PASS bash42_missing_terminal_nul_is_rejected\n'
else
  printf 'FAIL bash42_missing_terminal_nul_is_rejected\n' >&2
  failures=$((failures+1))
fi

over_limit=$test_root/over-limit
mkdir -- "$over_limit"
: > "$over_limit/argv"
index=0
while ((index<129)); do
  printf 'x\0' >> "$over_limit/argv"
  index=$((index+1))
done
write_frame "$over_limit/argv" "$over_limit/frames" || exit 2
rm -f -- "$test_root/executed.argv"
if run_bridge "$over_limit"; then
  printf 'FAIL bash42_more_than_128_arguments_is_rejected\n' >&2
  failures=$((failures+1))
elif [[ ! -e $test_root/executed.argv ]]; then
  printf 'PASS bash42_more_than_128_arguments_is_rejected\n'
else
  printf 'FAIL bash42_more_than_128_arguments_is_rejected\n' >&2
  failures=$((failures+1))
fi

printf 'TEST_RUNTIME_BASH=%s\n' "$BASH_VERSION"
((failures==0))

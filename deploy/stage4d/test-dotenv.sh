#!/usr/bin/env bash
set -Eeuo pipefail

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
source "$script_dir/lib/dotenv.sh"

[[ "$(decode_quoted_env_value 'plain')" == 'plain' ]]
[[ "$(decode_quoted_env_value 'a\\b')" == 'a\b' ]]
[[ "$(decode_quoted_env_value "a\\'b")" == "a'b" ]]
if decode_quoted_env_value "trailing\\" >/dev/null; then
  exit 1
fi
if decode_quoted_env_value 'illegal\x' >/dev/null; then
  exit 1
fi

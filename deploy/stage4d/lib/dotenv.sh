#!/usr/bin/env bash

decode_quoted_env_value() {
  local input="$1" output='' char escaped index length=${#1}
  for ((index = 0; index < length; index++)); do
    char=${input:index:1}
    if [[ "$char" != "\\" ]]; then
      output+="$char"
      continue
    fi
    ((index += 1))
    ((index < length)) || return 1
    escaped=${input:index:1}
    if [[ "$escaped" != "$char" && "$escaped" != "'" ]]; then
      return 1
    fi
    output+="$escaped"
  done
  printf '%s' "$output"
}

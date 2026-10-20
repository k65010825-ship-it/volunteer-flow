#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
project='' compose_file='' env_file='' topic='' partition='' start='' end='' dry=false confirm=''
while (($#)); do
  case "$1" in
    --dry-run) dry=true; shift ;;
    --project|--compose-file|--env-file|--topic|--partition|--start-offset|--end-offset|--confirm)
      (($# >= 2)) || exit 2
      case "$1" in
        --project) project=$2 ;; --compose-file) compose_file=$2 ;; --env-file) env_file=$2 ;;
        --topic) topic=$2 ;; --partition) partition=$2 ;; --start-offset) start=$2 ;;
        --end-offset) end=$2 ;; --confirm) confirm=$2 ;;
      esac; shift 2 ;;
    *) printf 'Unknown replay argument\n' >&2; exit 2 ;;
  esac
done
[[ $project =~ ^vf5[0-9a-f]{32}$ && -n $compose_file && -n $env_file ]] || exit 2
[[ $topic =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,200}\.DLT$ ]] || exit 2
for n in "$partition" "$start" "$end"; do [[ $n =~ ^(0|[1-9][0-9]{0,9})$ ]] && ((n <= 2147483647)) || exit 2; done
((end > start && end - start <= 1000)) || { printf 'Range must contain 1..1000 records\n' >&2; exit 2; }
target=${topic%.DLT}
if [[ $dry == true ]]; then printf 'DRY_RUN %s partition=%s [%s,%s) -> %s; original key/eventId preserved\n' "$topic" "$partition" "$start" "$end" "$target"; exit 0; fi
expected="${topic}:${partition}:${start}:${end}"
[[ $confirm == "$expected" ]] || { printf 'Explicit --confirm topic:partition:start:end required\n' >&2; exit 2; }
[[ -f $compose_file && -f $env_file ]] || exit 2
compose() { timeout 60 docker compose -p "$project" -f "$compose_file" --env-file "$env_file" "$@"; }
scratch=$(mktemp -d)
trap 'rm -f -- "$scratch/raw" "$scratch/records"; rmdir -- "$scratch"' EXIT
# No group offsets are reset or committed. Sparse/compacted ranges fail closed.
compose exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 \
  --topic "$topic" --partition "$partition" --offset "$start" --max-messages "$((end-start))" \
  --timeout-ms 15000 --consumer-property enable.auto.commit=false \
  --property print.offset=true --property print.key=true --property key.separator=$'\t' > "$scratch/raw"
python3 - "$scratch/raw" "$scratch/records" "$start" "$end" <<'PY'
import json, pathlib, sys, uuid
source, dest, start, end = sys.argv[1:]
rows = pathlib.Path(source).read_text().splitlines()
if len(rows) != int(end)-int(start): raise SystemExit('Incomplete replay range')
safe=[]
for offset, row in enumerate(rows, int(start)):
    parts=row.split('\t',2)
    if len(parts)!=3 or parts[0] != 'Offset:'+str(offset): raise SystemExit('Noncontiguous replay range')
    key, payload=parts[1:]
    event=json.loads(payload)
    uuid.UUID(event['eventId'])
    if key != str(event['organizationId'])+':'+str(event['activityId']): raise SystemExit('Invalid original key')
    safe.append(key+'\t'+payload)
pathlib.Path(dest).write_text('\n'.join(safe)+'\n')
PY
compose exec -T kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 \
  --topic "$target" --property parse.key=true --property key.separator=$'\t' \
  --producer-property acks=all --producer-property delivery.timeout.ms=30000 \
  < "$scratch/records"
printf 'REPLAYED %s partition=%s [%s,%s); verify consumed_event and notification before repeating\n' "$topic" "$partition" "$start" "$end"

#!/usr/bin/env bash
set -Eeuo pipefail
umask 077
run_id='' remote_root='' compose_project='' compose_env='' commit='' live=false migration=false
while (($#)); do
  case "$1" in
    --authorize-live-run) live=true; shift ;; --migration-reviewed) migration=true; shift ;;
    --run-id|--remote-root|--compose-project|--compose-env|--commit)
      (($#>=2)) || exit 2
      case "$1" in --run-id) run_id=$2 ;; --remote-root) remote_root=$2 ;; --compose-project) compose_project=$2 ;; --compose-env) compose_env=$2 ;; --commit) commit=$2 ;; esac
      shift 2 ;;
    *) exit 2 ;;
  esac
done
[[ $live == true ]] || { printf 'Explicit live authorization required\n' >&2; exit 2; }
[[ $run_id =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]] || exit 2
[[ $remote_root == "/opt/volunteerflow-stage5/$run_id" && $compose_project == "vf5${run_id//-/}" ]] || exit 2
[[ $compose_env == "$remote_root/stage5.env" && $commit =~ ^[a-f0-9]{40}$ ]] || exit 2
[[ $(realpath -m "$remote_root") == "$remote_root" && ! -L $remote_root && -f $compose_env ]] || exit 2
repo="$remote_root/repo" evidence="$remote_root/evidence"
compose_file="$repo/deploy/stage5/docker-compose.yml"
mkdir -p "$evidence"
chmod 700 "$remote_root" "$evidence"
chmod 600 "$compose_env"
export STAGE5_RUN_COMPACT=${run_id//-/}
compose() { current_step="compose:$1"; env -i PATH="$PATH" STAGE5_RUN_COMPACT="$STAGE5_RUN_COMPACT" timeout 900 docker compose -p "$compose_project" -f "$compose_file" --env-file "$compose_env" "$@"; }
driver() { current_step=$1; timeout 600 python3 "$repo/deploy/stage5/acceptance.py" --run-id "$run_id" --root "$remote_root" --env-file "$compose_env" --project "$compose_project" "$@"; }
backup_database() { driver backup; }
verify_migration_gate() {
  cp "$repo/backend/src/main/resources/db/migration/V10__add_stage5_event_pipeline.sql" "$evidence/V10-review-only.sql"
  sha256sum "$evidence/V10-review-only.sql" > "$evidence/V10-review-only.sha256"
  [[ $migration == true ]] || { printf 'Backup saved; separate V10 review/application required; no migration was applied\n' >&2; return 78; }
  driver migration-check
}
primary=failed recovery=not-required cleanup=not-required scan=failed started=false seeded=false current_step=preflight
finish() {
  rc=$?; primary_step=$current_step; trap - EXIT; set +e
  if [[ $started == true ]]; then
    compose start kafka > "$remote_root/recovery.log" 2>&1
    if bash "$repo/deploy/stage5/wait-for-stage5.sh" "$compose_project" "$compose_file" "$compose_env" "$evidence/readiness-final-kafka.txt" kafka; then recovery=success; else recovery=failed; fi
    # Quiesce only our backends before removing their exact fixture IDs.
    if compose stop backend-a backend-b > "$remote_root/quiesce.log" 2>&1; then
      if [[ $seeded == true ]]; then
        if driver cleanup && driver assert-clean; then cleanup=success; else cleanup=failed; fi
      else cleanup=success; fi
    else cleanup=failed; fi
    if [[ $cleanup == success ]]; then
      if ! compose down --volumes > "$remote_root/stack-down.log" 2>&1; then cleanup=failed; fi
    else
      # Keep failed-run Kafka data rather than destroying recovery evidence.
      compose stop kafka > "$remote_root/preserved-kafka.log" 2>&1
    fi
  fi
  if driver scan; then scan=success; else scan=failed; fi
  printf '{"runId":"%s","commit":"%s","primaryStatus":"%s","primaryExitCode":%s,"primaryStep":"%s","recoveryStatus":"%s","cleanupStatus":"%s","secretScanStatus":"%s"}\n' "$run_id" "$commit" "$primary" "$rc" "$primary_step" "$recovery" "$cleanup" "$scan" > "$evidence/run-status.json"
  if ((rc != 0)); then exit "$rc"; fi
  [[ $primary == success && $recovery == success && $cleanup == success && $scan == success && $rc == 0 ]] || exit 1
}
trap finish EXIT
driver preflight
compose config --no-interpolate > "$evidence/compose-config.yml"
backup_database
verify_migration_gate
compose build backend-a backend-b > "$remote_root/build.log" 2>&1
started=true
compose up -d kafka backend-a backend-b > "$remote_root/stack-up.log" 2>&1
bash "$repo/deploy/stage5/wait-for-stage5.sh" "$compose_project" "$compose_file" "$compose_env" "$evidence/readiness-initial.json" api
bash "$repo/deploy/stage5/wait-for-stage5.sh" "$compose_project" "$compose_file" "$compose_env" "$evidence/readiness-kafka.txt" kafka
driver topics
seeded=true
driver seed
driver normal
driver metrics-before
compose stop kafka > "$evidence/kafka-stop.txt" 2>&1
bash "$repo/deploy/stage5/wait-for-stage5.sh" "$compose_project" "$compose_file" "$compose_env" "$evidence/readiness-outage.json" api
driver outage
compose start kafka > "$evidence/kafka-start.txt" 2>&1
bash "$repo/deploy/stage5/wait-for-stage5.sh" "$compose_project" "$compose_file" "$compose_env" "$evidence/readiness-recovered.txt" kafka
driver recover
driver duplicate
driver reconcile
driver metrics-after
# Driver captures offsets, metrics, SQL terminal states, environment-redacted.txt,
# backup.sha256 and fingerprint-before.sha256/fingerprint-after.sha256.
primary=success

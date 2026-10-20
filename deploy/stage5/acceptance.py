#!/usr/bin/env python3
"""Repo-owned live acceptance driver. No third-party Python dependencies.

Called only after remote-stage5.sh's explicit live gate. Credentials, tokens and
raw dumps stay outside evidence; subprocesses receive SQL/HTTP secrets on stdin.
"""
import argparse
import base64
import hashlib
import json
import math
import os
from pathlib import Path
import re
import queue
import secrets
import shutil
import subprocess
import sys
import time
import threading
import uuid


SECRET_NAMES = {"DB_PASSWORD", "JWT_SECRET", "REDIS_PASSWORD", "RATE_LIMIT_KEY_SECRET"}
REQUIRED = {"DB_HOST", "DB_PORT", "DB_NAME", "DB_USERNAME", "DB_PASSWORD", "JWT_SECRET",
            "REDIS_HOST", "REDIS_PORT", "RATE_LIMIT_KEY_SECRET", "SPRING_FLYWAY_ENABLED",
            "STAGE5_MYSQL_CONTAINER", "STAGE5_REDIS_CONTAINER", "STAGE5_MAINTENANCE_APPROVED"}
OPTIONAL = {"REDIS_PASSWORD", "REDIS_DATABASE", "OUTBOX_POLL_INTERVAL", "OUTBOX_BATCH_SIZE",
            "OUTBOX_LEASE", "OUTBOX_SEND_TIMEOUT", "OUTBOX_RETRY_INITIAL", "OUTBOX_RETRY_MAX",
            "OUTBOX_RETENTION", "OUTBOX_RETENTION_INTERVAL", "OUTBOX_RETENTION_BATCH_SIZE",
            "PROMOTION_RECONCILIATION_SCAN_DELAY", "PROMOTION_RECONCILIATION_BATCH_SIZE",
            "PROMOTION_RECONCILIATION_LEASE", "PROMOTION_RECONCILIATION_RETRY_INITIAL",
            "PROMOTION_RECONCILIATION_RETRY_MAX"}
METRICS = ("outbox.pending.count", "outbox.dead.count", "outbox.oldest.pending.age",
           "outbox.publish.success", "outbox.publish.failure", "kafka.consumer.success",
           "kafka.consumer.duplicate", "kafka.consumer.dlt", "promotion.reconcile.pending",
           "promotion.reconcile.success", "promotion.reconcile.failure")
# Closed, child-first cleanup inventory. Every scope is checked before any creation.
ORG_TABLE_KEYS = {
    'promotion_reconcile_task': 'position_id', 'outbox_event': 'id', 'promotion_offer': 'id',
    'notification': 'id', 'registration_answer': 'id', 'registration_cycle': 'id',
    'registration': 'id', 'activity_position': 'id', 'activity': 'id',
    'organization_member': 'id', 'rbac_role_permission': 'id', 'rbac_role': 'id', 'audit_log': 'id'}
TABLE_KEYS = {**ORG_TABLE_KEYS, 'organization': 'id', 'app_user': 'id',
              'refresh_session': 'id', 'consumed_event': 'id'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_env(path):
    result = {}
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        if not line or line.startswith("#"):
            continue
        key, separator, value = line.partition("=")
        require(separator and key in REQUIRED | OPTIONAL and key not in result, "Invalid environment key")
        require(not re.search(r"[\s\x00-\x1f$'\"\\#]", value), "Unsafe environment syntax")
        require(not re.search(r"replace-with|placeholder|change-me", value, re.I), "Unresolved environment")
        result[key] = value
    require(REQUIRED <= result.keys(), "Missing environment key")
    require(all(result[k] for k in REQUIRED), "Empty required environment value")
    require(result["SPRING_FLYWAY_ENABLED"] == "false", "Restored Flyway must remain false")
    require(result["STAGE5_MAINTENANCE_APPROVED"] == "true", "Maintenance window not approved")
    for key in ("DB_NAME", "DB_USERNAME", "STAGE5_MYSQL_CONTAINER", "STAGE5_REDIS_CONTAINER"):
        require(re.fullmatch(r"[A-Za-z0-9_][A-Za-z0-9_.-]{0,100}", result[key]), "Invalid resource name")
    for key in ("DB_PORT", "REDIS_PORT"):
        require(result[key].isdigit() and 0 < int(result[key]) < 65536, "Invalid port")
    return result


def safe_scope(run_id, root, project):
    require(re.fullmatch(r"[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}", run_id), "invalid run scope")
    require(root == "/opt/volunteerflow-stage5/" + run_id and
            project == "vf5" + run_id.replace("-", ""), "invalid run scope")
    require(Path(root).resolve() == Path(root) and not Path(root).is_symlink(), "invalid run scope")


def pipe_exchange(frame, seconds, response=True):
    """Bound both write and partial reads; daemon uses raw FDs, no buffered shutdown locks."""
    result = queue.Queue(maxsize=1)
    def worker():
        try:
            remaining = memoryview(frame)
            while remaining:
                written = os.write(1, remaining)
                require(written > 0, 'Pipe write failed')
                remaining = remaining[written:]
            data = bytearray()
            while response:
                chunk = os.read(0, min(65536, 90*1024*1024 + 1 - len(data)))
                require(chunk and len(data) + len(chunk) <= 90*1024*1024, 'Invalid pipe response size/EOF')
                data.extend(chunk)
                if b'\n' in chunk:
                    require(data.endswith(b'\n') and data.count(b'\n') == 1, 'Multiple response frames')
                    break
            result.put(bytes(data))
        except Exception as error:
            result.put(error)
    threading.Thread(target=worker, daemon=True).start()
    try:
        answer = result.get(timeout=seconds)
    except queue.Empty:
        raise ValueError('Pipe operation deadline exceeded') from None
    require(isinstance(answer, bytes), 'Pipe operation failed')
    return answer


def strict_base64(value, cap):
    require(len(value) <= ((cap+2)//3)*4, 'Encoded response exceeds cap')
    decoded = base64.b64decode(value, validate=True)
    require(len(decoded) <= cap and base64.b64encode(decoded).decode('ascii') == value,
            'Invalid response encoding')
    return decoded


class Driver:
    def __init__(self, args):
        safe_scope(args.run_id, args.root, args.project)
        self.run_id, self.root, self.project = args.run_id, Path(args.root), args.project
        require(Path(args.env_file) == self.root / "stage5.env", "Invalid environment path")
        self.env_file = args.env_file
        self.env = read_env(args.env_file)
        self.evidence = self.root / "evidence"
        require(self.evidence.is_dir() and not self.evidence.is_symlink(), "Missing protected evidence directory")
        self.state_file = self.root / "private/manifest-private.json"
        self.pipe = args.pipe
        self.request_id = 0
        self.state = json.loads(self.state_file.read_text()) if self.state_file.exists() else {}
        self.child_env = {k: v for k, v in os.environ.items() if k in ("PATH", "HOME", "LANG", "LC_ALL")}
        self.child_env["STAGE5_RUN_COMPACT"] = args.run_id.replace("-", "")
        self.compose = ["docker", "compose", "-p", args.project, "-f",
                        str(self.root / "repo/deploy/stage5/docker-compose.yml"), "--env-file", args.env_file]
        # Host constructs this private file; it is never mounted into Python.
        self.cnf = self.root / "host-private/mysql.cnf"

    def rpc(self, action, values, data=None, timeout=60):
        require(1 <= timeout <= 300 and action in ('exec', 'kafka-list-topics',
                'kafka-produce-duplicate', 'kafka-describe-group'), 'Invalid pipe request')
        encoded = ('\0'.join(values) + '\0').encode() if values else b''
        require(len(encoded) <= 8192 and len(data or b'') <= 131072, 'Request byte cap exceeded')
        self.request_id += 1
        identity = f'{self.request_id:08d}'
        frame = ('VF5|1|' + identity + '|' + action + '|' + str(timeout) + '|' +
                 base64.b64encode(encoded).decode() + '|' + base64.b64encode(data or b'').decode() + '\n').encode()
        fields = pipe_exchange(frame, timeout+30).decode('ascii').rstrip('\n').split('|')
        require(len(fields) == 7 and fields[:5] == ['VF5','1',identity,'result','0'], 'Invalid response correlation/schema')
        strict_base64(fields[6], 65536)
        return strict_base64(fields[5], 64*1024*1024).decode('utf-8')

    def execute(self, command, data=None, timeout=60, extra_env=None):
        if getattr(self, 'pipe', False):
            require(extra_env is None or set(extra_env) <= {'REDISCLI_AUTH'}, 'Invalid pipe request')
            return self.rpc('exec', command, data, timeout)
        result = subprocess.run(command, input=data, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                env={**self.child_env, **(extra_env or {})}, timeout=timeout, check=False)
        require(result.returncode == 0, "External command failed (details retained only by operator)")
        return result.stdout.decode("utf-8")

    def sql(self, statement):
        return self.execute(["mysql", "--defaults-file=" + str(self.cnf), "--connect-timeout=5", "--binary-mode=1", "--local-infile=0",
                             "--batch", "--raw", "--skip-column-names", self.env["DB_NAME"]],
                            statement.encode(), timeout=60).strip()

    def count(self, query):
        return int(self.sql(query))

    def redis(self, *arguments):
        database = self.env.get('REDIS_DATABASE', '2')
        require(database.isdigit() and 0 <= int(database) <= 15, 'Invalid Redis database')
        return self.execute(['redis-cli', '--raw', '-h', self.env['REDIS_HOST'], '-p', self.env['REDIS_PORT'],
            '-n', database] + list(arguments), extra_env={'REDISCLI_AUTH': self.env.get('REDIS_PASSWORD', '')})

    def cleanup_redis(self):
        prefix = 'volunteerflow:' + self.project + ':'
        keys = self.redis('--scan', '--pattern', prefix + '*').splitlines()
        require(len(keys) <= 1000, 'Run Redis cleanup bound exceeded')
        require(all(key.startswith(prefix) and not re.search(r'[\s\x00-\x1f]', key) for key in keys), 'Foreign Redis key')
        for start in range(0, len(keys), 100):
            self.redis('DEL', *keys[start:start+100])
        require(not self.redis('--scan', '--pattern', prefix + '*').strip(), 'Run Redis cleanup incomplete')

    def save(self):
        self.state_file.write_text(json.dumps(self.state))
        self.state_file.chmod(0o600)

    def evidence_json(self, name, value):
        require(re.fullmatch(r"[a-z0-9-]+\.json", name), "Invalid evidence name")
        (self.evidence / name).write_text(json.dumps(value, indent=2, allow_nan=False) + "\n")

    def api(self, path, body=None, token=None, backend="backend-a"):
        require(path.startswith("/api/v1/") or path.startswith("/actuator/"), "Invalid endpoint")
        if path.startswith('/actuator/metrics') and token is None:
            token = self.state['users'][0]['token']
        lines = ['url = ' + json.dumps('http://localhost:8080' + path), 'silent', 'show-error', 'fail',
                 'max-time = 15', 'connect-timeout = 5']
        if token:
            lines.append('header = ' + json.dumps('Authorization: Bearer ' + token))
        if body is not None:
            lines += ['header = "Content-Type: application/json"', 'request = "POST"',
                      'data = ' + json.dumps(json.dumps(body))]
        # Neither passwords nor Authorization appear in argv, logs or evidence.
        text = self.execute(self.compose + ["exec", "-T", backend, "curl", "--config", "-"],
                            ('\n'.join(lines) + '\n').encode(), timeout=25)
        return json.loads(text)

    def wait(self, predicate, message, seconds=180):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(2)
        raise ValueError(message)

    def fingerprint(self):
        raw = self.execute(["mysqldump", "--defaults-file=" + str(self.cnf), "--single-transaction",
            "--skip-comments", "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob", "--order-by-primary",
            "--skip-extended-insert", "--no-create-info", "--skip-add-locks", "--skip-disable-keys",
            self.env["DB_NAME"]], timeout=300)
        return hashlib.sha256(raw.encode()).hexdigest()

    def identities(self):
        values = []
        for key in ("STAGE5_MYSQL_CONTAINER", "STAGE5_REDIS_CONTAINER"):
            text = self.execute(["docker", "inspect", "--format",
                '{"id":"{{.Id}}","name":"{{.Name}}","image":"{{.Config.Image}}","running":{{.State.Running}}}', self.env[key]])
            value = json.loads(text)
            require(value["running"] is True, "Existing store not running")
            values.append(value)
        return values

    def preflight(self):
        require(not self.state_file.exists(), "Run ID was already used")
        for command in (["mysql", "--version"], ["mysqldump", "--version"], ['redis-cli','--version'], ["docker", "compose", "version"]):
            self.execute(command)
        require(self.redis('PING').strip() == 'PONG', 'Existing Redis unavailable')
        require(not self.redis('--scan', '--pattern', 'volunteerflow:' + self.project + ':*').strip(), 'Redis namespace reused')
        containers = self.identities()
        disk = shutil.disk_usage(self.root / 'private')
        require(disk.free >= 5 * 1024**3, 'Insufficient backup/build disk space')
        self.state["containers"] = containers
        self.save()
        self.evidence_json("environment.json", {"runId": self.run_id, "containers": containers,
            "python": sys.version.split()[0], "platform": os.uname().sysname,
            "release": os.uname().release, "cpuCount": os.cpu_count(),
            "memoryBytes": os.sysconf('SC_PHYS_PAGES') * os.sysconf('SC_PAGE_SIZE'),
            "diskFreeBytes": disk.free,
            "dockerVersion": self.execute(['docker','version','--format','{{.Server.Version}}']).strip(),
            "mysqlVersion": self.execute(['mysql','--version']).strip(),
            "redisVersion": self.execute(['redis-cli','--version']).strip()})
        (self.evidence / "environment-redacted.txt").write_text('\n'.join(
            key + '=' + ('[REDACTED]' if key in SECRET_NAMES else value)
            for key, value in sorted(self.env.items())) + '\n')

    def backup(self):
        backup = self.execute(["mysqldump", "--defaults-file=" + str(self.cnf), "--single-transaction",
            "--skip-comments", "--no-tablespaces", "--set-gtid-purged=OFF", "--hex-blob",
            "--routines", "--triggers", self.env["DB_NAME"]], timeout=300)
        require(len(backup) > 100, "Empty database backup")
        (self.root / "private/backup-private.sql").write_text(backup)
        (self.root / "private/backup-private.sql").chmod(0o600)
        (self.evidence / "backup.sha256").write_text(hashlib.sha256(backup.encode()).hexdigest() + '\n')
        fingerprint = self.fingerprint()
        self.state["fingerprint"] = fingerprint
        self.save()
        (self.evidence / "fingerprint-before.sha256").write_text(fingerprint + '\n')

    def migration_check(self):
        require(self.count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() "
                           "AND table_name='outbox_event' AND column_name IN ('topic','published_offset','locked_until')") == 3,
                "V10 must be separately reviewed and manually applied; runner never migrates")
        require(self.count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() "
                           "AND table_name IN ('consumed_event','promotion_reconcile_task')") == 2, "V10 schema incomplete")
        # Avoid touching pre-existing pending work or making fingerprint claims under live traffic.
        require(self.count("SELECT COUNT(*) FROM outbox_event") == 0, "Original Outbox must be empty in maintenance window")
        require(self.count("SELECT COUNT(*) FROM promotion_reconcile_task") == 0, "Original reconciliation tasks must be empty")
        require(self.count("SELECT COUNT(*) FROM activity WHERE status='PUBLISHED' AND activity_end_at<=CURRENT_TIMESTAMP+INTERVAL 1 DAY") == 0
                and self.count("SELECT COUNT(*) FROM promotion_offer WHERE status='PENDING' AND expires_at<=CURRENT_TIMESTAMP+INTERVAL 1 DAY") == 0,
                'Original scheduled work could mutate historical records')
        self.evidence_json("migration-gate.json", {"reviewed": True, "schemaVerified": True, "appliedByRunner": False})

    def topics(self):
        listed = set(self.rpc('kafka-list-topics', [], timeout=30).splitlines())
        for suffix in ("activity-events.v1", "registration-events.v1", "checkin-events.v1"):
            topic = self.project + '.' + suffix
            for name in (topic, topic + '.DLT'):
                require(name in listed, 'Host-prepared run topic missing')

    def seed(self):
        base = 4_000_000_000_000_000 + (uuid.UUID(self.run_id).int % 1_000_000_000_000) * 100
        for table, ids in (('organization', [base]), ('activity', [base+1]),
                           ('rbac_role', [base+2]), ('activity_position', [base+3,base+4,base+5])):
            for identity in ids:
                require(self.count(f"SELECT COUNT(*) FROM {table} WHERE id={identity}") == 0, 'Fixture ID collision')
        names = ['s5-' + self.run_id + '-' + str(i) for i in range(3)]
        for name in names:
            require(self.count("SELECT COUNT(*) FROM app_user WHERE username='" + name + "' OR student_number='" + name + "'") == 0, "Fixture username collision")
        for table in ORG_TABLE_KEYS:
            require(self.count(f"SELECT COUNT(*) FROM `{table}` WHERE organization_id={base}") == 0,
                    'Historical rows in cleanup scope')
        original = {}
        for table, key in TABLE_KEYS.items():
            rows = self.sql(f"SELECT {key} FROM `{table}` ORDER BY {key} LIMIT 100001").splitlines()
            require(len(rows) <= 100000, 'Original cleanup key inventory exceeds bound')
            original[table] = [int(value) for value in rows]
        self.state.update({"org": base, "activity": base+1, "role": base+2,
                           "positions": [base+3, base+4, base+5], "users": [],
                           "names": names, "scope_preflight": True, "original_keys": original,
                           "created": {table: [] for table in TABLE_KEYS}})
        self.save()  # persist planned exact names before the first account request
        try:
            self.seed_planned()
        finally:
            # A lost HTTP response can follow an already committed account/session.
            # Recover only the names proven absent by this persisted pre-seed receipt.
            self.checkpoint_created()

    def seed_planned(self):
        base = self.state['org']
        for index, name in enumerate(self.state["names"]):
            token = self.api('/api/v1/auth/register', {"username": name, "password": secrets.token_urlsafe(28),
                "realName": "Stage5 isolated member", "studentNumber": name, "contact": "stage5-only"})['data']['accessToken']
            user = self.api('/api/v1/auth/me', token=token)['data']
            self.state['users'].append({"id": int(user['id']), "token": token})
            self.save()
        org, activity, role = base, base+1, base+2
        owner = self.state['users'][0]['id']
        statements = ["START TRANSACTION", f"INSERT INTO organization(id,name,status,created_by) VALUES({org},'s5-{self.run_id}','ACTIVE',{owner})",
            f"INSERT INTO rbac_role(id,organization_id,name,built_in_type,protected_role,status) VALUES({role},{org},'member','CUSTOM',0,'ACTIVE')",
            f"INSERT INTO rbac_role_permission(id,organization_id,role_id,permission_id) SELECT {base+10},{org},{role},id FROM rbac_permission WHERE code='registration:create'",
            f"INSERT INTO activity(id,organization_id,title,description,location,registration_start_at,registration_end_at,free_cancel_deadline_at,activity_start_at,activity_end_at,status,created_by,published_at) "
            f"VALUES({activity},{org},'s5-{self.run_id}','isolated fixture','stage5',NOW()-INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY,NOW()+INTERVAL 1 DAY,NOW()+INTERVAL 2 DAY,NOW()+INTERVAL 3 DAY,'PUBLISHED',{owner},NOW())"]
        for i, user in enumerate(self.state['users']):
            statements.append(f"INSERT INTO organization_member(id,organization_id,user_id,role_id,status,joined_at) VALUES({base+20+i},{org},{user['id']},{role},'ACTIVE',NOW())")
        for i, position in enumerate(self.state['positions']):
            statements.append(f"INSERT INTO activity_position(id,organization_id,activity_id,name,description,capacity,registration_mode,promotion_timeout_minutes,status) "
                              f"VALUES({position},{org},{activity},'position-{i}','isolated fixture',{0 if i == 2 else 1},'FIRST_COME',120,'ACTIVE')")
        self.sql(';'.join(statements + ['COMMIT']) + ';')
        self.evidence_json('manifest.json', {k: self.state[k] for k in ('org','activity','positions')})

    def submit(self, index):
        activity, position = self.state['activity'], self.state['positions'][index]
        user = self.state['users'][index]
        result = self.api(f'/api/v1/activities/{activity}/registrations',
                          {"positionId": position, "answers": []}, user['token'],
                          'backend-b' if index == 1 else 'backend-a')['data']
        rows = self.sql(f"SELECT event_id FROM outbox_event WHERE organization_id={self.state['org']} AND aggregate_id='{int(result['registrationId'])}' AND event_type='registration.submitted.v1'").splitlines()
        require(len(rows) == 1, 'Exactly one registration Outbox event required')
        event = str(uuid.UUID(rows[0]))
        require(self.count(f"SELECT COUNT(*) FROM audit_log WHERE organization_id={self.state['org']} AND resource_id={int(result['cycleId'])} AND action='registration.submitted'") == 1,
                'Registration audit must commit')
        self.state[str(index)] = {"event": event, "registration": int(result['registrationId']), "cycle": int(result['cycleId']), "status": result['status']}
        self.save()
        self.checkpoint_created()
        return event

    def terminal(self, index):
        event = self.state[str(index)]['event']
        user = self.state['users'][index]['id']
        return [self.count(query) for query in (
            f"SELECT COUNT(*) FROM outbox_event WHERE event_id='{event}' AND status='PUBLISHED' AND published_offset IS NOT NULL",
            f"SELECT COUNT(*) FROM consumed_event WHERE event_id='{event}' AND consumer_name='volunteerflow-notification-v1'",
            f"SELECT COUNT(*) FROM notification WHERE organization_id={self.state['org']} AND deduplication_key='{event}:{user}:IN_APP'")]

    def normal(self):
        self.submit(0)
        require(self.state['0']['status'] == 'CONFIRMED', 'Normal registration not confirmed')
        self.wait(lambda: self.terminal(0) == [1, 1, 1], 'Normal event did not terminate')
        self.snapshot('normal', 0)

    def outage(self):
        self.submit(1)
        require(self.state['1']['status'] == 'CONFIRMED', 'Outage business transaction failed')
        event = self.state['1']['event']
        require(self.sql(f"SELECT status FROM outbox_event WHERE event_id='{event}'") in ('PENDING','PROCESSING','RETRY'), 'Outage event not pending/retryable')
        require(self.terminal(1) == [0, 0, 0], 'Outage unexpectedly consumed')
        # Wait until relay recorded a retry, not merely before its first poll.
        self.wait(lambda: self.count(f"SELECT COUNT(*) FROM outbox_event WHERE event_id='{event}' AND retry_count>0 AND status='RETRY'") == 1, 'No observed relay failure')
        self.snapshot('outage', 1)

    def recover(self):
        self.wait(lambda: self.terminal(1) == [1, 1, 1], 'Recovered event did not terminate', seconds=420)
        self.snapshot('recover', 1)

    def duplicate(self):
        before = self.metric('kafka.consumer.duplicate')
        event = self.state['1']['event']
        raw = self.sql(f"SELECT HEX(message_key),HEX(payload_json),topic FROM outbox_event WHERE event_id='{event}'").split('\t')
        require(len(raw) == 3 and raw[2] == self.project + '.registration-events.v1', 'Unexpected event routing')
        key, value = bytes.fromhex(raw[0]).decode(), bytes.fromhex(raw[1]).decode()
        require(json.loads(value)['eventId'] == event and '\n' not in key and '\t' not in key, 'Invalid duplicate payload')
        self.rpc('kafka-produce-duplicate', [raw[2]], (key+'\t'+value+'\n').encode(), timeout=45)
        self.wait(lambda: self.metric('kafka.consumer.duplicate') > before, 'Duplicate not observed')
        require(self.terminal(1) == [1, 1, 1], 'Duplicate changed notification cardinality')
        self.snapshot('duplicate', 1)

    def reconcile(self):
        self.submit(2)
        require(self.state['2']['status'] == 'WAITLISTED', 'Expected waitlisted fixture')
        position, org = self.state['positions'][2], self.state['org']
        self.sql(f"START TRANSACTION; UPDATE activity_position SET capacity=1 WHERE id={position} AND organization_id={org}; "
                 f"INSERT INTO promotion_reconcile_task(position_id,organization_id,status,request_version,processed_version,next_run_at) VALUES({position},{org},'PENDING',1,0,NOW()); COMMIT;")
        offer_query = f"SELECT COUNT(*) FROM promotion_offer WHERE organization_id={org} AND position_id={position} AND status='PENDING'"
        self.wait(lambda: self.count(offer_query) == 1, 'Reconciliation did not create one offer')
        self.wait(lambda: self.count(f"SELECT COUNT(*) FROM promotion_reconcile_task WHERE position_id={position} AND request_version=processed_version") == 1, 'Reconciliation continuation incomplete')
        self.sql(f"UPDATE promotion_reconcile_task SET request_version=request_version+1,status='PENDING',next_run_at=NOW() WHERE position_id={position} AND organization_id={org}")
        self.wait(lambda: self.count(f"SELECT COUNT(*) FROM promotion_reconcile_task WHERE position_id={position} AND request_version=processed_version") == 1, 'Second reconciliation incomplete')
        require(self.count(offer_query) == 1 and self.count(f"SELECT COUNT(*) FROM promotion_offer WHERE position_id={position}") == 1, 'Reconciliation duplicated offer')
        self.wait(lambda: self.count(f"SELECT COUNT(*) FROM outbox_event o LEFT JOIN consumed_event c "
            f"ON c.event_id=o.event_id AND c.consumer_name='volunteerflow-notification-v1' "
            f"WHERE o.organization_id={org} AND (o.status<>'PUBLISHED' OR c.id IS NULL)") == 0,
            'Reconciliation events not fully drained')
        self.evidence_json('reconciliation.json', {'positionId': position, 'activeOffers': 1, 'totalOffers': 1, 'secondRunProcessed': True})
        self.snapshot('reconcile', 2)

    def metric_samples(self, name):
        samples = {}
        for backend in ('backend-a','backend-b'):
            if 'lag' in name and name not in self.api('/actuator/metrics', backend=backend)['names']:
                continue
            sample = self.api('/actuator/metrics/' + name, backend=backend)
            total = 0.0
            for measurement in sample['measurements']:
                value = float(measurement['value'])
                require(math.isfinite(value), 'Metric unavailable')
                total += value
            require(sample['measurements'], 'No actual metric samples')
            samples[backend] = total
        require(samples, 'No actual metric samples')
        return samples

    def metric(self, name):
        return sum(self.metric_samples(name).values())

    def metrics(self, phase):
        samples = {name: self.metric_samples(name) for name in METRICS}
        gauges = {'outbox.pending.count','outbox.dead.count','outbox.oldest.pending.age','promotion.reconcile.pending'}
        values = {name: sum(value.values()) for name, value in samples.items() if name not in gauges}
        if phase == 'after':
            require(values['outbox.publish.failure'] > 0 and values['kafka.consumer.duplicate'] > 0 and
                    values['promotion.reconcile.success'] > 0, 'Expected metric transitions absent')
        self.evidence_json('metrics-' + phase + '.json', {'counterSums': values, 'perInstance': {
            backend: {name: sample[backend] for name, sample in samples.items()} for backend in ('backend-a','backend-b')}})
        # Capture lag meters as registered by the client (names may contain tags).
        names = set(self.api('/actuator/metrics', backend='backend-a')['names']) | set(
            self.api('/actuator/metrics', backend='backend-b')['names'])
        lag_names = [name for name in names if name.startswith('kafka.consumer.') and 'lag' in name]
        require(lag_names, 'Kafka client lag not exposed')
        self.evidence_json('metrics-lag-' + phase + '.json', {name: self.metric_samples(name) for name in lag_names})

    def snapshot(self, phase, index):
        self.checkpoint_created()
        event = self.state[str(index)]['event']
        rows = self.sql(f"SELECT event_id,status,retry_count,COALESCE(published_partition,-1),COALESCE(published_offset,-1) FROM outbox_event WHERE event_id='{event}'")
        self.evidence_json('database-' + phase + '.json', {'eventId': event, 'outbox': rows, 'terminalCounts': self.terminal(index),
            'registration': self.state[str(index)], 'positionId': self.state['positions'][index]})
        if phase != 'outage':
            text = self.rpc('kafka-describe-group', [self.project+'-notification'], timeout=30)
            (self.evidence / ('offsets-' + phase + '.txt')).write_text(text)

    def cleanup_rows(self):
        """Read current candidate identities; never infer ownership from organization alone."""
        org = int(self.state['org'])
        name = 's5-' + self.run_id
        require(self.count(f"SELECT COUNT(*) FROM organization WHERE id={org} AND name='{name}'") in (0,1), 'Ambiguous fixture')
        require(self.count(f"SELECT COUNT(*) FROM organization WHERE id={org} AND name<>'{name}'") == 0, 'Fixture ID collision')
        for username in self.state['names']:
            require(re.fullmatch('s5-' + re.escape(self.run_id) + '-[012]', username), 'Unsafe cleanup username')
        names = ','.join("'" + value + "'" for value in self.state['names'])
        require(names, 'Missing exact account ownership names')
        predicates = {table: f'organization_id={org}' for table in ORG_TABLE_KEYS}
        predicates['organization'] = f"id={org} AND name='{name}'"
        predicates['app_user'] = f'username IN ({names}) AND student_number=username'
        rows = {}
        for table, predicate in predicates.items():
            values = self.sql(f'SELECT {TABLE_KEYS[table]} FROM `{table}` WHERE {predicate} ORDER BY {TABLE_KEYS[table]} LIMIT 1001').splitlines()
            require(len(values) <= 1000, 'Run cleanup key inventory exceeds bound')
            rows[table] = sorted(int(value) for value in values)
            require(not set(rows[table]) & set(self.state['original_keys'][table]), 'Original cleanup rows cannot be owned')
        require(self.count(f'SELECT COUNT(*) FROM app_user WHERE username IN ({names}) OR student_number IN ({names})') == len(rows['app_user']),
                'Ambiguous account cleanup identity')
        users = ','.join(str(value) for value in rows['app_user']) or 'NULL'
        outbox = ','.join(str(value) for value in rows['outbox_event']) or 'NULL'
        events = self.sql(f'SELECT event_id FROM outbox_event WHERE id IN ({outbox}) AND organization_id={org}').splitlines()
        events = [str(uuid.UUID(value)) for value in events]
        event_sql = ','.join("'" + value + "'" for value in events) or 'NULL'
        predicates['refresh_session'] = f'user_id IN ({users})'
        predicates['consumed_event'] = f"consumer_name='volunteerflow-notification-v1' AND event_id IN ({event_sql})"
        for table in ('refresh_session','consumed_event'):
            values = self.sql(f'SELECT id FROM {table} WHERE {predicates[table]} ORDER BY id LIMIT 1001').splitlines()
            require(len(values) <= 1000, 'Run cleanup key inventory exceeds bound')
            # Historical orphan session/consumed keys are never claimed, even if they
            # reference a later-created account/event. This baseline predates all HTTP.
            rows[table] = sorted(set(int(value) for value in values) - set(self.state['original_keys'][table]))
        return rows, predicates

    def checkpoint_created(self):
        if self.state.get('scope_preflight') is not True:
            return
        rows, _ = self.cleanup_rows()
        for table in TABLE_KEYS:
            require(set(self.state['created'][table]) <= set(rows[table]), 'Recorded cleanup rows unexpectedly disappeared')
        self.state['created'] = rows
        self.save()

    def cleanup(self):
        # No preflight receipt means seed never acquired any ownership; do nothing.
        if self.state.get('scope_preflight') is not True:
            return
        rows, predicates = self.cleanup_rows()
        require(rows == self.state['created'], 'Unrecorded cleanup rows; preserve all rows for review')
        statements = ['START TRANSACTION']
        for table in ('consumed_event', *ORG_TABLE_KEYS, 'organization', 'refresh_session', 'app_user'):
            if rows[table]:
                ids = ','.join(str(value) for value in rows[table])
                statements.append(f'DELETE FROM `{table}` WHERE {TABLE_KEYS[table]} IN ({ids}) AND {predicates[table]}')
        self.sql(';'.join(statements + ['COMMIT']) + ';')

        self.cleanup_redis()

    def assert_clean(self):
        require(self.identities() == self.state['containers'], 'Original store identity changed')
        actual = self.fingerprint()
        (self.evidence / 'fingerprint-after.sha256').write_text(actual + '\n')
        require(actual == self.state['fingerprint'], 'Original database fingerprint changed')
        self.evidence_json('cleanup.json', {'originalDataUnchanged': True, 'storeContainersUnchanged': True,
            'redisRunNamespaceClean': True})

    def scan(self):
        values = [value.encode() for name, value in self.env.items() if name in SECRET_NAMES and value]
        forbidden = re.compile(rb'-----BEGIN (?:OPENSSH|RSA|EC|PRIVATE).*KEY-----|Bearer\s+[A-Za-z0-9_-]{12,}|eyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+')
        for path in self.evidence.rglob('*'):
            require(not path.is_symlink(), 'Symlink in evidence')
            if path.is_file() and path.name != 'secret-scan.txt':
                data = path.read_bytes()
                require(not forbidden.search(data) and not any(value in data for value in values), 'Evidence secret scan failed')
        (self.evidence / 'secret-scan.txt').write_text('PASS\n')


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--run-id', required=True)
    parser.add_argument('--root', required=True)
    parser.add_argument('--env-file', required=True)
    parser.add_argument('--project', required=True)
    parser.add_argument('--pipe', action='store_true')
    parser.add_argument('action', choices=['preflight','backup','migration-check','topics','seed','normal','outage',
        'recover','duplicate','reconcile','metrics-before','metrics-after','cleanup','assert-clean','scan'])
    args = parser.parse_args()
    try:
        safe_scope(args.run_id, args.root, args.project)
        driver = Driver(args)
        if args.action.startswith('metrics-'):
            driver.metrics(args.action.split('-')[1])
        else:
            getattr(driver, args.action.replace('-', '_'))()
        if driver.pipe:
            pipe_exchange(f'VF5|1|{driver.request_id+1:08d}|done|0||\n'.encode(), 5, response=False)
    except Exception as error:
        # Never expose subprocess output, SQL, tokens, passwords or payloads in failure text.
        print('Stage5 action failed: ' + ('invalid run scope' if str(error) == 'invalid run scope' else type(error).__name__), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())

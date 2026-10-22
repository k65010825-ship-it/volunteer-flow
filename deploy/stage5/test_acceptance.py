"""Offline tests: real driver logic; doubles exist only at HTTP/SQL process boundaries."""
import importlib.util
import json
import re
from pathlib import Path
import sqlite3
import tempfile
import unittest
import uuid
import subprocess
import os
import shutil
from types import SimpleNamespace
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('acceptance.py'))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)
RUN = '00000000-0000-0000-0000-000000000001'
MYSQL_ID = 'a' * 64
REDIS_ID = 'b' * 64
ORG_TABLE_KEYS = {
    'promotion_reconcile_task': 'position_id', 'outbox_event': 'id', 'promotion_offer': 'id',
    'notification': 'id', 'registration_answer': 'id', 'registration_cycle': 'id',
    'registration': 'id', 'activity_position': 'id', 'activity': 'id',
    'organization_member': 'id', 'rbac_role_permission': 'id', 'rbac_role': 'id', 'audit_log': 'id'}


class DriverTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def driver(self):
        driver = acceptance.Driver.__new__(acceptance.Driver)
        driver.run_id = RUN
        driver.root = self.root
        driver.evidence = self.root / 'evidence'
        driver.evidence.mkdir()
        driver.state_file = self.root / 'state.json'
        driver.state = {}
        return driver

    def database(self, driver):
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        for table, key in ORG_TABLE_KEYS.items():
            extra = ',event_id TEXT' if table == 'outbox_event' else ''
            db.execute(f'CREATE TABLE {table}({key} INTEGER PRIMARY KEY,organization_id INTEGER{extra})')
        db.executescript('CREATE TABLE organization(id INTEGER PRIMARY KEY,name TEXT);'
            'CREATE TABLE app_user(id INTEGER PRIMARY KEY,username TEXT,student_number TEXT);'
            'CREATE TABLE refresh_session(id INTEGER PRIMARY KEY,user_id INTEGER);'
            'CREATE TABLE consumed_event(id INTEGER PRIMARY KEY,consumer_name TEXT,event_id TEXT);')
        def sql(query):
            if query.startswith('START TRANSACTION;'):
                # SQLite adapter only translates MySQL's joined-delete syntax;
                # its real row effects remain observable (including the old unsafe deletes).
                query = re.sub(r'DELETE c FROM consumed_event c JOIN outbox_event o ON o.event_id=c.event_id WHERE o.organization_id=(\d+)',
                    r'DELETE FROM consumed_event WHERE event_id IN (SELECT event_id FROM outbox_event WHERE organization_id=\1)', query)
                query = re.sub(r"DELETE s FROM refresh_session s JOIN app_user u ON u.id=s.user_id WHERE u.username='([^']+)'",
                    r"DELETE FROM refresh_session WHERE user_id IN (SELECT id FROM app_user WHERE username='\1')", query)
                db.executescript(query.replace('START TRANSACTION;', 'BEGIN;'))
                return ''
            rows = db.execute(query).fetchall()
            return '\n'.join('\t'.join(str(value) for value in row) for row in rows)
        driver.sql = sql
        driver.count = lambda query: int(sql(query))
        driver.cleanup_redis = lambda: None
        return db

    def base(self):
        return 4_000_000_000_000_000 + (uuid.UUID(RUN).int % 1_000_000_000_000) * 100

    def test_topics_only_verifies_host_created_names_via_typed_list(self):
        driver = self.driver()
        driver.project = 'vf5' + RUN.replace('-', '')
        names = [driver.project+'.'+suffix+ending for suffix in
                 ('activity-events.v1', 'registration-events.v1', 'checkin-events.v1') for ending in ('', '.DLT')]
        calls = []
        def rpc(action, values, **kwargs):
            calls.append((action, values, kwargs))
            return '\n'.join(names)
        driver.rpc = rpc
        driver.execute = lambda *args, **kwargs: self.fail('Generic Kafka command attempted')
        driver.topics()
        self.assertEqual([('kafka-list-topics', [], {'timeout': 30})], calls)
        names.pop()
        with self.assertRaisesRegex(ValueError, 'Host-prepared'):
            driver.topics()

    def test_duplicate_uses_one_typed_topic_and_stdin_not_argv(self):
        driver = self.driver()
        driver.project = 'vf5' + RUN.replace('-', '')
        event = '00000000-0000-0000-0000-000000000001'
        driver.state = {'1': {'event': event}}
        value = json.dumps({'eventId': event})
        driver.sql = lambda query: '313a32\t'+value.encode().hex()+'\t'+driver.project+'.registration-events.v1'
        samples = iter((1, 2))
        driver.metric = lambda name: next(samples)
        driver.wait = lambda predicate, message: self.assertTrue(predicate())
        driver.terminal = lambda index: [1, 1, 1]
        driver.snapshot = lambda *args: None
        driver.execute = lambda *args, **kwargs: self.fail('Generic Kafka command attempted')
        calls = []
        driver.rpc = lambda *args, **kwargs: calls.append((args, kwargs))
        driver.duplicate()
        self.assertEqual([(('kafka-produce-duplicate', [driver.project+'.registration-events.v1'],
                           ('1:2\t'+value+'\n').encode()), {'timeout': 45})], calls)

    def test_each_orphan_scope_blocks_seed_before_accounts_and_cleanup_preserves_it(self):
        # No FK: every child table can hold historical rows for an absent organization.
        for table, key in ORG_TABLE_KEYS.items():
            with self.subTest(table=table), tempfile.TemporaryDirectory() as directory:
                driver = acceptance.Driver.__new__(acceptance.Driver)
                driver.root = Path(directory)
                driver.evidence = driver.root
                driver.state_file = driver.root / 'state.json'
                driver.state = {}
                driver.run_id = RUN
                db = self.database(driver)
                db.execute(f'INSERT INTO {table}({key},organization_id) VALUES(77,?)', (self.base(),))
                db.commit()
                driver.api = lambda *args, **kwargs: self.fail('Seed issued HTTP with a historical orphan')
                with self.assertRaisesRegex(ValueError, 'cleanup scope'):
                    driver.seed()
                driver.cleanup()
                self.assertEqual(1, db.execute(f'SELECT COUNT(*) FROM {table} WHERE {key}=77').fetchone()[0])

    def test_partial_seed_transport_failure_removes_only_recorded_new_accounts_and_sessions(self):
        driver = self.driver()
        db = self.database(driver)
        db.execute('INSERT INTO refresh_session VALUES(900,102)')  # orphan refers to a future account ID
        db.execute("INSERT INTO consumed_event VALUES(901,'other-consumer','historical-event')")
        db.commit()
        created = []
        def api(path, body=None, **kwargs):
            if path.endswith('/register'):
                identity = 101 + len(created)
                db.execute('INSERT INTO app_user VALUES(?,?,?)', (identity,body['username'],body['studentNumber']))
                db.execute('INSERT INTO refresh_session VALUES(?,?)', (identity+100,identity))
                db.commit()
                created.append(identity)
                if identity == 102:
                    raise ValueError('Simulated response loss after account commit')
                return {'data': {'accessToken': 'offline-token'}}
            return {'data': {'id': created[-1]}}
        driver.api = api
        with self.assertRaisesRegex(ValueError, 'response loss'):
            driver.seed()
        self.assertEqual(2, db.execute('SELECT COUNT(*) FROM app_user').fetchone()[0])
        # Recover using only the persisted ownership manifest, as a new driver process would.
        driver.state = json.loads(driver.state_file.read_text())
        driver.cleanup()
        self.assertEqual([], db.execute('SELECT id FROM app_user').fetchall())
        self.assertEqual([(900,102)], db.execute('SELECT id,user_id FROM refresh_session').fetchall())
        self.assertEqual([(901,)], db.execute('SELECT id FROM consumed_event').fetchall())

    def test_cleanup_refuses_unrecorded_rows_before_deleting_any_recorded_rows(self):
        driver = self.driver()
        db = self.database(driver)
        # Use a real pre-seed receipt but deliberately interrupt before any account creation.
        driver.api = lambda *args, **kwargs: (_ for _ in ()).throw(ValueError('stop after preflight'))
        with self.assertRaisesRegex(ValueError, 'stop after preflight'):
            driver.seed()
        db.execute('INSERT INTO registration(id,organization_id) VALUES(51,?)', (self.base(),))
        db.commit()
        with self.assertRaisesRegex(ValueError, 'Unrecorded cleanup rows'):
            driver.cleanup()
        self.assertEqual([(51,)], db.execute('SELECT id FROM registration').fetchall())

    def test_environment_rejects_duplicates_interpolation_and_unknown_keys(self):
        path = self.root / 'input.env'
        values = {key: 'example' for key in acceptance.REQUIRED}
        values.update(DB_PORT='3307', REDIS_PORT='6379', SPRING_FLYWAY_ENABLED='false',
                      STAGE5_MAINTENANCE_APPROVED='true')
        clean = '\n'.join(key + '=' + value for key, value in values.items()) + '\n'
        path.write_text(clean)
        self.assertEqual('3307', acceptance.read_env(path)['DB_PORT'])
        for suffix in ('DB_PORT=3308\n', 'DOCKER_HOST=unsafe\n', 'REDIS_PASSWORD=$(touch unsafe)\n',
                       'REDIS_PASSWORD="secret"\n', 'REDIS_PASSWORD=path\\secret\n'):
            path.write_text(clean + suffix)
            with self.assertRaises(ValueError):
                acceptance.read_env(path)

    def preflight_driver(self):
        index = getattr(self, '_preflight_index', 0)
        self._preflight_index = index + 1
        root = self.root / ('preflight-' + str(index))
        (root / 'evidence').mkdir(parents=True)
        (root / 'private').mkdir()
        driver = acceptance.Driver.__new__(acceptance.Driver)
        driver.run_id = RUN
        driver.root = root
        driver.evidence = root / 'evidence'
        driver.state_file = root / 'state.json'
        driver.state = {}
        driver.project = 'vf5' + RUN.replace('-', '')
        driver.pipe = False
        driver.env = {
            'REDIS_HOST': '192.168.40.144', 'REDIS_PORT': '6379', 'REDIS_DATABASE': '2',
            'REDIS_PASSWORD': 'redis-private-marker', 'STAGE5_MYSQL_CONTAINER': 'mysql-web',
            'STAGE5_REDIS_CONTAINER': 'redis-web'
        }
        driver.child_env = {}
        return driver

    def test_preflight_checks_both_existing_store_identities_before_redis_client(self):
        for failure in ('missing', 'stopped', 'ambiguous', 'wrong-name', 'short-id', 'duplicate-id'):
            with self.subTest(failure=failure):
                driver = self.preflight_driver()
                calls = []
                def execute(command, *args, **kwargs):
                    calls.append(command)
                    if command[:2] == ['docker', 'inspect']:
                        if failure == 'missing':
                            raise ValueError('container missing')
                        identity = MYSQL_ID if command[-1] == 'mysql-web' else REDIS_ID
                        if failure == 'short-id' and command[-1] == 'redis-web':
                            identity = REDIS_ID[:12]
                        if failure == 'duplicate-id' and command[-1] == 'redis-web':
                            identity = MYSQL_ID
                        name = '/' + command[-1]
                        if failure == 'wrong-name' and command[-1] == 'redis-web':
                            name = '/replacement'
                        value = {'id': identity, 'name': name, 'image': 'redis:7',
                                 'running': failure != 'stopped'}
                        text = json.dumps(value)
                        return text + ('\n' + text if failure == 'ambiguous' else '')
                    return 'safe-version\n'
                driver.execute = execute
                with self.assertRaises((ValueError, json.JSONDecodeError)):
                    driver.preflight()
                self.assertFalse(any(command and command[0] == 'redis-cli' for command in calls))

    def test_preflight_preserves_configured_redis_endpoint_and_requires_version_ping_and_empty_namespace(self):
        driver = self.preflight_driver()
        calls = []
        def execute(command, *args, **kwargs):
            calls.append((command, kwargs.get('extra_env')))
            if command[:2] == ['docker', 'inspect']:
                identity = MYSQL_ID if command[-1] == 'mysql-web' else REDIS_ID
                return json.dumps({'id': identity, 'name': '/' + command[-1],
                                   'image': 'redis:7', 'running': True})
            if command == ['redis-cli', '--container-id', REDIS_ID, '--version']:
                return 'redis-cli 7.2.5\n'
            if command[-1:] == ['PING']:
                return 'PONG\n'
            if '--scan' in command:
                return ''
            if command[:2] == ['docker', 'version']:
                return '27.0\n'
            return 'safe-version\n'
        driver.execute = execute
        with patch.object(acceptance.os, 'uname', create=True,
                          return_value=SimpleNamespace(sysname='Linux', release='offline')), \
             patch.object(acceptance.os, 'sysconf', create=True, return_value=1024):
            driver.preflight()
        redis_calls = [item for item in calls if item[0][0] == 'redis-cli']
        self.assertEqual(['redis-cli', '--container-id', REDIS_ID, '--version'], redis_calls[0][0])
        for command, environment in redis_calls[1:]:
            self.assertEqual(['redis-cli', '--container-id', REDIS_ID, '--raw', '-h',
                              '192.168.40.144', '-p', '6379', '-n', '2'], command[:10])
            self.assertEqual({'REDISCLI_AUTH': 'redis-private-marker'}, environment)
        self.assertEqual('PING', redis_calls[1][0][-1])
        self.assertEqual(['--scan', '--pattern', 'volunteerflow:' + driver.project + ':*'],
                         redis_calls[2][0][-3:])
        evidence = json.loads((driver.evidence / 'environment.json').read_text())
        self.assertEqual('redis-cli 7.2.5', evidence['redisVersion'])
        redacted = (driver.evidence / 'environment-redacted.txt').read_text()
        self.assertIn('REDIS_PASSWORD=[REDACTED]', redacted)
        self.assertNotIn('redis-private-marker', redacted)

    def test_missing_container_redis_cli_stops_before_ping_or_scan(self):
        driver = self.preflight_driver()
        calls = []
        def execute(command, *args, **kwargs):
            calls.append(command)
            if command[:2] == ['docker', 'inspect']:
                identity = MYSQL_ID if command[-1] == 'mysql-web' else REDIS_ID
                return json.dumps({'id': identity, 'name': '/' + command[-1],
                                   'image': 'redis:7', 'running': True})
            if command == ['redis-cli', '--container-id', REDIS_ID, '--version']:
                raise ValueError('redis-cli unavailable in configured container')
            return 'safe-version\n'
        driver.execute = execute
        with self.assertRaisesRegex(ValueError, 'redis-cli unavailable'):
            driver.preflight()
        self.assertFalse(any(command[-1:] == ['PING'] or '--scan' in command for command in calls))

    def test_redis_version_evidence_rejects_empty_arbitrary_and_multiline_output_before_ping(self):
        invalid_versions = (
            '', 'safe-version', 'redis-cli 7.2.5\nsecond-line',
            '\nredis-cli 7.2.5\n', 'redis-cli 7.2.5\n\n',
            '\tredis-cli 7.2.5\n', 'redis-cli 7.2.5\t\n',
            'redis-cli 7.2.5\r', '\x1fredis-cli 7.2.5\n',
            ' ' * 201 + 'redis-cli 7.2.5\n',
        )
        for version in invalid_versions:
            with self.subTest(version=version):
                driver = self.preflight_driver()
                def execute(command, *args, **kwargs):
                    if command[:2] == ['docker', 'inspect']:
                        identity = MYSQL_ID if command[-1] == 'mysql-web' else REDIS_ID
                        return json.dumps({'id': identity, 'name': '/' + command[-1],
                                           'image': 'redis:7', 'running': True})
                    if command[0] == 'redis-cli':
                        if command[-1] == '--version':
                            return version
                        self.fail('PING/SCAN ran after invalid Redis version evidence')
                    return 'safe-version\n'
                driver.execute = execute
                with self.assertRaisesRegex(ValueError, 'Redis client version'):
                    driver.preflight()

    def test_redis_cleanup_is_exactly_bounded_to_run_namespace_and_batches_of_one_hundred(self):
        driver = self.preflight_driver()
        driver.project = 'vf5' + 'b' * 32
        prefix = 'volunteerflow:' + driver.project + ':'
        keys = [prefix + str(index) for index in range(1000)]
        calls = []
        scans = iter(('\n'.join(keys), ''))
        def redis(*arguments):
            calls.append(arguments)
            return next(scans) if arguments[0] == '--scan' else str(len(arguments) - 1)
        driver.redis = redis
        driver.cleanup_redis()
        deletes = [arguments for arguments in calls if arguments[0] == 'DEL']
        self.assertEqual(10, len(deletes))
        self.assertTrue(all(len(arguments) == 101 for arguments in deletes))
        self.assertEqual(keys, [key for arguments in deletes for key in arguments[1:]])
        driver.redis = lambda *arguments: '\n'.join(keys + [prefix + 'overflow'])
        with self.assertRaisesRegex(ValueError, 'bound exceeded'):
            driver.cleanup_redis()

    def test_submit_asserts_real_audit_schema_and_persists_exact_event(self):
        driver = self.driver()
        driver.state = {'org': 11, 'activity': 12, 'positions': [13], 'users': [{'id': 14, 'token': 'test-token'}]}
        event = '11111111-1111-1111-1111-111111111111'
        db = sqlite3.connect(':memory:')
        self.addCleanup(db.close)
        db.executescript("CREATE TABLE outbox_event(organization_id,aggregate_id,event_type,event_id);"
                         "CREATE TABLE audit_log(organization_id,resource_id,action);"
                         "INSERT INTO audit_log VALUES(11,16,'registration.submitted');"
                         "INSERT INTO outbox_event VALUES(11,'15','registration.submitted.v1','" + event + "');")
        driver.api = lambda *args: {'data': {'registrationId': '15', 'cycleId': '16', 'status': 'CONFIRMED'}}
        driver.sql = lambda query: '\n'.join(str(row[0]) for row in db.execute(query).fetchall())
        driver.count = lambda query: int(driver.sql(query))
        self.assertEqual(event, driver.submit(0))
        self.assertEqual(event, json.loads(driver.state_file.read_text())['0']['event'])

    def test_seed_refuses_existing_org_id_before_any_account_request(self):
        driver = self.driver()
        driver.count = lambda query: 1 if 'FROM organization WHERE id=' in query else 0
        def forbidden_request(*args, **kwargs):
            self.fail('Account request occurred before ID collision preflight')
        driver.api = forbidden_request
        with self.assertRaisesRegex(ValueError, 'Fixture ID collision'):
            driver.seed()

    def test_seed_checks_all_planned_usernames_before_creating_the_first_account(self):
        driver = self.driver()
        driver.count = lambda query: 1 if "username='s5-" + RUN + "-2'" in query else 0
        driver.api = lambda *args, **kwargs: self.fail('Created account before checking third username')
        with self.assertRaisesRegex(ValueError, 'Fixture username collision'):
            driver.seed()

    def test_migration_gate_rejects_original_scheduled_work_before_backend_start(self):
        driver = self.driver()
        def count(query):
            if 'information_schema.columns' in query:
                return 3
            if 'information_schema.tables' in query:
                return 2
            if 'FROM activity ' in query:
                return 1
            return 0
        driver.count = count
        with self.assertRaisesRegex(ValueError, 'Original scheduled work'):
            driver.migration_check()

    def test_metrics_evidence_does_not_double_count_shared_database_gauges(self):
        driver = self.driver()
        def api(path, **kwargs):
            if path == '/actuator/metrics':
                return {'names': ['kafka.consumer.fetch.manager.records.lag.max']}
            return {'measurements': [{'value': 1.0}]}
        driver.api = api
        driver.metrics('before')
        evidence = json.loads((driver.evidence / 'metrics-before.json').read_text())
        self.assertEqual(1.0, evidence['perInstance']['backend-a']['outbox.pending.count'])
        self.assertEqual(1.0, evidence['perInstance']['backend-b']['outbox.pending.count'])
        self.assertNotIn('outbox.pending.count', evidence['counterSums'])

    def test_cleanup_refuses_wrong_organization_owner_without_issuing_delete(self):
        driver = self.driver()
        driver.state = {'org': 11, 'names': [], 'scope_preflight': True}
        driver.count = lambda query: 1 if "name<>" in query else 0
        driver.sql = lambda query: self.fail('Cleanup issued SQL for another organization')
        with self.assertRaisesRegex(ValueError, 'Fixture ID collision'):
            driver.cleanup()

    def test_secret_scan_rejects_credentials_tokens_and_writes_pass_only_if_clean(self):
        driver = self.driver()
        driver.env = {'DB_PASSWORD': 'unique-secret-marker'}
        artifact = driver.evidence / 'sample.json'
        for value in ('unique-secret-marker', 'Bearer abcdefghijklmnop'):
            artifact.write_text(value)
            with self.assertRaisesRegex(ValueError, 'secret scan failed'):
                driver.scan()
            self.assertFalse((driver.evidence / 'secret-scan.txt').exists())
        artifact.write_text('{"count":1}')
        driver.scan()
        self.assertEqual('PASS\n', (driver.evidence / 'secret-scan.txt').read_text())

    def test_metrics_authentication_uses_stdin_and_never_process_arguments(self):
        driver = self.driver()
        driver.state = {'users': [{'token': 'fixture-token'}]}
        driver.compose = ['docker', 'compose']
        observed = []
        def execute(command, data=None, timeout=60):
            observed.append((command, data))
            return '{"measurements":[{"statistic":"COUNT","value":1}]}'
        driver.execute = execute
        driver.api('/actuator/metrics/outbox.pending.count')
        self.assertIn(b'Authorization: Bearer fixture-token', observed[0][1])
        self.assertNotIn('fixture-token', ' '.join(observed[0][0]))

    def test_lag_sampling_ignores_idle_instance_without_inventing_a_sample(self):
        driver = self.driver()
        name = 'kafka.consumer.fetch.manager.records.lag.max'
        def api(path, **kwargs):
            if path == '/actuator/metrics':
                return {'names': [name] if kwargs['backend'] == 'backend-a' else []}
            self.assertEqual('backend-a', kwargs['backend'])
            return {'measurements': [{'value': 2.0}]}
        driver.api = api
        self.assertEqual(2.0, driver.metric(name))

    def test_redis_cleanup_refuses_a_key_outside_exact_run_namespace(self):
        driver = self.driver()
        driver.project = 'vf5' + RUN.replace('-', '')
        commands = []
        def redis(*args):
            commands.append(args)
            return 'volunteerflow:other-run:registration:key\n'
        driver.redis = redis
        with self.assertRaisesRegex(ValueError, 'Foreign Redis key'):
            driver.cleanup_redis()
        self.assertFalse(any('DEL' in command for command in commands))

    def test_database_cleanup_also_removes_its_exact_redis_namespace(self):
        driver = self.driver()
        self.database(driver)
        driver.api = lambda *args, **kwargs: (_ for _ in ()).throw(ValueError('stop after preflight'))
        with self.assertRaisesRegex(ValueError, 'stop after preflight'):
            driver.seed()
        issued = []
        driver.cleanup_redis = lambda: issued.append('redis-run-cleanup')
        driver.cleanup()
        self.assertEqual('redis-run-cleanup', issued[-1])

    def test_existing_consumed_orphan_with_future_event_key_is_never_claimed_or_deleted(self):
        driver = self.driver()
        db = self.database(driver)
        event = '11111111-1111-1111-1111-111111111111'
        db.execute("INSERT INTO consumed_event VALUES(901,'volunteerflow-notification-v1',?)", (event,))
        db.commit()
        driver.api = lambda *args, **kwargs: (_ for _ in ()).throw(ValueError('stop after preflight'))
        with self.assertRaisesRegex(ValueError, 'stop after preflight'):
            driver.seed()
        db.execute('INSERT INTO outbox_event VALUES(51,?,?)', (self.base(),event))
        db.commit()
        driver.checkpoint_created()
        self.assertEqual([], driver.state['created']['consumed_event'])
        driver.cleanup()
        self.assertEqual([(901,)], db.execute('SELECT id FROM consumed_event').fetchall())
        self.assertEqual([], db.execute('SELECT id FROM outbox_event').fetchall())


class KeyRemovalTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.identity = self.root / 'identity'
        self.identity.write_text('offline-private-marker')
        Path(str(self.identity) + '.pub').write_text('ssh-ed25519 AAAA volunteerflow-stage4d-' + RUN)
        self.status = self.root / 'key-removal-status.json'

    def helper(self):
        path = Path(__file__).with_name('remove-temporary-key.py')
        self.assertTrue(path.exists(), 'Bounded Stage5 temporary-key helper must exist')
        spec = importlib.util.spec_from_file_location('key_removal', path)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_timeout_keeps_key_and_writes_failure_with_consistent_ssh_bounds(self):
        observed = []
        def run(command, **kwargs):
            observed.append((command,kwargs))
            raise subprocess.TimeoutExpired(command, kwargs['timeout'])
        code = self.helper().remove_key('vm.invalid', 'root', 22, self.identity, self.status, 'offline-ssh', run)
        self.assertEqual(124, code)
        self.assertTrue(self.identity.exists())
        self.assertTrue(Path(str(self.identity) + '.pub').exists())
        status = json.loads(self.status.read_text())
        self.assertEqual({'status': 'failed', 'exitCode': 124}, status)
        self.assertEqual(60, observed[0][1]['timeout'])
        for option in ('ConnectTimeout=10','ServerAliveInterval=15','ServerAliveCountMax=3','BatchMode=yes'):
            self.assertIn(option, observed[0][0])

    def test_nonzero_key_removal_is_not_reported_as_success_or_deleting_local_key(self):
        run = lambda command, **kwargs: subprocess.CompletedProcess(command, 23, b'', b'private-stderr-must-not-leak')
        code = self.helper().remove_key('vm.invalid', 'root', 22, self.identity, self.status, 'offline-ssh', run)
        self.assertEqual(23, code)
        self.assertTrue(self.identity.exists())
        self.assertEqual('failed', json.loads(self.status.read_text())['status'])
        self.assertNotIn('private-stderr', self.status.read_text())

    def test_local_keys_deleted_only_after_exact_remote_removal_success(self):
        observed = []
        def run(command, **kwargs):
            observed.append(kwargs['input'].decode())
            return subprocess.CompletedProcess(command, 0, b'', b'')
        code = self.helper().remove_key('vm.invalid', 'root', 22, self.identity, self.status, 'offline-ssh', run)
        self.assertEqual(0, code)
        self.assertFalse(self.identity.exists())
        self.assertFalse(Path(str(self.identity) + '.pub').exists())
        self.assertIn('grep -Fvx --', observed[0])
        self.assertIn('grep -Fxq --', observed[0])
        self.assertEqual('success', json.loads(self.status.read_text())['status'])

    def shell_process(self, script, fail_verification=False):
        bash = Path('C:/Program Files/Git/bin/bash.exe')
        command = str(bash) if bash.exists() else shutil.which('bash')
        self.assertTrue(command, 'Offline shell is required for key-filter behavior tests')
        environment = dict(os.environ)
        environment['STAGE5_TEST_AUTH_FILE'] = self.authorized.as_posix()
        script = script.replace('file="$HOME/.ssh/authorized_keys"', 'file="$STAGE5_TEST_AUTH_FILE"')
        if fail_verification:
            script = 'grep() { if [ "$1" = -Fxq ]; then return 2; fi; command grep "$@"; }\n' + script
        return subprocess.run([command, '-c', script], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              env=environment, timeout=5, check=False)

    def test_exact_remote_filter_preserves_every_other_authorized_key(self):
        self.authorized = self.root / 'authorized_keys'
        key = Path(str(self.identity) + '.pub').read_text()
        foreign = 'ssh-ed25519 BBBB another-user\n'
        self.authorized.write_text(foreign + key + '\n')
        run = lambda command, **kwargs: self.shell_process(kwargs['input'].decode())
        code = self.helper().remove_key('vm.invalid', 'root', 22, self.identity, self.status, 'offline-ssh', run)
        self.assertEqual(0, code)
        self.assertEqual(foreign, self.authorized.read_text())

    def test_remote_verification_read_failure_is_not_treated_as_key_absence(self):
        self.authorized = self.root / 'authorized_keys'
        self.authorized.write_text(Path(str(self.identity) + '.pub').read_text() + '\n')
        run = lambda command, **kwargs: self.shell_process(kwargs['input'].decode(), True)
        code = self.helper().remove_key('vm.invalid', 'root', 22, self.identity, self.status, 'offline-ssh', run)
        self.assertNotEqual(0, code)
        self.assertTrue(self.identity.exists())
        self.assertEqual('failed', json.loads(self.status.read_text())['status'])


if __name__ == '__main__':
    unittest.main()

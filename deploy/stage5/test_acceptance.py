"""Offline tests: real driver logic; doubles exist only at HTTP/SQL process boundaries."""
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).with_name('acceptance.py'))
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)
RUN = '00000000-0000-0000-0000-000000000001'


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
        driver.state = {'org': 11, 'names': []}
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
        driver.state = {'org': 11, 'names': []}
        driver.count = lambda query: 0
        issued = []
        driver.sql = lambda query: issued.append(query)
        driver.cleanup_redis = lambda: issued.append('redis-run-cleanup')
        driver.cleanup()
        self.assertEqual('redis-run-cleanup', issued[-1])


if __name__ == '__main__':
    unittest.main()

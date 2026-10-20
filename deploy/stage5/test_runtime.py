"""Offline container runtime regression tests."""
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import tarfile
import threading
import time
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
BASH = 'C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else 'bash'

class RuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / 'private').mkdir()
        (self.root / 'evidence').mkdir()
        (self.root / 'stage5.env').write_text('DB_PASSWORD=private-marker\n')
        (self.root / 'acceptance.py').write_text('print("safe")\n')
        self.digest = hashlib.sha256((self.root / 'acceptance.py').read_bytes()).hexdigest()

    def shell(self, body, **env):
        runtime = HERE / 'python-runtime.sh'
        self.assertTrue(runtime.exists(), 'missing repo-owned container runtime')
        fake = '''
docker() {
  printf '%s\\0' "$@" >> "$TEST_ROOT/argv"
  printf '\\n' >> "$TEST_ROOT/argv"
  case "$1 $2" in
    'version --format') [[ ${FAIL_DOCKER:-0} == 1 ]] && return 42; printf '27.0\\n' ;;
    'compose version') printf 'v2.29\\n' ;;
    'image inspect') [[ ${FAIL_IMAGE:-0} == 1 ]] && return 1; printf 'sha256:%064d|["python@sha256:abc"]\\n' 1 ;;
    'pull --quiet') [[ ${FAIL_PULL:-0} == 1 ]] && return 1 ;;
    'run --rm') return "${FAIL_RUN:-0}" ;;
  esac
}
python3() { printf 'HOST_PYTHON_CALLED' >&2; return 127; }
export -f docker python3
timeout() { printf '%s\\0' "$@" >> "$TEST_ROOT/timeouts"; while [[ $1 == --* ]]; do shift; done; shift; "$@"; }
export -f timeout
'''
        return subprocess.run([BASH, '-c', f'source "{runtime.as_posix()}"\n' + fake + body],
            capture_output=True, text=True, timeout=15,
            env={**os.environ, 'TEST_ROOT': self.root.as_posix(), 'EXPECTED_HASH': self.digest, **env})

    def invoke(self):
        return 'stage5_python_driver "$TEST_ROOT" "$TEST_ROOT/acceptance.py" "$EXPECTED_HASH" 00000000-0000-0000-0000-000000000001 vf500000000000000000000000000000001 scan\n'

    def test_missing_host_python_uses_sandboxed_digest_image_and_exact_mounts(self):
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"\n' + self.invoke())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn('private-marker', result.stdout + result.stderr)
        self.assertNotIn('HOST_PYTHON_CALLED', result.stdout + result.stderr)
        argv = (self.root / 'argv').read_text().split('\0')
        for arg in ('--network', 'none', '--read-only', '--cap-drop', 'ALL', '--security-opt',
                    'no-new-privileges', '--cpus', '1', '--memory', '768m', '--pids-limit', '64',
                    '--user', '--log-driver', '--mount'):
            self.assertIn(arg, argv)
        self.assertTrue(any('python@sha256:' in a and len(a.split('sha256:')[1]) == 64 for a in argv))
        self.assertFalse(any('docker.sock' in a or a == '--privileged' or 'private-marker' in a for a in argv))
        mounts = [argv[i+1] for i, arg in enumerate(argv[:-1]) if arg == '--mount']
        self.assertEqual(4, len(mounts), 'mount only source, dotenv, private state and evidence')
        self.assertTrue(any('acceptance.py' in m and 'readonly' in m for m in mounts))
        self.assertTrue(any('stage5.env' in m and 'readonly' in m for m in mounts))
        recorded = (self.root / 'evidence/python-runtime.txt').read_text()
        self.assertIn('sha256:', recorded)
        self.assertIn('uid=', recorded)
        uid = self.shell('id -u').stdout.strip()
        gid = self.shell('id -g').stdout.strip()
        self.assertEqual(uid + ':' + gid, argv[argv.index('--user') + 1])
        cidfile = argv[argv.index('--cidfile') + 1]
        self.assertNotIn('/private/', cidfile, 'container ID must not be container-writable')
        timeouts = (self.root / 'timeouts').read_bytes()
        self.assertIn(b'--signal=TERM\0--kill-after=10\0' + b'600\0docker\0run\0', timeouts)

    def test_bad_hash_refuses_before_container(self):
        result = self.shell(self.invoke(), EXPECTED_HASH='0'*64)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('hash', result.stderr)
        self.assertFalse((self.root / 'argv').exists())

    def test_pull_failure_is_explicit_without_running_python(self):
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"', FAIL_IMAGE='1', FAIL_PULL='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('image unavailable', result.stderr)
        self.assertNotIn('run', (self.root / 'argv').read_text().split('\0'))

    def test_docker_failure_precedes_image_pull(self):
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"', FAIL_DOCKER='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Docker', result.stderr)
        self.assertNotIn('pull', (self.root / 'argv').read_text().split('\0'))

    def test_container_failure_stays_failure_and_removes_transport(self):
        for code in ('37', '124'):
            result = self.shell(self.invoke(), FAIL_RUN=code)
            self.assertEqual(int(code), result.returncode, result.stderr)
            self.assertFalse((self.root / 'private/bridge/request.ready').exists())

    def test_bridge_without_host_response_has_bounded_deadline(self):
        spec = importlib.util.spec_from_file_location('acceptance_deadline_test', HERE / 'acceptance.py')
        acceptance = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(acceptance)
        driver = acceptance.Driver.__new__(acceptance.Driver)
        driver.bridge = self.root / 'private/bridge'
        driver.bridge.mkdir()
        with patch.object(acceptance.time, 'monotonic', side_effect=[0, 20]):
            with self.assertRaisesRegex(ValueError, 'deadline exceeded'):
                driver.execute(['mysql', '--version'], timeout=1)

    def test_real_client_and_host_bridge_keep_sql_and_redis_secrets_off_argv_and_output(self):
        spec = importlib.util.spec_from_file_location('acceptance_runtime_test', HERE / 'acceptance.py')
        acceptance = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(acceptance)
        driver = acceptance.Driver.__new__(acceptance.Driver)
        driver.bridge = self.root / 'private/bridge'
        driver.bridge.mkdir()
        (self.root / 'stage5.env').write_text('DB_NAME=demo\nREDIS_HOST=127.0.0.1\nREDIS_PORT=6379\n')
        # A real Bash bridge services the real Python client's protected request files.
        body = f'''source "{(HERE / 'python-runtime.sh').as_posix()}"
mysql() {{ [[ $(cat) == private-marker ]] || return 9; printf '42'; }}
redis-cli() {{ [[ $REDISCLI_AUTH == redis-private-marker ]] || return 9; printf 'PONG'; }}
timeout() {{ printf '%s\\n' "$3" >> "$TEST_ROOT/request-budgets"; while [[ $1 == --* ]]; do shift; done; shift; "$@"; }}
export -f mysql redis-cli timeout
for request in 1 2; do
  for attempt in {{1..300}}; do [[ -f "$TEST_ROOT/private/bridge/request.ready" ]] && break; sleep 0.01; done
  stage5_bridge_request "$TEST_ROOT" vf5test "$TEST_ROOT/private/bridge" 1 || exit 9
done
'''
        process = subprocess.Popen([BASH, '-c', body], stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={**os.environ, 'TEST_ROOT': self.root.as_posix()})
        try:
            result = driver.execute(['mysql', '--defaults-extra-file=' + self.root.as_posix() + '/private/mysql-private.cnf',
                '--connect-timeout=5', '--batch', '--raw', '--skip-column-names', 'demo'], b'private-marker', timeout=3)
            self.assertEqual('42', result)
            self.assertNotIn('private-marker', (driver.bridge / 'argv').read_text())
            result = driver.execute(['redis-cli', '--raw', '-h', '127.0.0.1', '-p', '6379', '-n', '2', 'PING'],
                timeout=3, extra_env={'REDISCLI_AUTH': 'redis-private-marker'})
            self.assertEqual('PONG', result)
            self.assertNotIn('redis-private-marker', (driver.bridge / 'argv').read_text())
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(0, process.returncode, stderr)
            self.assertNotIn(b'private-marker', stdout + stderr)
            self.assertEqual(['1', '1'], (self.root / 'request-budgets').read_text().splitlines(),
                             'host command deadline must not outlive remaining action budget')
        except Exception as error:
            if process.poll() is None:
                process.kill()
            stdout, stderr = process.communicate()
            self.fail('Protected local bridge request failed: ' + type(error).__name__)
        finally:
            if process.poll() is None:
                process.kill()
                process.communicate()

    def test_bridge_refuses_arbitrary_commands_foreign_keys_and_foreign_projects(self):
        (self.root / 'stage5.env').write_text('DB_NAME=demo\nREDIS_HOST=127.0.0.1\nREDIS_PORT=6379\n')
        for command in ('bash -c true', 'docker stop mysql',
                        'redis-cli --raw -h 127.0.0.1 -p 6379 -n 2 DEL foreign:key',
                        'mysql --password=private-marker demo',
                        'docker compose -p foreign -f other.yml up'):
            with self.subTest(command=command):
                result = self.shell('stage5_bridge_allowed "$TEST_ROOT" vf5test ' + command)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn('private-marker', result.stdout + result.stderr)

    def test_runner_hashes_exact_archive_bytes_not_crlf_checkout_or_empty_show_output(self):
        helper = HERE / 'archive-driver-hash.py'
        self.assertTrue(helper.exists(), 'archive hash helper required')
        archive = self.root / 'source with spaces.tar'
        original = b"print('LF')\n"
        with tarfile.open(archive, 'w') as tar:
            member = tarfile.TarInfo('deploy/stage5/acceptance.py')
            member.size = len(original)
            tar.addfile(member, io.BytesIO(original))
        result = subprocess.run([os.sys.executable, '-B', str(helper), str(archive)],
            capture_output=True, text=True, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(hashlib.sha256(original).hexdigest(), result.stdout.strip())
        self.assertNotEqual(hashlib.sha256(original.replace(b'\n', b'\r\n')).hexdigest(), result.stdout.strip())
        with tarfile.open(archive, 'a') as tar:
            tar.addfile(member, io.BytesIO(original))
        result = subprocess.run([os.sys.executable, '-B', str(helper), str(archive)],
            capture_output=True, text=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn("print('LF')", result.stdout + result.stderr)

    def test_remote_no_longer_requires_host_python(self):
        root = Path(__file__).parent
        for name in ('remote-stage5.sh', 'replay-dlt.sh'):
            self.assertNotRegex((root / name).read_text(), r'(?m)^\s*(?:timeout\s+\d+\s+)?python3?\s')
        self.assertTrue((root / 'python-runtime.sh').exists())

if __name__ == '__main__':
    unittest.main()

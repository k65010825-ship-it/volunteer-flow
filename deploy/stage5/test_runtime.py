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
        (self.root / 'host-private').mkdir()
        (self.root / 'host-private/mysql.cnf').write_text('[client]\n')
        (self.root / 'container.env').write_text('safe\n')
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
    'run --rm') printf 'VF5|1|00000001|done|0||\\n'; return "${FAIL_RUN:-0}" ;;
  esac
}
python3() { printf 'HOST_PYTHON_CALLED' >&2; return 127; }
export -f docker python3
timeout() { printf '%s\\0' "$@" >> "$TEST_ROOT/timeouts"; while [[ $1 == --* ]]; do shift; done; shift; "$@"; }
export -f timeout
id() { [[ $1 != -u ]] || { printf '%s\\n' "${FAKE_UID:-0}"; return; }; command id "$@"; }
export -f id
chmod() { printf '%s\\n' "$*" >> "$TEST_ROOT/modes"; }
export -f chmod
ulimit() { return 0; }
export -f ulimit
'''
        return subprocess.run([BASH, '-c', f'source "{runtime.as_posix()}"\n' + fake + body],
            capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=15,
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
        self.assertTrue(any('container.env' in m and 'readonly' in m for m in mounts))
        self.assertFalse(any('/host-private,' in m or '/host-private/mysql.cnf' in m for m in mounts))
        recorded = (self.root / 'evidence/python-runtime.txt').read_text()
        self.assertIn('sha256:', recorded)
        self.assertIn('hostUid=0 containerUid=65534 containerGid=65534', recorded)
        self.assertEqual('65534:65534', argv[argv.index('--user') + 1])
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
        def stalled_read(*args):
            time.sleep(0.1)
            return b''
        with patch.object(acceptance.os, 'write', return_value=1), patch.object(acceptance.os, 'read', side_effect=stalled_read):
            with self.assertRaisesRegex(ValueError, 'deadline exceeded'):
                acceptance.pipe_exchange(b'x', 0.01)

    def test_non_root_runtime_is_refused_before_any_docker_call(self):
        result = self.shell(self.invoke(), FAKE_UID='1000')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('root', result.stderr)
        self.assertFalse((self.root / 'argv').exists())

    def test_host_evidence_publication_replaces_leaf_without_truncating_hardlink_target(self):
        outside = self.root / 'outside'
        outside.write_bytes(b'untouched')
        os.link(outside, self.root / 'evidence/acceptance.sha256')
        result = self.shell(self.invoke())
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b'untouched', outside.read_bytes())
        self.assertIn(self.digest, (self.root / 'evidence/acceptance.sha256').read_text())

    def test_host_configuration_and_state_ownership_are_distinct_and_explicit(self):
        (self.root / 'host-private/mysql.cnf').unlink()
        (self.root / 'container.env').unlink()
        (self.root / 'host-private').rmdir()
        (self.root / 'stage5.env').write_text('DB_HOST=127.0.0.1\nDB_PORT=3307\nDB_USERNAME=demo\nDB_PASSWORD=private-marker\nSPRING_FLYWAY_ENABLED=false\nSTAGE5_MAINTENANCE_APPROVED=true\n')
        result = self.shell('''
chown() { printf '%s\\n' "$*" >> "$TEST_ROOT/ownership"; }
mkdir() { [[ $1 != -m ]] || shift 2; command mkdir "$@"; }
chmod() { printf '%s\\n' "$*" >> "$TEST_ROOT/modes"; }
stage5_host_prepare "$TEST_ROOT"
''')
        self.addCleanup(lambda: (self.root / 'container.env').chmod(0o600) if (self.root / 'container.env').exists() else None)
        self.assertEqual(0, result.returncode, result.stderr)
        operations = (self.root / 'ownership').read_text()
        self.assertIn('0:0 ' + self.root.as_posix() + '/host-private', operations)
        self.assertIn('65534:65534 ' + self.root.as_posix() + '/private', operations)
        self.assertNotIn('private-marker', result.stdout + result.stderr)
        self.assertNotIn('init-command', (self.root / 'host-private/mysql.cnf').read_text())

    def test_all_host_evidence_capture_uses_private_then_atomic_replace(self):
        outside = self.root / 'outside'
        outside.write_bytes(b'untouched')
        os.link(outside, self.root / 'evidence/kafka-stop.txt')
        result = self.shell('stage5_capture_evidence "$TEST_ROOT" kafka-stop.txt printf safe')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(b'untouched', outside.read_bytes())
        self.assertEqual(b'safe', (self.root / 'evidence/kafka-stop.txt').read_bytes())

    def test_sql_validation_read_error_is_not_treated_as_no_match(self):
        (self.root / 'query').write_bytes(b'SELECT 1;')
        result = self.shell('grep() { return 2; }; stage5_sql_safe "$TEST_ROOT/query"')
        self.assertNotEqual(0, result.returncode)

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

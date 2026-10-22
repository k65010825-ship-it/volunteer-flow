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
        for artifact in ('argv', 'timeouts', 'offline-inspect-count', 'evidence/python-runtime.txt'):
            path = self.root / artifact
            if path.exists():
                path.unlink()
        fake = '''
docker() {
  printf '%s\\0' "$@" >> "$TEST_ROOT/argv"
  printf '\\n' >> "$TEST_ROOT/argv"
  case "$1 $2" in
    'version --format') [[ ${FAIL_DOCKER:-0} == 1 ]] && return 42; printf '27.0\\n' ;;
    'compose version') printf 'v2.29\\n' ;;
    'image inspect')
      image=${@: -1}
      if [[ $image == python@sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6 ]]; then
        [[ ${FAIL_IMAGE:-0} == 1 && ! -f $TEST_ROOT/pulled ]] && return 1
        printf '%s\\n' "${EXACT_INSPECT:-sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243|linux|amd64|[\\"python:3.13.12-slim-bookworm\\"]|[\\"python@sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6\\"]}"
      elif [[ $image == python:i-was-a-digest ]]; then
        [[ ${FAIL_OFFLINE_IMAGE:-0} == 1 ]] && return 1
        count_file="$TEST_ROOT/offline-inspect-count"
        count=0; [[ ! -f $count_file ]] || count=$(< "$count_file")
        count=$((count+1)); printf '%s' "$count" > "$count_file"
        if [[ $count == 1 ]]; then
          printf '%s\\n' "${OFFLINE_INSPECT_FIRST:-sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243|linux|amd64|[\\"python:i-was-a-digest\\"]|[]}"
        else
          printf '%s\\n' "${OFFLINE_INSPECT_SECOND:-${OFFLINE_INSPECT_FIRST:-sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243|linux|amd64|[\\"python:i-was-a-digest\\"]|[]}}"
        fi
      else
        return 1
      fi ;;
    'pull --quiet') [[ ${FAIL_PULL:-0} == 1 ]] && return 1; : > "$TEST_ROOT/pulled" ;;
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
        expected_id = 'sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243'
        self.assertIn(expected_id, argv)
        run_index = next(i for i, arg in enumerate(argv) if arg.endswith('run'))
        self.assertNotIn('python@sha256:', argv[run_index:])
        self.assertFalse(any('docker.sock' in a or a == '--privileged' or 'private-marker' in a for a in argv))
        mounts = [argv[i+1] for i, arg in enumerate(argv[:-1]) if arg == '--mount']
        self.assertEqual(4, len(mounts), 'mount only source, dotenv, private state and evidence')
        self.assertTrue(any('acceptance.py' in m and 'readonly' in m for m in mounts))
        self.assertTrue(any('container.env' in m and 'readonly' in m for m in mounts))
        self.assertFalse(any('/host-private,' in m or '/host-private/mysql.cnf' in m for m in mounts))
        recorded = (self.root / 'evidence/python-runtime.txt').read_text()
        self.assertIn('upstreamIndexDigest=sha256:a58daefb915e1e03ad48f3ca4df8832065412c5c35cacb9d39f4229184de12b6', recorded)
        self.assertIn('linuxAmd64ManifestDigest=sha256:3121f8b0804aa3698ab750d9a39ea4a42657a385c9b133722b915e55c51551a6', recorded)
        self.assertIn('expectedImageConfigDigest=' + expected_id, recorded)
        self.assertIn('offlineArchiveSha256=sha256:437e3a92118aa1e01954b202f11ac592d13da1105171a9fc480dc10fc2620f3b', recorded)
        self.assertIn('runtimeImageId=' + expected_id, recorded)
        self.assertNotIn('localRepoDigest=', recorded)
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
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"', FAIL_IMAGE='1',
                            FAIL_OFFLINE_IMAGE='1', FAIL_PULL='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('image unavailable', result.stderr)
        self.assertFalse(any(arg.lstrip('\n') == 'run' for arg in
                             (self.root / 'argv').read_text().split('\0')))

    def test_docker_failure_precedes_image_pull(self):
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"', FAIL_DOCKER='1')
        self.assertNotEqual(0, result.returncode)
        self.assertIn('Docker', result.stderr)
        self.assertFalse(any(arg.lstrip('\n') == 'pull' for arg in
                             (self.root / 'argv').read_text().split('\0')))

    def test_exact_official_reference_is_preferred_and_driver_uses_approved_image_id(self):
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"\n' + self.invoke())
        self.assertEqual(0, result.returncode, result.stderr)
        argv = (self.root / 'argv').read_text().split('\0')
        self.assertNotIn('python:i-was-a-digest', argv)
        self.assertFalse(any(arg.lstrip('\n') == 'pull' for arg in argv))
        self.assertIn('resolution=official-digest',
                      (self.root / 'evidence/python-runtime.txt').read_text())

    def test_verified_offline_tag_skips_pull_and_driver_uses_only_immutable_id(self):
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"\n' + self.invoke(),
                            FAIL_IMAGE='1')
        self.assertEqual(0, result.returncode, result.stderr)
        argv = (self.root / 'argv').read_text().split('\0')
        self.assertFalse(any(arg.lstrip('\n') == 'pull' for arg in argv))
        run_index = next(i for i, arg in enumerate(argv) if arg.endswith('run'))
        self.assertIn('sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243',
                      argv[run_index:])
        self.assertNotIn('python:i-was-a-digest', argv[run_index:])
        recorded = (self.root / 'evidence/python-runtime.txt').read_text()
        self.assertIn('resolution=verified-offline-tag', recorded)
        self.assertIn('observedRepoDigests=[]', recorded)

    def test_offline_fallback_rejects_untrusted_or_racing_inspection_without_secret_echo(self):
        valid = 'sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243|linux|amd64|["python:i-was-a-digest"]|[]'
        invalid_cases = {
            'short-id': 'sha256:64f864dc|linux|amd64|["python:i-was-a-digest"]|[]',
            'wrong-platform': valid.replace('|linux|amd64|', '|linux|arm64|'),
            'extra-tag': valid.replace('["python:i-was-a-digest"]', '["python:i-was-a-digest","evil:latest"]'),
            'unexpected-digest': valid[:-2] + '["evil@sha256:' + '1' * 64 + '"]',
            'malformed': 'private-marker\x7f' + valid,
            'multiple-lines': valid + '\n' + valid,
            'trailing-blank-line': valid + '\n',
        }
        for name, inspection in invalid_cases.items():
            with self.subTest(name=name):
                result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"', FAIL_IMAGE='1',
                                    OFFLINE_INSPECT_FIRST=inspection, OFFLINE_INSPECT_SECOND=inspection,
                                    FAIL_PULL='1')
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn('private-marker', result.stdout + result.stderr)
                self.assertFalse((self.root / 'evidence/python-runtime.txt').exists())

        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"', FAIL_IMAGE='1',
                            OFFLINE_INSPECT_FIRST=valid,
                            OFFLINE_INSPECT_SECOND=valid.replace('python:i-was-a-digest', 'evil:latest'),
                            FAIL_PULL='1')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('private-marker', result.stdout + result.stderr)

    def test_offline_fallback_accepts_one_canonical_tag_and_one_expected_manifest_digest(self):
        inspection = ('sha256:64f864dc81f0907bda2e1f08d208687d261f70ad0a67dbe56d88598f31ed7243'
                      '|linux|amd64|["index.docker.io/library/python:i-was-a-digest"]'
                      '|["index.docker.io/library/python@sha256:3121f8b0804aa3698ab750d9a39ea4a42657a385c9b133722b915e55c51551a6"]')
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"\n' + self.invoke(),
                            FAIL_IMAGE='1', OFFLINE_INSPECT_FIRST=inspection,
                            OFFLINE_INSPECT_SECOND=inspection)
        self.assertEqual(0, result.returncode, result.stderr)

    def test_missing_offline_tag_retains_bounded_exact_digest_pull(self):
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"', FAIL_IMAGE='1',
                            FAIL_OFFLINE_IMAGE='1')
        self.assertEqual(0, result.returncode, result.stderr)
        argv = (self.root / 'argv').read_text().split('\0')
        self.assertTrue(any(arg.lstrip('\n') == 'pull' for arg in argv))
        timeouts = (self.root / 'timeouts').read_bytes()
        self.assertIn(b'--signal=TERM\0--kill-after=10\0' +
                      b'180\0docker\0pull\0--quiet\0', timeouts)

    def test_container_failure_stays_failure_and_removes_transport(self):
        for code in ('37', '124'):
            result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"\n' + self.invoke(),
                                FAIL_RUN=code)
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
        result = self.shell('stage5_python_preflight "$TEST_ROOT/evidence"\n' + self.invoke())
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

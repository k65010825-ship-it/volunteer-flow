"""Adversarial offline pipe tests: no Docker daemon, SSH, MySQL or Redis service."""
import base64
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time
import unittest

HERE = Path(__file__).resolve().parent
BASH = 'C:/Program Files/Git/bin/bash.exe' if os.name == 'nt' else 'bash'
PROJECT = 'vf5test'


def encoded(value):
    return base64.b64encode(value).decode('ascii')


def request(argv=None, data=b'SELECT 1;', identity='00000001', seconds='3'):
    argv = argv or ['mysql', '--version']
    return ('VF5|1|' + identity + '|exec|' + seconds + '|' +
            encoded(('\0'.join(argv) + '\0').encode()) + '|' + encoded(data) + '\n').encode()


def done(identity='00000002'):
    return ('VF5|1|' + identity + '|done|0||\n').encode()


class PipeBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('private', 'evidence', 'host-private', 'bin'):
            (self.root / name).mkdir()
        (self.root / 'stage5.env').write_bytes(b'DB_NAME=demo\nREDIS_HOST=127.0.0.1\nREDIS_PORT=6379\n')
        (self.root / 'host-private/mysql.cnf').write_bytes(b'[client]\nhost=127.0.0.1\npassword=private-marker\n')
        fake = '''#!/usr/bin/env bash
printf '%s\\0' "$@" > "$TEST_ROOT/called.argv"
cat > "$TEST_ROOT/called.stdin"
case ${FAKE_MODE:-normal} in
  crash) exit 37 ;;
  hang) sleep 20 ;;
  bigout) head -c 16777217 /dev/zero ;;
  bigerr) head -c 65537 /dev/zero >&2 ;;
  *) printf '42' ;;
esac
'''
        self.fake = fake.replace('$TEST_ROOT', self.root.as_posix())
        (self.root / 'bin/mysql').write_bytes(self.fake.encode())
        (self.root / 'bin/mysql').chmod(0o755)

    def serve(self, frames, timeout=8, **env):
        bridge = HERE / 'pipe-bridge.sh'
        self.assertTrue(bridge.exists(), 'repo-owned bounded pipe bridge required')
        mode = env.get('FAKE_MODE', 'normal')
        (self.root / 'bin/mysql').write_bytes(self.fake.replace('${FAKE_MODE:-normal}', mode).encode())
        body = self.server_body()
        return subprocess.run([BASH, '-c', body], input=frames, capture_output=True, timeout=timeout,
            env={**os.environ, 'TEST_ROOT': self.root.as_posix(), **env})

    def server_body(self):
        return f'''source "{(HERE / 'pipe-bridge.sh').as_posix()}"
id() {{ if [[ $1 == -u ]]; then printf '%s\\n' "${{FAKE_UID:-0}}"; else command id "$@"; fi; }}
bin_dir=$(cd "$TEST_ROOT/bin" && pwd); export PATH="$bin_dir:$PATH"
stage5_pipe_serve "$TEST_ROOT" vf5test 0 1 "$((SECONDS+${{FAKE_BUDGET:-5}}))"
'''

    def sql_argv(self):
        return ['mysql', '--defaults-file=' + self.root.as_posix() + '/host-private/mysql.cnf',
                '--connect-timeout=5', '--binary-mode=1', '--local-infile=0',
                '--batch', '--raw', '--skip-column-names', 'demo']

    def test_valid_framed_sql_has_private_configuration_and_binary_mode(self):
        result = self.serve(request(self.sql_argv()) + done())
        self.assertEqual(0, result.returncode, result.stderr)
        fields = result.stdout.strip().split(b'|')
        self.assertEqual([b'VF5', b'1', b'00000001', b'result', b'0'], fields[:5])
        self.assertEqual(b'42', base64.b64decode(fields[5], validate=True))
        self.assertNotIn(b'private-marker', result.stdout + result.stderr)
        argv = (self.root / 'called.argv').read_bytes()
        self.assertIn(b'--binary-mode=1\0', argv)
        self.assertNotIn(b'/private/mysql', argv)

    def test_mysql_client_commands_nul_and_oversize_never_reach_client(self):
        for data in (b'\\! touch target\n', b' \t\\. /host/target\n', b'system touch target\n',
                     b'SOURCE /host/target;\n', b'SELECT 1; system touch target;\n',
                     b'SELECT 1;\x00', b'x' * 131073):
            with self.subTest(data=data[:40]):
                result = self.serve(request(self.sql_argv(), data=data) + done())
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.root / 'called.argv').exists())

    def test_malformed_partial_oversize_and_noncanonical_base64_fail_closed(self):
        valid = request()
        cases = (valid[:-1], b'VF5|2|00000001|done|0||\n', done('00000000'),
                 valid.replace(b'|exec|', b'|shell|'), valid.replace(b'|3|', b'|301|'),
                 valid.replace(b'|exec|', b'|exec|extra|'), b'x'*196609 + b'\n',
                 b'VF5|1|00000001|exec|3|!!!!|\n',
                 b'VF5|1|00000001|exec|3|bXlzcWwALS12ZXJzaW9uAA==|ZE==\n',
                 valid.replace(b'VF5', b'VF\0' + b'5'))
        for frame in cases:
            with self.subTest(frame=frame[:50]):
                result = self.serve(frame)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.root / 'called.argv').exists())

    def test_replayed_ids_are_not_executed_twice(self):
        result = self.serve(request() + request() + done())
        self.assertNotEqual(0, result.returncode)
        self.assertEqual(1, result.stdout.count(b'|result|'))

    def test_crash_command_timeout_and_closed_response_reader_do_not_deadlock(self):
        for mode in ('crash', 'hang'):
            with self.subTest(mode=mode):
                result = self.serve(request(seconds='1'), FAKE_MODE=mode)
                self.assertNotEqual(0, result.returncode)
                self.assertNotIn(b'private-marker', result.stdout + result.stderr)

    def test_stdout_and_stderr_have_independent_hard_caps(self):
        for mode in ('bigout', 'bigerr'):
            with self.subTest(mode=mode):
                result = self.serve(request() + done(), FAKE_MODE=mode)
                self.assertNotEqual(0, result.returncode)
                self.assertLess(len(result.stdout), 1024)

    def test_old_shared_request_and_response_hardlinks_are_never_opened(self):
        bridge = self.root / 'private/bridge'
        bridge.mkdir()
        for name in ('argv', 'stdin', 'auth', 'deadline', 'request.ready', 'stdout', 'stderr', 'response.tmp', 'response.ready'):
            target = self.root / ('sentinel-' + name)
            target.write_bytes(b'untouched')
            os.link(target, bridge / name)
        result = self.serve(request() + done())
        self.assertEqual(0, result.returncode, result.stderr)
        for target in self.root.glob('sentinel-*'):
            self.assertEqual(b'untouched', target.read_bytes())

    def test_non_root_host_is_refused_before_any_command(self):
        result = self.serve(request() + done(), FAKE_UID='1000')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'called.argv').exists())

    def test_actual_python_client_and_bash_server_exchange_over_kernel_pipes(self):
        to_host_read, to_host_write = os.pipe()
        to_client_read, to_client_write = os.pipe()
        host = subprocess.Popen([BASH, '-c', self.server_body()], stdin=to_host_read, stdout=to_client_write,
            stderr=subprocess.PIPE, env={**os.environ, 'TEST_ROOT': self.root.as_posix()})
        client_code = r'''import importlib.util,pathlib,sys
spec=importlib.util.spec_from_file_location('driver',sys.argv[1]); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
d=m.Driver.__new__(m.Driver); d.pipe=True; d.request_id=0; d.cnf=pathlib.PurePosixPath(pathlib.Path(sys.argv[2]).as_posix())/'host-private/mysql.cnf'; d.env={'DB_NAME':'demo'}
answer=d.sql('SELECT 1;'); m.pipe_exchange(b'VF5|1|00000002|done|0||\n',5,response=False)
(pathlib.Path(sys.argv[2])/'client-result').write_text(answer)
'''
        client = subprocess.Popen([os.sys.executable, '-B', '-c', client_code,
            str(HERE / 'acceptance.py'), str(self.root)], stdin=to_client_read, stdout=to_host_write,
            stderr=subprocess.PIPE)
        for fd in (to_host_read, to_host_write, to_client_read, to_client_write):
            os.close(fd)
        try:
            _, client_error = client.communicate(timeout=12)
            _, host_error = host.communicate(timeout=12)
            self.assertEqual(0, client.returncode, client_error)
            self.assertEqual(0, host.returncode, host_error)
            self.assertEqual('42', (self.root / 'client-result').read_text())
            self.assertNotIn(b'private-marker', client_error + host_error)
        finally:
            for process in (client, host):
                if process.poll() is None:
                    process.kill()
                    process.communicate()

    def test_partial_frame_with_open_writer_and_closed_response_reader_are_bounded(self):
        env = {**os.environ, 'TEST_ROOT': self.root.as_posix(), 'FAKE_BUDGET': '1'}
        process = subprocess.Popen([BASH, '-c', self.server_body()], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        process.stdin.write(b'VF5|1|00000001|exec|'); process.stdin.flush()
        try:
            self.assertNotEqual(0, process.wait(timeout=5))
        finally:
            process.stdin.close(); process.stdout.close(); process.stderr.close()
            if process.poll() is None: process.kill(); process.wait()
        process = subprocess.Popen([BASH, '-c', self.server_body()], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env={**env, 'FAKE_BUDGET': '5'})
        process.stdout.close()
        process.stdin.write(request() + done()); process.stdin.close()
        try:
            self.assertNotEqual(0, process.wait(timeout=8))
        finally:
            process.stderr.close()
            if process.poll() is None: process.kill(); process.wait()

    def test_unreferenced_fifo_paths_and_concurrent_swaps_cannot_block_or_touch_targets(self):
        shared = self.root / 'private/bridge'
        shared.mkdir()
        paths = ('argv', 'stdin', 'stdout', 'stderr', 'response.tmp', 'response.ready', 'request.ready')
        command = 'for name in argv stdin stdout stderr response.tmp response.ready request.ready; do mkfifo "$TEST_ROOT/private/bridge/$name" || exit 1; done'
        creation = subprocess.run([BASH, '-c', command], capture_output=True, timeout=5,
            env={**os.environ, 'TEST_ROOT': self.root.as_posix()})
        self.assertEqual(0, creation.returncode, creation.stderr)
        result = self.serve(request() + done())
        self.assertEqual(0, result.returncode, result.stderr)
        # In Linux this also exercises real leaf symlinks. Windows lacks privilege
        # 1314, explicitly reported instead of claiming native symlink coverage.
        for name in paths:
            subprocess.run([BASH, '-c', 'rm -f -- "$1"', '_', (shared / name).as_posix()], timeout=5)
        sentinel = self.root / 'outside'
        sentinel.write_bytes(b'untouched')
        native_links = True
        try:
            for name in paths: os.symlink(sentinel, shared / name)
        except OSError as error:
            if os.name != 'nt' or getattr(error, 'winerror', None) != 1314: raise
            native_links = False
            print('Native leaf symlink creation unavailable (Windows1314); real hardlink/FIFO coverage retained')
        stop = threading.Event()
        def swap():
            while not stop.is_set():
                temporary = shared / 'swap'
                try:
                    os.link(sentinel, temporary)
                    os.replace(temporary, shared / 'stdin')
                except FileExistsError:
                    temporary.unlink(missing_ok=True)
                time.sleep(0.001)
        worker = threading.Thread(target=swap)
        worker.start()
        try:
            result = self.serve(request() + done())
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(b'untouched', sentinel.read_bytes())
        finally:
            stop.set(); worker.join(timeout=2)


if __name__ == '__main__':
    unittest.main()

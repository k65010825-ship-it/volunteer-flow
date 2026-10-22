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
PROJECT = 'vf5' + 'a'*32
REDIS_ID = 'b' * 64
OTHER_REDIS_ID = 'c' * 64


def encoded(value):
    return base64.b64encode(value).decode('ascii')


def request(argv=None, data=b'SELECT 1;', identity='00000001', seconds='3', action='exec'):
    argv = ['mysql', '--version'] if argv is None else argv
    fields = ('\0'.join(argv) + '\0').encode() if argv else b''
    return ('VF5|1|' + identity + '|' + action + '|' + seconds + '|' +
            encoded(fields) + '|' + encoded(data) + '\n').encode()


def done(identity='00000002'):
    return ('VF5|1|' + identity + '|done|0||\n').encode()


class PipeBridgeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for name in ('private', 'evidence', 'host-private', 'bin'):
            (self.root / name).mkdir()
        (self.root / 'stage5.env').write_bytes(
            b'DB_NAME=demo\nREDIS_HOST=127.0.0.1\nREDIS_PORT=6379\nREDIS_DATABASE=2\n'
            b'REDIS_PASSWORD=redis-private-marker\nSTAGE5_MYSQL_CONTAINER=mysql-web\n'
            b'STAGE5_REDIS_CONTAINER=redis-web\n')
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
        (self.root / 'bin/docker').write_bytes(self.fake.encode())
        (self.root / 'bin/docker').chmod(0o755)

    def serve(self, frames, timeout=8, **env):
        bridge = HERE / 'pipe-bridge.sh'
        self.assertTrue(bridge.exists(), 'repo-owned bounded pipe bridge required')
        mode = env.get('FAKE_MODE', 'normal')
        (self.root / 'bin/mysql').write_bytes(self.fake.replace('${FAKE_MODE:-normal}', mode).encode())
        if env.get('PRESERVE_DOCKER') != '1':
            (self.root / 'bin/docker').write_bytes(self.fake.replace('${FAKE_MODE:-normal}', mode).encode())
        body = self.server_body()
        return subprocess.run([BASH, '-c', body], input=frames, capture_output=True, timeout=timeout,
            env={**os.environ, 'TEST_ROOT': self.root.as_posix(), **env})

    def server_body(self):
        return f'''source "{(HERE / 'pipe-bridge.sh').as_posix()}"
id() {{ if [[ $1 == -u ]]; then printf '%s\\n' "${{FAKE_UID:-0}}"; else command id "$@"; fi; }}
bin_dir=$(cd "$TEST_ROOT/bin" && pwd)
if [[ ${{ISOLATE_PATH:-0}} == 1 ]]; then
  export PATH="$bin_dir"
  if command -v redis-cli > "$TEST_ROOT/redis.path" 2>/dev/null; then exit 97; else printf 'ABSENT' > "$TEST_ROOT/redis.path"; fi
else export PATH="$bin_dir:$PATH"
fi
if [[ ${{CAPTURE_PATH:-0}} == 1 ]]; then printf '%s' "$PATH" > "$TEST_ROOT/isolated.path"; fi
stage5_pipe_serve "$TEST_ROOT" "${{TEST_PROJECT:-{PROJECT}}}" 0 1 "$((SECONDS+${{FAKE_BUDGET:-5}}))"
'''

    def sql_argv(self):
        return ['mysql', '--defaults-file=' + self.root.as_posix() + '/host-private/mysql.cnf',
                '--connect-timeout=5', '--binary-mode=1', '--local-infile=0',
                '--batch', '--raw', '--skip-column-names', 'demo']

    def redis_argv(self, *suffix):
        return ['redis-cli', '--container-id', REDIS_ID, '--raw', '-h', '127.0.0.1',
                '-p', '6379', '-n', '2', *suffix]

    def install_redis_docker(self):
        for tool in ('timeout', 'head', 'base64', 'wc', 'cmp', 'mktemp', 'mkdir', 'cat',
                     'rm', 'rmdir', 'sleep'):
            wrapper = self.root / 'bin' / tool
            wrapper.write_text('#!/bin/bash\nexec /usr/bin/' + tool + ' "$@"\n')
            wrapper.chmod(0o755)
        docker = self.root / 'bin/docker'
        docker.write_bytes(('''#!/bin/bash
printf '%s\\0' "$@" >> "ROOT/docker.argv"
printf '\\0' >> "ROOT/docker.argv"
if [[ $1 == inspect ]]; then
  [[ $2 == --format && $3 == '{{.Id}}' && $4 == redis-web && $# == 4 ]] || exit 90
  [[ ${REDISCLI_AUTH+x} != x ]] || exit 95
  printf 'ID\n'
elif [[ $1 == exec ]]; then
  [[ $2 == --env && $3 == REDISCLI_AUTH && $4 == ID && $5 == redis-cli ]] || exit 91
  [[ ${REDISCLI_AUTH+x} == x && $REDISCLI_AUTH == redis-private-marker ]] || exit 92
  printf 'auth-present' >> "ROOT/docker.auth"
  case " ${*:6} " in
    *' --version '*) printf 'redis-cli 7.2.5\\n' ;;
    *' PING '*) printf 'PONG\\n' ;;
    *' --scan '*) printf '' ;;
    *' DEL '*) printf '1\\n' ;;
    *) exit 93 ;;
  esac
else
  exit 94
fi
'''.replace('ROOT', self.root.as_posix()).replace('ID', REDIS_ID)).encode())
        docker.chmod(0o755)

    def test_redis_commands_use_only_exact_existing_container_client_without_host_cli(self):
        self.install_redis_docker()
        commands = [
            ['redis-cli', '--container-id', REDIS_ID, '--version'],
            self.redis_argv('PING'),
            self.redis_argv('--scan', '--pattern', 'volunteerflow:' + PROJECT + ':*'),
            self.redis_argv('DEL', 'volunteerflow:' + PROJECT + ':registration:1'),
        ]
        frames = b''.join(request(command, data=b'', identity=f'{index:08d}')
                          for index, command in enumerate(commands, 1)) + done('00000005')
        result = self.serve(frames, timeout=30, PRESERVE_DOCKER='1', FAKE_BUDGET='25',
                            ISOLATE_PATH='1', CAPTURE_PATH='1')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn(b'redis-private-marker', result.stdout + result.stderr)
        calls = (self.root / 'docker.argv').read_bytes().decode().split('\0\0')[:-1]
        self.assertEqual(8, len(calls))
        for logical, inspect_call, exec_call in zip(commands, calls[::2], calls[1::2]):
            self.assertEqual(['inspect', '--format', '{{.Id}}', 'redis-web'], inspect_call.split('\0'))
            actual = exec_call.split('\0')
            self.assertEqual(['exec', '--env', 'REDISCLI_AUTH', REDIS_ID, 'redis-cli', *logical[3:]], actual)
            self.assertFalse(set(actual) & {'run', 'rm', 'stop', 'start', 'restart', 'create'})
            self.assertNotIn('redis-private-marker', actual)
        self.assertEqual('auth-present' * 4, (self.root / 'docker.auth').read_text())
        self.assertNotIn(':', (self.root / 'isolated.path').read_text())
        self.assertEqual('ABSENT', (self.root / 'redis.path').read_text())

    def test_empty_redis_password_is_passed_by_environment_name_without_secret_output(self):
        self.install_redis_docker()
        env_file = self.root / 'stage5.env'
        env_file.write_text(env_file.read_text().replace('REDIS_PASSWORD=redis-private-marker', 'REDIS_PASSWORD='))
        docker = self.root / 'bin/docker'
        docker.write_text(docker.read_text().replace(
            '[[ ${REDISCLI_AUTH+x} == x && $REDISCLI_AUTH == redis-private-marker ]]',
            '[[ ${REDISCLI_AUTH+x} == x && -z $REDISCLI_AUTH ]]'))
        result = self.serve(request(['redis-cli', '--container-id', REDIS_ID, '--version'], data=b'') + done(),
                            PRESERVE_DOCKER='1')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn(b'redis-private-marker', result.stdout + result.stderr)
        self.assertNotIn(b'redis-private-marker', (self.root / 'docker.argv').read_bytes())

    def test_other_canonical_container_id_is_rejected_before_credential_bearing_exec(self):
        self.install_redis_docker()
        result = self.serve(request(['redis-cli', '--container-id', OTHER_REDIS_ID, '--version'], data=b'') + done(),
                            PRESERVE_DOCKER='1')
        self.assertNotEqual(0, result.returncode)
        self.assertTrue((self.root / 'docker.argv').exists(), (result.stdout, result.stderr))
        calls = (self.root / 'docker.argv').read_bytes().decode().split('\0\0')[:-1]
        self.assertEqual([['inspect', '--format', '{{.Id}}', 'redis-web']],
                         [call.split('\0') for call in calls])

    def test_redis_scope_tls_and_generic_docker_exec_fail_before_docker(self):
        self.install_redis_docker()
        prefix = 'volunteerflow:' + PROJECT + ':'
        invalid = [
            ['redis-cli', '--container-id', REDIS_ID, '--raw', '-h', 'other', '-p', '6379', '-n', '2', 'PING'],
            ['redis-cli', '--container-id', REDIS_ID, '--raw', '-h', '127.0.0.1', '-p', '6380', '-n', '2', 'PING'],
            ['redis-cli', '--container-id', REDIS_ID, '--raw', '-h', '127.0.0.1', '-p', '6379', '-n', '3', 'PING'],
            ['redis-cli', '--container-id', REDIS_ID[:12], '--version'],
            self.redis_argv('--tls', 'PING'), self.redis_argv('FLUSHDB'),
            self.redis_argv('--scan', '--pattern', 'volunteerflow:*'),
            self.redis_argv('DEL', 'volunteerflow:foreign:key'),
            self.redis_argv('DEL', *[prefix + str(i) for i in range(101)]),
            ['docker', 'exec', 'redis-web', 'redis-cli', 'PING'],
        ]
        for command in invalid:
            with self.subTest(command=command[:10]):
                (self.root / 'docker.argv').unlink(missing_ok=True)
                result = self.serve(request(command, data=b'') + done(), PRESERVE_DOCKER='1')
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.root / 'docker.argv').exists())

    def test_invalid_redis_container_name_fails_before_docker(self):
        self.install_redis_docker()
        env_file = self.root / 'stage5.env'
        env_file.write_text(env_file.read_text().replace('STAGE5_REDIS_CONTAINER=redis-web',
                                                          'STAGE5_REDIS_CONTAINER=redis/web'))
        result = self.serve(request(['redis-cli', '--container-id', REDIS_ID, '--version'], data=b'') + done(),
                            PRESERVE_DOCKER='1')
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'docker.argv').exists())

    def test_host_tool_gate_does_not_require_redis_cli_but_still_requires_docker(self):
        tools = ('bash', 'timeout', 'docker', 'mysql', 'mysqldump', 'sha256sum', 'realpath',
                 'tar', 'id', 'base64', 'head', 'cmp', 'chown')
        for tool in tools:
            path = self.root / 'bin' / tool
            if not path.exists():
                path.write_text('#!/usr/bin/env bash\nexit 0\n')
                path.chmod(0o755)
        bridge = HERE / 'pipe-bridge.sh'
        body = f'''source "{bridge.as_posix()}"
bin_dir=$(cd "$TEST_ROOT/bin" && pwd)
PATH="$bin_dir" stage5_require_host_tools
'''
        result = subprocess.run([BASH, '-c', body], capture_output=True, env={**os.environ,
            'TEST_ROOT': self.root.as_posix()}, timeout=5)
        self.assertEqual(0, result.returncode, result.stderr)
        (self.root / 'bin/docker').unlink()
        result = subprocess.run([BASH, '-c', body], capture_output=True, env={**os.environ,
            'TEST_ROOT': self.root.as_posix()}, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertIn(b'Missing deploy-essential tool: docker', result.stderr)

    def test_bash_version_evidence_requires_recognizable_nonempty_single_line_value(self):
        bridge = HERE / 'pipe-bridge.sh'
        body = f'''source "{bridge.as_posix()}"
stage5_validate_bash_version "$TEST_VERSION"
'''
        for value, expected in (('', 1), ('not-a-version', 1), ('5.2.15(1)-release\nother', 1),
                                ('\n5.2.15(1)-release', 1), ('5.2.15(1)-release\r', 1),
                                ('\t5.2.15(1)-release', 1), (' ' * 129 + '5.2.15(1)-release', 1),
                                ('5.2.15(1)-release', 0)):
            with self.subTest(value=value):
                env = {**os.environ, 'TEST_VERSION': value}
                result = subprocess.run([BASH, '-c', body], env=env, capture_output=True, timeout=5)
                self.assertEqual(expected, result.returncode, result.stderr)

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

    def docker_prefix(self):
        return ['docker', 'compose', '-p', PROJECT, '-f', self.root.as_posix() + '/repo/deploy/stage5/docker-compose.yml',
                '--env-file', self.root.as_posix() + '/stage5.env', 'exec', '-T', 'kafka']

    def test_generic_kafka_argv_counterexamples_never_reach_docker(self):
        variants = [
            ['kafka-topics.sh', '--bootstrap-server', 'foreign.example:9092', '--delete', '--topic=unrelated'],
            ['kafka-topics.sh', '--bootstrap-server', 'kafka:9092', '--delete', '--topic', PROJECT+'.safe|.*'],
            ['kafka-consumer-groups.sh', '--bootstrap-server', 'kafka:9092', '--reset-offsets', '--group='+PROJECT+'-notification', '--execute'],
            ['kafka-console-producer.sh', '--bootstrap-server=foreign.example:9092', '--topic='+PROJECT+'.registration-events.v1'],
        ]
        for suffix in variants:
            with self.subTest(suffix=suffix):
                (self.root / 'called.argv').unlink(missing_ok=True)
                argv = self.docker_prefix() + ['/opt/kafka/bin/'+suffix[0]] + suffix[1:]
                result = self.serve(request(argv, data=b'') + done())
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.root / 'called.argv').exists(), 'generic Kafka argv must never be executed')

    def test_only_typed_kafka_actions_construct_fixed_argv(self):
        topic = PROJECT+'.registration-events.v1'
        group = PROJECT+'-notification'
        cases = [
            ('kafka-list-topics', [], b'', ['kafka-topics.sh','--bootstrap-server','kafka:9092','--list']),
            ('kafka-produce-duplicate', [topic], b'1:2\t{"eventId":"00000000-0000-0000-0000-000000000001"}\n',
             ['kafka-console-producer.sh','--bootstrap-server','kafka:9092','--topic',topic,'--property','parse.key=true',
              '--property','key.separator=\t','--producer-property','acks=all','--producer-property','delivery.timeout.ms=30000']),
            ('kafka-describe-group', [group], b'', ['kafka-consumer-groups.sh','--bootstrap-server','kafka:9092','--group',group,'--describe']),
        ]
        for action, fields, data, suffix in cases:
            with self.subTest(action=action):
                result = self.serve(request(fields, data=data, action=action) + done())
                self.assertEqual(0, result.returncode, result.stderr)
                actual = (self.root / 'called.argv').read_bytes().decode().split('\0')[:-1]
                self.assertEqual(self.docker_prefix()[1:] + ['/opt/kafka/bin/'+suffix[0]] + suffix[1:], actual)

    def test_typed_kafka_fields_options_and_regex_scopes_fail_before_execution(self):
        cases = [
            ('kafka-list-topics', ['--delete'], b'', PROJECT),
            ('kafka-list-topics', [], b'nonempty', PROJECT),
            ('kafka-produce-duplicate', ['--topic='+PROJECT+'.registration-events.v1'], b'1:2\t{}\n', PROJECT),
            ('kafka-produce-duplicate', [PROJECT+'.registration-events.v1|.*'], b'1:2\t{}\n', PROJECT),
            ('kafka-produce-duplicate', ['foreign.registration-events.v1'], b'1:2\t{}\n', PROJECT),
            ('kafka-produce-duplicate', [PROJECT+'.registration-events.v1','--bootstrap-server','foreign:9092'], b'1:2\t{}\n', PROJECT),
            ('kafka-produce-duplicate', [PROJECT+'.registration-events.v1'], b'1:2\t{}\n3:4\t{}\n', PROJECT),
            ('kafka-describe-group', [PROJECT+'-notification','--delete'], b'', PROJECT),
            ('kafka-describe-group', [PROJECT+'-notification.*'], b'', PROJECT),
            ('kafka-describe-group', ['--group='+PROJECT+'-notification'], b'', PROJECT),
            ('kafka-describe-group', ['--delete'], b'', PROJECT),
            ('kafka-create-topic', [PROJECT+'.registration-events.v1'], b'', PROJECT),
            ('kafka-describe-group', [PROJECT+'-notification'], b'', PROJECT+'.*'),
        ]
        for action, fields, data, project in cases:
            with self.subTest(action=action, fields=fields):
                (self.root / 'called.argv').unlink(missing_ok=True)
                result = self.serve(request(fields, data=data, action=action) + done(), TEST_PROJECT=project)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.root / 'called.argv').exists())

    def test_topic_creation_is_host_only_and_has_six_exact_fixed_commands(self):
        docker = self.root / 'bin/docker'
        docker.write_bytes(('''#!/usr/bin/env bash
printf '%s\\0' "$@" >> "ROOT/created.argv"
printf '\\0' >> "ROOT/created.argv"
printf '%s' "$STAGE5_RUN_COMPACT" > "ROOT/created.compact"
[[ -z ${PRIVATE_TEST_SECRET:-} ]]
'''.replace('ROOT', self.root.as_posix())).encode())
        body = self.server_body().split('stage5_pipe_serve', 1)[0] + '\nstage5_prepare_topics "$TEST_ROOT" "$TEST_PROJECT"\n'
        env = {**os.environ, 'TEST_ROOT': self.root.as_posix(), 'TEST_PROJECT': PROJECT,
               'PRIVATE_TEST_SECRET': 'private-marker'}
        result = subprocess.run([BASH, '-c', body], capture_output=True, env=env, timeout=15)
        self.assertEqual(0, result.returncode, result.stderr)
        actual = (self.root / 'created.argv').read_bytes().decode().split('\0\0')[:-1]
        names = [PROJECT+'.'+suffix+ending for suffix in
                 ('activity-events.v1', 'registration-events.v1', 'checkin-events.v1') for ending in ('', '.DLT')]
        self.assertEqual(6, len(actual))
        for command, name in zip(actual, names):
            self.assertEqual(self.docker_prefix()[1:] + ['/opt/kafka/bin/kafka-topics.sh', '--bootstrap-server',
                'kafka:9092', '--create', '--if-not-exists', '--topic', name, '--partitions', '1', '--replication-factor', '1'], command.split('\0'))
        self.assertEqual(PROJECT[3:], (self.root / 'created.compact').read_text())
        (self.root / 'created.argv').unlink()
        result = subprocess.run([BASH, '-c', body], capture_output=True, env={**env, 'TEST_PROJECT': PROJECT+'.*'}, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertFalse((self.root / 'created.argv').exists())

    def test_bom_delimiter_prefix_and_comment_client_forms_are_rejected(self):
        cases = [b'\xef\xbb\xbfDELIMITER $$\n', b' \t\xef\xbb\xbfDeLiMiTeRsuffix $$\r\n',
                 b'  dElImItErSuffix $$\r\n', b'/*prefix*/ DELIMITERsuffix $$\n',
                 b'\r\n /*comment*/ charset utf8mb4\n', b'/*x*/system touch target\n',
                 b'--prefix\r\n\tSOURCE target\r\n', b'/*x*/ tee target\n', b'/*x*/pager cat\n']
        for data in cases:
            with self.subTest(data=data):
                (self.root / 'called.argv').unlink(missing_ok=True)
                result = self.serve(request(self.sql_argv(), data=data) + done())
                self.assertNotEqual(0, result.returncode)
                self.assertFalse((self.root / 'called.argv').exists())

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
            stderr=subprocess.PIPE, env={**os.environ, 'TEST_ROOT': self.root.as_posix(), 'FAKE_BUDGET': '15'})
        client_code = r'''import importlib.util,pathlib,sys
spec=importlib.util.spec_from_file_location('driver',sys.argv[1]); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
d=m.Driver.__new__(m.Driver); d.pipe=True; d.request_id=0; d.cnf=pathlib.PurePosixPath(pathlib.Path(sys.argv[2]).as_posix())/'host-private/mysql.cnf'; d.env={'DB_NAME':'demo'}
answer=d.sql('SELECT 1;')+'/'+d.rpc('kafka-list-topics',[],timeout=3); m.pipe_exchange(b'VF5|1|00000003|done|0||\n',5,response=False)
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
            self.assertEqual('42/42', (self.root / 'client-result').read_text())
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

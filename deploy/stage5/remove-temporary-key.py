#!/usr/bin/env python3
"""Exact temporary-key removal with a bounded SSH child and safe local status."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys


def remove_key(host, user, port, identity, status_file, ssh='ssh', run=subprocess.run):
    code = 1
    try:
        if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9.-]*', host) or not re.fullmatch(r'[A-Za-z0-9_][A-Za-z0-9._-]*', user):
            raise ValueError('Invalid SSH target')
        if not 0 < int(port) < 65536:
            raise ValueError('Invalid SSH port')
        identity = Path(identity).resolve(strict=True)
        public_path = Path(str(identity) + '.pub')
        key = public_path.read_text(encoding='utf-8').strip()
        if not re.fullmatch(r'ssh-ed25519 [A-Za-z0-9+/=]+ volunteerflow-stage4d-[0-9a-f-]+', key):
            raise ValueError('Dedicated temporary key required')
        script = """set -eu
umask 077
file="$HOME/.ssh/authorized_keys"
test -f "$file" && test ! -L "$file"
key='""" + key + """'
tmp=$(mktemp "${file}.stage5.XXXXXX")
trap 'rm -f -- "$tmp"' EXIT
rc=0
grep -Fvx -- "$key" "$file" > "$tmp" || rc=$?
test "$rc" -le 1
chmod 600 "$tmp"
mv -- "$tmp" "$file"
trap - EXIT
verify=0
grep -Fxq -- "$key" "$file" || verify=$?
# Only grep's exact "not found" result proves removal; read errors fail closed.
test "$verify" -eq 1
"""
        command = [ssh, '-p', str(port), '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
                   '-o', 'ConnectTimeout=10', '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
                   '-i', str(identity), user + '@' + host, 'sh', '-s']
        result = run(command, input=script.encode('utf-8'), stdout=subprocess.PIPE,
                     stderr=subprocess.PIPE, timeout=60, check=False)
        code = result.returncode if 0 <= result.returncode <= 255 else 1
        if code == 0:
            # Never delete local recovery material before exact remote verification.
            identity.unlink()
            public_path.unlink()
    except subprocess.TimeoutExpired:
        code = 124
    except Exception:
        code = 1
    try:
        Path(status_file).write_text(json.dumps({'status': 'success' if code == 0 else 'failed',
                                               'exitCode': code}) + '\n', encoding='utf-8')
    except Exception:
        return 1
    return code


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--host', required=True)
    parser.add_argument('--user', required=True)
    parser.add_argument('--port', type=int, required=True)
    parser.add_argument('--identity', required=True)
    parser.add_argument('--status-file', required=True)
    parser.add_argument('--ssh', default='ssh')
    args = parser.parse_args()
    return remove_key(args.host, args.user, args.port, args.identity, args.status_file, args.ssh)


if __name__ == '__main__':
    sys.exit(main())

"""Local-only: hash the exact committed member sent to Linux, without extraction."""
import hashlib
import sys
import tarfile


def main():
    try:
        with tarfile.open(sys.argv[1], 'r:') as archive:
            members = [m for m in archive.getmembers() if m.name == 'deploy/stage5/acceptance.py']
            if len(members) != 1 or not members[0].isfile() or not 0 < members[0].size <= 1024 * 1024:
                raise ValueError('Invalid committed driver')
            print(hashlib.sha256(archive.extractfile(members[0]).read()).hexdigest())
        return 0
    except Exception:
        print('Committed archive driver hash failed', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())

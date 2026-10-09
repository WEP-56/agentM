"""Package pinned Linux runtime sources and the scripts/notices needed to inspect them."""
import hashlib
import json
from pathlib import Path
import tarfile
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
CACHE = ROOT / '.cache/release-sources'
OUT = ROOT / 'output/release'
LOCK = ROOT / 'android/third-party/runtime/sources.lock.json'


def main():
    CACHE.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(parents=True, exist_ok=True)
    entries = json.loads(LOCK.read_text(encoding='utf-8'))['sources']
    archives = []
    for entry in entries:
        destination = CACHE / entry['file']
        if not destination.exists():
            for attempt in range(3):
                try:
                    request = urllib.request.Request(entry['url'], headers={'User-Agent': 'agentM-release'})
                    with urllib.request.urlopen(request, timeout=120) as response:
                        content = response.read()
                    if hashlib.sha256(content).hexdigest() != entry['sha256']:
                        raise ValueError(f"Source checksum mismatch: {entry['file']}")
                    destination.write_bytes(content)
                    break
                except OSError:
                    if attempt == 2:
                        raise
                    time.sleep(2)
        if hashlib.sha256(destination.read_bytes()).hexdigest() != entry['sha256']:
            raise ValueError(f"Source checksum mismatch: {entry['file']}")
        archives.append(destination)
        print(f"Verified source: {entry['file']}")
    target = OUT / 'linux-runtime-sources.tar.gz'
    with tarfile.open(target, 'w:gz') as archive:
        for source in archives:
            archive.add(source, arcname=f'linux-runtime-sources/upstream/{source.name}')
        for relative in (
            'android/third-party',
            'android/runtime-assets.lock.json',
            'android/app/src/main/assets/licenses',
            'tools/prepare-linux-runtime.mjs',
            'tools/release/bundle-runtime-sources.py',
        ):
            archive.add(ROOT / relative, arcname=f'linux-runtime-sources/{relative}')
    print(f'Packaged {target.name} ({target.stat().st_size} bytes)')


if __name__ == '__main__':
    main()

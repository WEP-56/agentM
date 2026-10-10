"""Reproduce the pinned DSHA runtime adaptations without changing the reference snapshot."""
import hashlib
import importlib.util
import json
from pathlib import Path, PurePosixPath
import sys
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
COMMIT = '70e37a7dbcae83b32fc92a8a37b33af88befc0e0'
SOURCE = ROOT / '.cache/dsha-reference'
ASSETS = 'app/src/main/assets/'
paths = ['tools/build-dsh-runtime.py', 'tools/source_text.py', 'tools/dsh-runtime/package.json', 'tools/dsh-runtime/package-lock.json']
paths += [ASSETS + directory + '/' + name for directory in ('runtime-fs', 'session-compat', 'client-combo-cache') for name in ('index.js', 'package.json')]
paths += [ASSETS + name for name in ('client-combo-patch.json', 'deepseek-messages-compat-patch.json', 'lexical-claim-patch.json', 'conversation-materialized-patch.json', 'rc1-settings-migration-patch.json', 'workspace-directory-policy-patch.json')]
provenance = {}
def lf_bytes(data):
    # The pinned DSHA Messages recipe inserts CRLF in two replacements. Canonicalize
    # generated text before writing AND hashing so Git checkouts preserve the bytes.
    # Keep upstream recipe/archive bytes unchanged for provenance and beforeSha256.
    return data.replace(b'\r\n', b'\n')

def acquire(name):
    local = SOURCE / name
    if not local.exists():
        url = f'https://raw.githubusercontent.com/DSH-APP/DSHA/{COMMIT}/{name}'
        with urllib.request.urlopen(url, timeout=90) as response:
            data = response.read()
        local.parent.mkdir(parents=True, exist_ok=True)
        local.write_bytes(data)
    provenance[name] = hashlib.sha256(local.read_bytes()).hexdigest()
    return local
for name in paths:
    acquire(name)
messages = json.loads((SOURCE / (ASSETS + 'deepseek-messages-compat-patch.json')).read_text(encoding='utf-8'))
for patch in messages['patches']:
    if 'prependAsset' in patch:
        acquire(ASSETS + patch['prependAsset'])
sys.path.insert(0, str(SOURCE / 'tools'))
spec = importlib.util.spec_from_file_location('dsha_builder', SOURCE / 'tools/build-dsh-runtime.py')
builder = importlib.util.module_from_spec(spec)
spec.loader.exec_module(builder)
lock = json.loads((SOURCE / 'tools/dsh-runtime/package-lock.json').read_text(encoding='utf-8'))
targets = set(builder.PATCHES) | {builder.COMBO_MODULE, builder.DEEPSEEK_MESSAGES_MODULE, builder.LEXICAL_CLAIM_MODULE,
    builder.STORAGE_JSON_MODULE, builder.WORKSPACE_DIRECTORY_MODULE, builder.SESSION_PERSISTENCE_JSONL_MODULE,
    '@deepseek-ai/dsh-session-format-v2-to-v3/lib/index.js'}
destination = ROOT / 'android/app/src/main/assets/dsh-overlays'
destination.mkdir(parents=True, exist_ok=True)
overlays = []
for target in sorted(targets):
    package = '/'.join(target.split('/')[:2])
    definition = lock['packages']['node_modules/' + package]
    archive = ROOT / '.cache/web-agents' / (hashlib.sha256(definition['resolved'].encode()).hexdigest() + '.tgz')
    with tarfile.open(archive, 'r:gz') as tar:
        data = tar.extractfile('package/' + target[len(package) + 1:]).read()
    result = builder.patched_content(PurePosixPath(target), data)
    if target == builder.WORKSPACE_DIRECTORY_MODULE:
        # Preserve DSHA's explicit default-directory override, bound to agentM's shared workspace.
        source = result.decode()
        anchor = 'process.env.DSHA_WORKSPACE_DOCUMENTS === "/root/Documents"\n\t\t&& (internals.home ?? homedir()) === "/root") directory = "/root/Documents";'
        if source.count(anchor) != 1:
            raise ValueError('DSHA workspace override anchor changed')
        result = source.replace(anchor, 'process.env.AGENTM_DSH_WORKSPACE === "/workspace"\n\t\t&& (internals.home ?? homedir()) === "/root") directory = "/workspace";').encode()
    result = lf_bytes(result)
    name = str(len(overlays)) + '.js'
    (destination / name).write_bytes(result)
    overlays.append({'path': 'node_modules/' + target, 'asset': 'dsh-overlays/' + name,
        'beforeSha256': hashlib.sha256(data).hexdigest(), 'sha256': hashlib.sha256(result).hexdigest()})
for directory, package in [(builder.HOOKS, 'dsha-runtime-fs'), (builder.SESSION_HOOKS, 'dsha-session-compat'), (builder.COMBO_HOOKS, 'dsha-client-combo-cache')]:
    for name in ('index.js', 'package.json'):
        data = lf_bytes((directory / name).read_bytes())
        asset = package + '-' + name
        (destination / asset).write_bytes(data)
        overlays.append({'path': 'node_modules/' + package + '/' + name, 'asset': 'dsh-overlays/' + asset,
            'sha256': hashlib.sha256(data).hexdigest()})
(destination / 'manifest.json').write_bytes((json.dumps({'version': '0.2.0-rc.2', 'sourceCommit': COMMIT, 'files': overlays}, indent=2) + '\n').encode('utf-8'))
(ROOT / 'docs/research/agent-packages/dsh-adaptations.json').write_bytes((json.dumps({'sourceCommit': COMMIT, 'sourceBase': f'https://github.com/DSH-APP/DSHA/tree/{COMMIT}/', 'inputs': provenance, 'overlays': overlays}, indent=2) + '\n').encode('utf-8'))
print(f'Generated {len(overlays)} verified DSHA runtime overlays')

// Published packages are pinned; no lifecycle scripts run on the host or on Android.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
const root = path.resolve(import.meta.dirname, '..');
const cache = path.join(root, '.cache/web-agents');
fs.mkdirSync(cache, { recursive: true });
const hash = (data, kind = 'sha256') => crypto.createHash(kind).update(data).digest(kind === 'sha512' ? 'base64' : 'hex');
async function fetchOk(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(240000) });
  if (!response.ok) throw Error(`HTTP ${response.status}: ${url}`);
  return response;
}
async function asset(url, integrity) {
  if (!integrity.startsWith('sha512-')) throw Error(`Unsupported integrity: ${url}`);
  const file = path.join(cache, hash(Buffer.from(url)) + '.tgz');
  let data;
  if (fs.existsSync(file)) data = fs.readFileSync(file);
  else {
    for (let attempt = 0; ; attempt++) {
      try { data = Buffer.from(await (await fetchOk(url)).arrayBuffer()); break; }
      catch (error) { if (attempt === 2) throw error; }
    }
  }
  if ('sha512-' + hash(data, 'sha512') !== integrity) throw Error(`Digest mismatch: ${url}`);
  fs.writeFileSync(file, data);
  return { url, integrity, bytes: data.length, sha256: hash(data), file };
}
async function npm(name, version) {
  const url = `https://registry.npmjs.org/${name}/${version}`;
  const metadata = await (await fetchOk(url)).json();
  if (metadata.name !== name || metadata.version !== version) throw Error(`Package mismatch: ${url}`);
  const data = await asset(metadata.dist.tarball, metadata.dist.integrity);
  return { metadata, asset: data };
}
const catalogFile = path.join(root, 'android/app/src/main/assets/agent-catalog.json');
const catalog = JSON.parse(fs.readFileSync(catalogFile, 'utf8'));
const metadata = {};
catalog.opencodeVersion = '1.18.35';
catalog.opencode = {};
for (const [abi, suffix] of [['x86_64', 'x64-baseline'], ['arm64-v8a', 'arm64']]) {
  const result = await npm(`opencode-linux-${suffix}`, catalog.opencodeVersion);
  const { file, ...definition } = result.asset;
  const files = execFileSync('tar', ['-tf', file], { encoding: 'utf8' }).trim().split(/\r?\n/);
  if (!files.includes('package/bin/opencode')) throw Error('Unexpected OpenCode layout');
  catalog.opencode[abi] = { ...definition, archiveRoot: 'package', executable: 'package/bin/opencode', package: result.metadata.name, version: result.metadata.version };
  metadata[abi] = result.metadata;
  console.log(`OpenCode ${abi}: ${definition.bytes} bytes verified`);
}
fs.writeFileSync(catalogFile, JSON.stringify(catalog, null, 2) + '\n');
fs.writeFileSync(path.join(root, 'docs/research/agent-packages/opencode.json'), JSON.stringify({ checkedAt: new Date().toISOString(), packages: metadata }, null, 2) + '\n');

// Local reference checkouts are intentionally not part of the public repository.
const lockFile = path.join(root, '.cache/dsha-reference/tools/dsh-runtime/package-lock.json');
const lockUrl = 'https://raw.githubusercontent.com/DSH-APP/DSHA/70e37a7dbcae83b32fc92a8a37b33af88befc0e0/tools/dsh-runtime/package-lock.json';
const lockBytes = fs.existsSync(lockFile) ? fs.readFileSync(lockFile) : Buffer.from(await (await fetchOk(lockUrl)).arrayBuffer());
if (hash(lockBytes) !== 'a3cf19ad8320ca415ae4eeff288d649645c3637dd9d08b1dd8588d3013c2ea2d') throw Error('Pinned DSH dependency lock digest mismatch');
fs.mkdirSync(path.dirname(lockFile), { recursive: true });
fs.writeFileSync(lockFile, lockBytes);
const lock = JSON.parse(lockBytes.toString('utf8'));
const permitted = (values, value) => !values || (!values.includes('!' + value) && (values.every(v => v.startsWith('!')) || values.includes(value)));
const entries = Object.entries(lock.packages).filter(([name, info]) => name && permitted(info.os, 'linux') && permitted(info.libc, 'glibc') &&
  (permitted(info.cpu, 'x64') || permitted(info.cpu, 'arm64')));
const result = new Map();
let completed = 0;
const queue = [...entries];
await Promise.all(Array.from({ length: 6 }, async () => {
  while (queue.length) {
    const [installPath, info] = queue.shift();
    if (!/^node_modules\/(?:[a-zA-Z0-9@._-]+\/)*[a-zA-Z0-9@._-]+$/.test(installPath) || installPath.split('/').includes('..')) throw Error('Invalid npm lock path');
    const downloaded = await asset(info.resolved, info.integrity);
    const names = execFileSync('tar', ['-tf', downloaded.file], { encoding: 'utf8', maxBuffer: 20 * 1024 * 1024 }).trim().split(/\r?\n/);
    const archiveRoots = [...new Set(names.map(n => n.replace(/^\.\//, '').split('/')[0]))];
    if (archiveRoots.length !== 1 || !/^[A-Za-z0-9@._ -]+$/.test(archiveRoots[0]) || ['.', '..'].includes(archiveRoots[0])) throw Error(`Invalid package archive: ${installPath}`);
    const { file, ...definition } = downloaded;
    result.set(installPath, { ...definition, path: installPath, version: info.version, archiveRoot: archiveRoots[0], bin: info.bin ?? {} });
    if (++completed % 75 === 0) console.log(`DSHA locked packages: ${completed}/${entries.length}`);
  }
}));
const dsh = { schemaVersion: 1, version: '0.2.0-rc.2', sourceCommit: '70e37a7dbcae83b32fc92a8a37b33af88befc0e0', lockSha256: hash(fs.readFileSync(lockFile)), architectures: {} };
for (const [abi, cpu] of [['x86_64', 'x64'], ['arm64-v8a', 'arm64']]) {
  const packages = entries.filter(([, info]) => permitted(info.cpu, cpu)).map(([name]) => result.get(name)).sort((a, b) => a.path.length - b.path.length || a.path.localeCompare(b.path));
  dsh.architectures[abi] = { packages, bytes: packages.reduce((n, p) => n + p.bytes, 0) };
}
fs.writeFileSync(path.join(root, 'android/app/src/main/assets/dsh-catalog.json'), JSON.stringify(dsh, null, 2) + '\n');
console.log(`DSH ${dsh.version}: ${entries.length} locked packages verified; ${dsh.architectures.x86_64.bytes} download bytes on x86_64`);

// Pin the published CLI bundle, including its WASM/native assets. No npm install scripts run.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const version = '1.1.0';
const url = `https://registry.npmjs.org/@earendil-works/pi-coding-agent/${version}`;
async function fetchOk(url) {
  const result = await fetch(url, { signal: AbortSignal.timeout(180000) });
  if (!result.ok) throw Error(`${url}: HTTP ${result.status}`);
  return result;
}
const metadata = await (await fetchOk(url)).json();
if (metadata.name !== '@earendil-works/pi-coding-agent' || metadata.version !== version ||
    !metadata.dist.integrity.startsWith('sha512-') || metadata.bin.pi !== 'dist/bundle/cli.js') {
  throw Error('Unexpected Pi package metadata');
}
const directory = path.join(root, '.cache/agent-packages');
fs.mkdirSync(directory, { recursive: true });
const file = path.join(directory, `pi-coding-agent-${version}.tgz`);
const bytes = fs.existsSync(file) ? fs.readFileSync(file) : Buffer.from(await (await fetchOk(metadata.dist.tarball)).arrayBuffer());
if (`sha512-${crypto.createHash('sha512').update(bytes).digest('base64')}` !== metadata.dist.integrity) throw Error('Pi digest mismatch');
fs.writeFileSync(file, bytes);
const asset = { url: metadata.dist.tarball, bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex'),
  archiveRoot: 'package', executable: 'package/dist/bundle/cli.js', package: metadata.name, integrity: metadata.dist.integrity, version };
asset.runtimeFiles = execFileSync('tar', ['-tf', file], { encoding: 'utf8' }).trim().split(/\r?\n/)
  .filter(name => name.startsWith('package/dist/bundle/') && name.endsWith('.js')).sort();
if (asset.runtimeFiles.length < 50 || asset.runtimeFiles.some(name => !/^package\/dist\/bundle\/(?:chunks\/)?[A-Za-z0-9_-]+\.js$/.test(name))) throw Error('Unexpected Pi bundle layout');
// These two runtime packages remain external to the published bundle: code-mode and image WASM.
const dependencies = [];
for (const [name, pinned] of [['quickjs-wasi', '3.6.2'], ['@silvia-odwyer/photon-node', '0.3.4']]) {
  if (metadata.dependencies[name] !== pinned) throw Error(`Unexpected Pi dependency: ${name}`);
  const dependencyUrl = `https://registry.npmjs.org/${name}/${pinned}`;
  const meta = await (await fetchOk(dependencyUrl)).json();
  if (meta.version !== pinned || meta.name !== name || !meta.dist.integrity.startsWith('sha512-') ||
      Object.keys(meta.dependencies ?? {}).length || meta.scripts?.install || meta.scripts?.postinstall) throw Error(`Unreviewed dependency: ${name}`);
  const dependencyFile = path.join(directory, path.basename(new URL(meta.dist.tarball).pathname));
  const data = fs.existsSync(dependencyFile) ? fs.readFileSync(dependencyFile) : Buffer.from(await (await fetchOk(meta.dist.tarball)).arrayBuffer());
  if (`sha512-${crypto.createHash('sha512').update(data).digest('base64')}` !== meta.dist.integrity) throw Error(`Digest mismatch: ${name}`);
  fs.writeFileSync(dependencyFile, data);
  const runtimeFiles = execFileSync('tar', ['-tf', dependencyFile], { encoding: 'utf8' }).trim().split(/\r?\n/)
    .filter(entry => /\.(js|wasm|so)$/.test(entry));
  if (runtimeFiles.some(entry => !/^package\/[A-Za-z0-9_./-]+$/.test(entry) || entry.split('/').includes('..'))) throw Error(`Unexpected dependency layout: ${name}`);
  dependencies.push({ url: meta.dist.tarball, bytes: data.length, sha256: crypto.createHash('sha256').update(data).digest('hex'),
    package: name, version: pinned, integrity: meta.dist.integrity, runtimeFiles: runtimeFiles.map(entry => `package/node_modules/${name}/${entry.slice(8)}`) });
  fs.writeFileSync(path.join(root, `docs/research/agent-packages/${name.split('/').at(-1)}.json`), JSON.stringify({ url: dependencyUrl, metadata: meta }, null, 2) + '\n');
}
asset.dependencies = dependencies;
const catalogPath = path.join(root, 'android/app/src/main/assets/agent-catalog.json');
const catalog = JSON.parse(fs.readFileSync(catalogPath, 'utf8'));
catalog.piVersion = version;
catalog.pi = { 'x86_64': asset, 'arm64-v8a': asset };
fs.writeFileSync(catalogPath, JSON.stringify(catalog, null, 2) + '\n');
fs.writeFileSync(path.join(root, 'docs/research/agent-packages/pi.json'), JSON.stringify({ checkedAt: new Date().toISOString(), url, metadata }, null, 2) + '\n');
console.log(`Verified Pi ${version}: ${bytes.length} bytes, SHA-256 ${asset.sha256}`);

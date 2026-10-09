// Explicit pinned inputs: running this script never follows a latest tag.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';

const root = path.resolve(import.meta.dirname, '..');
const cache = path.join(root, '.cache/agent-packages');
fs.mkdirSync(cache, { recursive: true });
const nodeVersion = '24.21.0';
const claudeVersion = '2.1.293';
const codexVersion = '0.161.0';
async function response(url) {
  const res = await fetch(url, { signal: AbortSignal.timeout(180000) });
  if (!res.ok) throw Error(`${url}: HTTP ${res.status}`);
  return res;
}
const sumsUrl = `https://nodejs.org/dist/v${nodeVersion}/SHASUMS256.txt`;
const sums = await (await response(sumsUrl)).text();
const metadata = {};
const codexMetadata = {};
const catalog = { schemaVersion: 1, nodeVersion, claudeVersion, codexVersion, node: {}, claude: {}, codex: {} };
async function asset(url, expected, algorithm) {
  const file = path.join(cache, path.basename(new URL(url).pathname));
  if (!fs.existsSync(file)) {
    await pipeline(Readable.fromWeb((await response(url)).body), fs.createWriteStream(file + '.part'));
    fs.renameSync(file + '.part', file);
  }
  const bytes = fs.readFileSync(file);
  const actual = crypto.createHash(algorithm).update(bytes).digest(algorithm === 'sha512' ? 'base64' : 'hex');
  if (actual !== expected) throw Error(`Digest mismatch: ${file}`);
  return { url, bytes: bytes.length, sha256: crypto.createHash('sha256').update(bytes).digest('hex') };
}
for (const [abi, arch] of [['x86_64', 'x64'], ['arm64-v8a', 'arm64']]) {
  const name = `node-v${nodeVersion}-linux-${arch}`;
  const sha = sums.split('\n').find(line => line.endsWith(`  ${name}.tar.gz`))?.split(' ')[0];
  if (!sha) throw Error(`Missing Node checksum: ${name}`);
  catalog.node[abi] = { ...await asset(`https://nodejs.org/dist/v${nodeVersion}/${name}.tar.gz`, sha, 'sha256'), archiveRoot: name, version: nodeVersion };
  const pkg = `@anthropic-ai/claude-code-linux-${arch}`;
  const metaUrl = `https://registry.npmjs.org/${pkg}/${claudeVersion}`;
  const meta = await (await response(metaUrl)).json();
  metadata[abi] = { url: metaUrl, metadata: meta };
  if (meta.version !== claudeVersion || !meta.dist.integrity.startsWith('sha512-')) throw Error('Unexpected package metadata');
  catalog.claude[abi] = { ...await asset(meta.dist.tarball, meta.dist.integrity.slice(7), 'sha512'), archiveRoot: 'package', package: pkg, integrity: meta.dist.integrity, version: claudeVersion };
  const codexUrl = `https://registry.npmjs.org/@openai/codex/${codexVersion}-linux-${arch}`;
  const codex = await (await response(codexUrl)).json();
  if (codex.version !== `${codexVersion}-linux-${arch}` || !codex.dist.integrity.startsWith('sha512-')) throw Error('Unexpected Codex package metadata');
  const target = abi === 'x86_64' ? 'x86_64-unknown-linux-musl' : 'aarch64-unknown-linux-musl';
  catalog.codex[abi] = { ...await asset(codex.dist.tarball, codex.dist.integrity.slice(7), 'sha512'), archiveRoot: 'package',
    executable: `package/vendor/${target}/bin/codex`, package: '@openai/codex', packageVersion: codex.version, integrity: codex.dist.integrity, version: codexVersion };
  codexMetadata[abi] = { url: codexUrl, metadata: codex };
  console.log(`Verified ${abi}: Node ${nodeVersion}, Claude Code ${claudeVersion}, Codex ${codexVersion}`);
}
fs.writeFileSync(path.join(root, 'android/app/src/main/assets/agent-catalog.json'), JSON.stringify(catalog, null, 2) + '\n');
fs.mkdirSync(path.join(root, 'docs/research/agent-packages'), { recursive: true });
fs.writeFileSync(path.join(root, 'docs/research/agent-packages/metadata.json'), JSON.stringify({ checkedAt: new Date().toISOString(), sumsUrl, sums, packages: metadata }, null, 2) + '\n');
fs.writeFileSync(path.join(root, 'docs/research/agent-packages/codex.json'), JSON.stringify({ checkedAt: new Date().toISOString(), packages: codexMetadata }, null, 2) + '\n');

import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const android = path.join(root, 'android');
const lock = JSON.parse(fs.readFileSync(path.join(android, 'runtime-assets.lock.json'), 'utf8'));
const hash = (data) => crypto.createHash('sha256').update(data).digest('hex');
const targets = [
  ['proot', 'bin/proot', 'libproot.so'],
  ['proot', 'libexec/proot/loader', 'libprootloader.so'],
  ['libtalloc', 'lib/libtalloc.so.2.5.0', 'libtalloc.so'],
  ['libandroid-shmem', 'lib/libandroid-shmem.so', 'libandroid-shmem.so'],
];

if (process.argv.includes('--prepare')) {
  const cache = path.join(root, '.cache', 'runtime-locked');
  fs.mkdirSync(cache, { recursive: true });
  for (const [arch, abi] of [['x86_64', 'x86_64'], ['aarch64', 'arm64-v8a']]) {
    for (const [name, entry, filename] of targets) {
      const pkg = lock.packages.find((p) => p.arch === arch && p.name === name);
      if (!pkg) throw new Error(`Missing pinned package: ${arch}/${name}`);
      const deb = path.join(cache, `${pkg.sha256}.deb`);
      if (!fs.existsSync(deb)) {
        const response = await fetch(pkg.url, { signal: AbortSignal.timeout(60000) });
        if (!response.ok) throw new Error(`Download failed: ${pkg.url} ${response.status}`);
        fs.writeFileSync(deb, Buffer.from(await response.arrayBuffer()));
      }
      const data = fs.readFileSync(deb);
      if (hash(data) !== pkg.sha256) throw new Error(`Package hash mismatch: ${name}/${arch}`);
      if (data.subarray(0, 8).toString() !== '!<arch>\n') throw new Error('Invalid Debian archive');
      let payload;
      for (let offset = 8; offset + 60 <= data.length;) {
        const header = data.subarray(offset, offset + 60).toString();
        const size = Number(header.slice(48, 58).trim());
        if (!Number.isSafeInteger(size) || size < 0 || offset + 60 + size > data.length) throw new Error('Invalid ar member');
        if (header.slice(0, 16).trim().replace(/\/$/, '').startsWith('data.tar.')) payload = data.subarray(offset + 60, offset + 60 + size);
        offset += 60 + size + size % 2;
      }
      if (!payload) throw new Error('Missing Debian payload');
      const tar = path.join(cache, `${pkg.sha256}.tar.xz`);
      fs.writeFileSync(tar, payload);
      // Extract only the named member to stdout. No archive-supplied path writes to the host.
      const binary = execFileSync(process.platform === 'win32' ? 'tar.exe' : 'tar', ['-xOf', tar, `./data/data/com.termux/files/usr/${entry}`]);
      const pinned = lock.files.find((f) => f.file === `app/src/main/jniLibs/${abi}/${filename}`);
      if (!pinned || hash(binary) !== pinned.originalSha256) throw new Error('Original ELF hash mismatch');
      const before = Buffer.from('libtalloc.so.2\0'), after = Buffer.from('libtalloc.so\0\0\0');
      for (let offset = binary.indexOf(before); offset !== -1; offset = binary.indexOf(before, offset + before.length)) after.copy(binary, offset);
      if (hash(binary) !== pinned.sha256) throw new Error('Prepared ELF hash mismatch');
      const destination = path.join(android, pinned.file);
      fs.mkdirSync(path.dirname(destination), { recursive: true });
      fs.writeFileSync(destination, binary);
    }
  }
  fs.writeFileSync(path.join(android, 'app/src/main/assets/linux-catalog.json'), JSON.stringify({ engineVersion: lock.engineVersion, rootfs: lock.rootfs }, null, 2) + '\n');
}

for (const file of lock.files) {
  const destination = path.resolve(android, file.file);
  const boundary = path.join(android, 'app', 'src', 'main', 'jniLibs') + path.sep;
  if (!destination.startsWith(boundary) || !fs.existsSync(destination) || hash(fs.readFileSync(destination)) !== file.sha256)
    throw new Error(`Runtime asset missing or changed: ${file.file}; run node tools/prepare-linux-runtime.mjs --prepare`);
}
const catalog = JSON.parse(fs.readFileSync(path.join(android, 'app/src/main/assets/linux-catalog.json'), 'utf8'));
if (JSON.stringify(catalog.rootfs) !== JSON.stringify(lock.rootfs) || catalog.engineVersion !== lock.engineVersion) throw new Error('Runtime catalog differs from lock');
console.log(`Verified ${lock.files.length} runtime ELFs and both Ubuntu image definitions.`);

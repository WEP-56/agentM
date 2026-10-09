// Runs without model requests, in a disposable private workspace and PI_CODING_AGENT_DIR.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { pathToFileURL } from 'node:url';
import { createRequire } from 'node:module';

// Keep a failed bundle assertion readable; Node's default output can dump a minified source line.
process.on('uncaughtException', error => { console.error(`Pi local probe failed: ${error.message}`); process.exitCode = 1; });

const packagePath = process.argv[2];
const pi = await import(pathToFileURL(`${packagePath}/dist/bundle/index.js`).href);
const require = createRequire(`${packagePath}/package.json`);
const cwd = process.cwd();
const text = result => result.content.filter(item => item.type === 'text').map(item => item.text).join('\n');
await pi.createWriteTool(cwd).execute('probe-write', { path: 'probe.txt', content: 'agentM Pi 本地工具\n' });
assert.match(text(await pi.createReadTool(cwd).execute('probe-read', { path: 'probe.txt' })), /agentM Pi 本地工具/);
await pi.createEditTool(cwd).execute('probe-edit', { path: 'probe.txt', edits: [{ oldText: '本地工具', newText: '读写成功' }] });
assert.equal(fs.readFileSync('probe.txt', 'utf8'), 'agentM Pi 读写成功\n');
assert.match(text(await pi.createBashTool(cwd).execute('probe-bash', { command: 'printf AGENTM_PI_BASH_OK', timeout: 10 })), /AGENTM_PI_BASH_OK/);
assert.match(text(await pi.createGrepTool(cwd).execute('probe-grep', { pattern: 'agentM', path: '.' })), /probe.txt/);
assert.match(text(await pi.createFindTool(cwd).execute('probe-find', { pattern: '*.txt', path: '.' })), /probe.txt/);
console.log('AGENTM_PI_TOOLS_OK: read/write/edit/bash/grep/find');

const { QuickJS } = await import(pathToFileURL(require.resolve('quickjs-wasi')).href);
const vm = await QuickJS.create({ wasm: fs.readFileSync(require.resolve('quickjs-wasi/quickjs.wasm')) });
try { const value = vm.evalCode('21 * 2'); try { assert.equal(value.toNumber(), 42); } finally { value.dispose(); } }
finally { vm.dispose(); }
const photon = require('@silvia-odwyer/photon-node');
const image = new photon.PhotonImage(new Uint8Array([255, 0, 0, 255]), 1, 1);
try { assert.equal(image.get_width(), 1); assert.equal(image.get_height(), 1); }
finally { image.free(); }
console.log('AGENTM_PI_WASM_OK: QuickJS evaluation / Photon image');

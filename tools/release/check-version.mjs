import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const gradle = fs.readFileSync(path.join(root, 'android/app/build.gradle.kts'), 'utf8');
const ui = fs.readFileSync(path.join(root, 'uiux-design/src/data/agents.ts'), 'utf8');
const version = gradle.match(/versionName\s*=\s*"([^"]+)"/)?.[1];
const code = Number(gradle.match(/versionCode\s*=\s*(\d+)/)?.[1]);
const uiVersion = ui.match(/APP_VERSION\s*=\s*"([^"]+)"/)?.[1];
const tag = process.argv[2] ?? process.env.GITHUB_REF_NAME;
if (!/^v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-(?:alpha|beta|rc)\.[1-9]\d*)?$/.test(tag ?? '')) {
  throw new Error('Use a version tag such as v0.14.0 or v0.15.0-rc.1.');
}
if (tag !== `v${version}` || version !== uiVersion || !Number.isSafeInteger(code) || code < 1) {
  throw new Error('Tag, Android versionName and APP_VERSION must match; versionCode must be positive.');
}
if (!fs.existsSync(path.join(root, `docs/releases/${tag}.md`))) {
  throw new Error(`Missing release notes: docs/releases/${tag}.md`);
}
if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `version=${version}\n`);
console.log(`Release ${tag}, versionCode ${code}: versions and release notes verified.`);

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const lock = JSON.parse(fs.readFileSync(path.join(root, 'uiux-design/package-lock.json'), 'utf8'));
const sections = [
  'agentM workbench dependency notices\n\nOriginal agentM code: MIT; see the repository LICENSE.\nThe following notices retain the terms of the locked production dependencies.\nMaterial Design Icons: Google, Apache-2.0 (full license below); https://github.com/google/material-design-icons\nSimple Icons: CC0-1.0; https://github.com/simple-icons/simple-icons\nBrand names and icons belong to their respective owners.',
];
for (const [name, entry] of Object.entries(lock.packages).sort(([a], [b]) => a.localeCompare(b, 'en'))) {
  if (!name || entry.dev) continue;
  const folder = path.join(root, 'uiux-design', name);
  const notices = fs.readdirSync(folder).filter(file => /^(license|licence|copying|notice|ofl)(\.|$)/i.test(file) && fs.statSync(path.join(folder, file)).isFile()).sort();
  if (!notices.length) throw new Error(`No license file for ${name}; review before release.`);
  sections.push(`${name.replace(/^node_modules\//, '')} ${entry.version} (${entry.license})\n\n` + notices.map(file => fs.readFileSync(path.join(folder, file), 'utf8').replace(/\r\n/g, '\n').trim()).join('\n\n'));
}
const text = sections.join('\n\n' + '='.repeat(72) + '\n\n') + '\n';
const target = path.join(root, 'android/app/src/main/assets/licenses/workbench-dependencies.txt');
if (process.argv.includes('--write')) fs.writeFileSync(target, text);
else if (fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n') !== text) throw new Error('Workbench notices changed. Run node tools/release/web-licenses.mjs --write and review.');
console.log(`Verified notices for ${sections.length - 1} locked workbench dependencies.`);

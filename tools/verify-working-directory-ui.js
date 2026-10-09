// Playwright CLI run-code --filename tools/verify-working-directory-ui.js
// Isolated native bridge fixtures; does not start real Agents or mutate device preferences.
async page => {
  const browser = page.context().browser();
  const check = (ok, message) => { if (!ok) throw Error(message); };
  const results = [];
  for (const [width, dark] of [[320, false], [480, true]]) {
    const context = await browser.newContext({ viewport: { width, height: 900 }, isMobile: true, hasTouch: true, colorScheme: dark ? 'dark' : 'light' });
    context.setDefaultTimeout(12000);
    await context.addInitScript(() => {
      localStorage.setItem('agentm-native-ui-v1', JSON.stringify({ state: { onboarded: true, settings: { themeMode: 'system', seed: '#6750A4', autoStartRuntime: false } }, version: 0 }));
      const record = version => ({ slot: 'fixture', version, verified: true });
      const snapshot = {
        protocolVersion: 1, appVersion: '0.14.0-dev', device: { model: 'Directory fixture', workspace: '/private/workspaces', abis: ['x86_64'], sdk: 35 },
        environment: { linuxReady: true, busy: false, reason: 'Linux 自检通过', phase: 'ready', supported: true, probes: [] },
        workingDirectory: { path: '/workspace', available: true, error: null },
        packages: { busy: false, toolsReady: true, claudeReady: true, codexReady: true, piReady: true, openCodeReady: true, dshReady: true,
          claude: record('2.1.293'), codex: record('0.161.0'), pi: record('1.1.0'), opencode: record('1.18.35'), dsh: record('0.2.0-rc.2') },
        terminal: { running: false, stopping: false, kind: 'linuxShell', directory: '/workspace' }, webSessions: {}, permissions: {}, logs: [],
      };
      const tree = {
        '/workspace': ['Project Alpha', '中文项目', '不可读取'],
        '/workspace/Project Alpha': ['src'], '/workspace/Project Alpha/src': [], '/workspace/中文项目': [],
        '/root': ['repo'], '/root/repo': [],
      };
      const fixture = window.directoryFixture = { requests: [], snapshot, tree, launched: [], failSave: false };
      const reply = (id, value) => queueMicrotask(() => window.AgentMHost.onmessage({ data: JSON.stringify({ id, ok: true, value }) }));
      const error = (id, message) => queueMicrotask(() => window.AgentMHost.onmessage({ data: JSON.stringify({ id, ok: false, error: { code: 'WORKING_DIRECTORY_ERROR', message } }) }));
      const list = path => ({ path, parent: path === '/workspace' || path === '/root' ? null : path.slice(0, path.lastIndexOf('/')), entries: tree[path], offset: 0, more: false,
        roots: [{ name: '工作区', path: '/workspace' }, { name: 'Linux home', path: '/root' }] });
      window.AgentMHost = { onmessage: null, postMessage(raw) {
        const request = JSON.parse(raw); fixture.requests.push(request); const { id, method, params: p } = request;
        if (method === 'inspect' || method === 'checkLinux') return reply(id, snapshot);
        if (method === 'listWorkingDirectories') return tree[p.path] ? reply(id, list(p.path)) : error(id, '目录不存在或无法读取');
        if (method === 'setWorkingDirectory') {
          if (fixture.failSave) return error(id, '工作目录保存失败，请重试');
          if (!tree[p.path]) return error(id, '目录已被移除，请重新选择');
          snapshot.workingDirectory = { path: p.path, available: true, error: null }; return reply(id, snapshot.workingDirectory);
        }
        if (method === 'createWorkingDirectory') {
          const path = p.path + '/' + p.name;
          if (tree[path]) return error(id, '同名目录已存在');
          tree[p.path].push(p.name); tree[path] = []; return reply(id, list(path));
        }
        if (method === 'openTerminal') {
          if (snapshot.terminal.running && snapshot.terminal.kind === p.kind) return reply(id, { opened: true });
          if (p.kind !== undefined && p.kind !== 'deviceShell' && !snapshot.workingDirectory.available) return error(id, '工作目录不存在，请重新选择');
          const directory = p.kind === undefined || p.kind === 'deviceShell' ? '/private/workspaces' : snapshot.workingDirectory.path;
          fixture.launched.push({ kind: p.kind ?? 'deviceShell', directory });
          snapshot.terminal = { running: true, kind: p.kind ?? 'deviceShell', stopping: false, directory };
          return reply(id, { opened: true });
        }
        if (method === 'openWeb') { fixture.launched.push({ kind: p.kind + '-web', directory: '/workspace' }); return reply(id, { opened: true }); }
        reply(id, { opened: true });
      } };
    });
    const target = await context.newPage();
    const errors = []; target.on('pageerror', e => errors.push(e.message));
    try {
      await target.goto('http://127.0.0.1:5173/');
      await target.getByRole('button', { name: '选择工作目录', exact: true }).waitFor();
      const panel = target.getByRole('heading', { name: 'Ubuntu 已就绪', exact: true }).locator('..');
      check(await panel.getByRole('button', { name: '选择工作目录', exact: true }).count() === 1, 'Picker was placed outside the existing Ubuntu panel');
      check((await panel.innerText()).includes('/workspace'), 'Default directory missing');
      await target.screenshot({ path: `output/playwright/workdir-home-${width}.png` });
      await panel.getByRole('button', { name: '选择工作目录', exact: true }).click();
      const dialog = target.getByRole('dialog', { name: '选择工作目录', exact: true });
      await dialog.getByRole('button', { name: 'Project Alpha', exact: true }).click();
      await dialog.getByRole('button', { name: 'src', exact: true }).click();
      await dialog.getByText('当前目录下没有子目录，可以使用此目录或新建目录。').waitFor();
      check(await target.evaluate(() => window.directoryFixture.requests.filter(r => r.method === 'setWorkingDirectory').length) === 0, 'Browsing applied a directory');
      await target.evaluate(() => window.agentMBack());
      await dialog.waitFor({ state: 'hidden' });
      check((await panel.innerText()).includes('/workspace') && !(await panel.innerText()).includes('Project Alpha'), 'Back changed the directory');
      await panel.getByRole('button', { name: '选择工作目录', exact: true }).click();
      await dialog.getByRole('button', { name: 'Project Alpha', exact: true }).click();
      await dialog.getByRole('button', { name: '使用此目录', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
      await panel.getByText('/workspace/Project Alpha', { exact: true }).waitFor();
      check(await target.evaluate(() => window.directoryFixture.launched.length) === 0, 'Selecting a folder started an Agent');
      const card = name => target.getByText(name, { exact: true }).locator('..').locator('..');
      await card('Claude Code').getByRole('button', { name: '启动', exact: true }).click();
      check(await target.evaluate(() => window.directoryFixture.launched[0].directory) === '/workspace/Project Alpha', 'Agent did not use selected directory');
      await target.evaluate(() => window.dispatchEvent(new Event('agentm:resume')));
      await card('Claude Code').getByRole('button', { name: '打开', exact: true }).waitFor();
      await panel.getByRole('button', { name: '选择工作目录', exact: true }).click();
      await dialog.getByRole('button', { name: 'Linux home', exact: true }).click();
      await dialog.getByRole('button', { name: 'repo', exact: true }).click();
      await dialog.getByRole('button', { name: '使用此目录', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
      await card('Claude Code').getByRole('button', { name: '打开', exact: true }).click();
      check(await target.evaluate(() => window.directoryFixture.launched.length) === 1, 'Existing terminal was recreated after selection');
      check(await target.evaluate(() => window.directoryFixture.snapshot.terminal.directory) === '/workspace/Project Alpha', 'Existing terminal directory changed');
      await target.evaluate(() => { window.directoryFixture.snapshot.terminal.running = false; window.dispatchEvent(new Event('agentm:resume')); });
      await panel.getByRole('button', { name: '打开 Linux 终端', exact: true }).click();
      check(await target.evaluate(() => window.directoryFixture.launched.at(-1).directory) === '/root/repo', 'Linux shell did not use selection');
      await target.evaluate(() => { window.directoryFixture.snapshot.terminal.running = false; window.dispatchEvent(new Event('agentm:resume')); });
      await card('OpenCode').getByRole('button', { name: 'TUI', exact: true }).click();
      check(await target.evaluate(() => window.directoryFixture.launched.at(-1).kind) === 'opencode', 'OpenCode TUI action missing');
      check(await target.evaluate(() => window.directoryFixture.launched.at(-1).directory) === '/root/repo', 'OpenCode TUI directory wrong');
      await target.evaluate(() => { window.directoryFixture.snapshot.terminal.running = false; window.dispatchEvent(new Event('agentm:resume')); });
      await card('OpenCode').getByRole('button', { name: 'WebUI', exact: true }).click();
      check(await target.evaluate(() => window.directoryFixture.launched.at(-1).directory) === '/workspace', 'WebUI inherited terminal selection');
      await panel.getByRole('button', { name: '选择工作目录', exact: true }).click();
      await dialog.getByRole('button', { name: '工作区', exact: true }).click();
      await dialog.getByRole('button', { name: '不可读取', exact: true }).click();
      await dialog.getByText('目录不存在或无法读取', { exact: true }).waitFor();
      check(await dialog.getByRole('button', { name: '使用此目录', exact: true }).isDisabled(), 'Unreadable folder could be selected');
      await dialog.getByRole('button', { name: '工作区', exact: true }).click();
      await dialog.getByRole('button', { name: '新建目录', exact: true }).click();
      await dialog.getByLabel('新目录名称', { exact: true }).fill('新的项目');
      await dialog.getByRole('button', { name: '创建目录', exact: true }).click();
      await dialog.getByText('/workspace/新的项目', { exact: true }).waitFor();
      check(await target.evaluate(() => window.directoryFixture.snapshot.workingDirectory.path) === '/root/repo', 'Creating a folder changed selection');
      await target.screenshot({ path: `output/playwright/workdir-picker-${width}.png` });
      await target.evaluate(() => { window.directoryFixture.failSave = true; });
      await dialog.getByRole('button', { name: '使用此目录', exact: true }).click();
      await dialog.getByText('工作目录保存失败，请重试', { exact: true }).waitFor();
      check(await target.evaluate(() => window.directoryFixture.snapshot.workingDirectory.path) === '/root/repo', 'Failed save changed selection');
      await target.evaluate(() => { window.directoryFixture.failSave = false; });
      await dialog.getByRole('button', { name: '刷新目录', exact: true }).click();
      await dialog.getByRole('button', { name: '使用此目录', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
      await panel.getByText('/workspace/新的项目', { exact: true }).waitFor();
      await target.getByRole('button', { name: '环境', exact: true }).click();
      await target.getByRole('button', { name: '首页', exact: true }).click();
      await panel.getByText('/workspace/新的项目', { exact: true }).waitFor();
      await target.evaluate(() => {
        const f = window.directoryFixture; delete f.tree['/workspace/新的项目'];
        f.snapshot.workingDirectory.available = false; f.snapshot.workingDirectory.error = '目录已被移除';
        f.snapshot.terminal.running = false; window.dispatchEvent(new Event('agentm:resume'));
      });
      await panel.getByText('目录不可用，请重新选择后启动。', { exact: true }).waitFor();
      await panel.getByRole('button', { name: '选择工作目录', exact: true }).click();
      await dialog.getByText('目录不存在或无法读取', { exact: true }).waitFor();
      await dialog.getByRole('button', { name: '工作区', exact: true }).click();
      await dialog.getByRole('button', { name: '使用此目录', exact: true }).click();
      await dialog.waitFor({ state: 'hidden' });
      check(await target.evaluate(() => !window.directoryFixture.requests.some(r => ['stopTerminal', 'stopWeb', 'managePackages'].includes(r.method))), 'Selection mutated existing sessions or packages');
      check(await target.evaluate(() => document.documentElement.scrollWidth <= innerWidth), 'Horizontal overflow');
      check(errors.length === 0, errors.join('\n'));
      results.push({ width, dark, passed: 'existing purple panel, browse/back, explicit selection, native preference, new terminal and TUI directory, retained session, independent WebUI, create, failed save and missing-folder recovery' });
    } catch (e) {
      await target.screenshot({ path: `output/playwright/workdir-failed-${width}.png` });
      throw Error(String(e) + '\n' + errors.join('\n') + '\n' + await target.locator('body').ariaSnapshot());
    } finally { await context.close(); }
  }
  return results;
}

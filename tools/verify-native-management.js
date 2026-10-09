// Playwright CLI: run-code --filename tools/verify-native-management.js
// Isolated bridge fixtures exercise the real native React branch; no device or Agent is contacted.
async page => {
  const browser = page.context().browser();
  const check = (value, message) => { if (!value) throw new Error(message); };
  const results = [];
  async function scenario(options) {
    const context = await browser.newContext({ viewport: { width: options.width || 360, height: 800 }, isMobile: true, hasTouch: true });
    context.setDefaultTimeout(12000);
    context.setDefaultNavigationTimeout(12000);
    await context.addInitScript(options => {
      if (!localStorage.getItem('agentm-native-ui-v1')) localStorage.setItem('agentm-native-ui-v1', JSON.stringify({ version: 0, state: {
        onboarded: options.onboarded, onboardingStep: options.step || 0,
        settings: { themeMode: options.dark ? 'dark' : 'light', seed: '#6750A4', autoStartRuntime: false },
      } }));
      const installed = options.installed;
      const p = {
        busy: false, phase: 'idle', message: '等待操作', toolsReady: !options.fresh,
        nodeVersion: '24.21.0', claudeVersion: '2.1.293', codexVersion: '0.161.0', opencodeVersion: '1.18.35', piVersion: '1.1.0', dshVersion: '0.2.0-rc.2',
        claudeReady: !!installed, codexReady: false, openCodeReady: false, piReady: false, dshReady: false,
        ...(!options.fresh ? { node: { slot: 'node-test', version: '24.21.0', verified: true, probeOutput: 'node npm git python OK', checkedAt: Date.now() } } : {}),
        ...(installed ? { claude: { slot: 'claude-test', version: '2.1.293', verified: true, probeOutput: 'Claude Code 2.1.293', checkedAt: Date.now() } } : {}), updates: {},
      };
      window.fixture = {
        requests: [], updateMode: 'failed', snapshot: {
          protocolVersion: 1, appVersion: '0.10.0-dev',
          device: { model: 'UI regression fixture', androidVersion: '15', sdk: 35, abis: ['x86_64'], webViewVersion: 'fixture', availableBytes: 8 * 1024 ** 3, totalBytes: 16 * 1024 ** 3, workspace: '/data/user/0/dev.agentm.app/files/workspaces' },
          environment: { linuxReady: !options.fresh, busy: false, supported: true, distribution: 'Ubuntu 24.04.5', reason: options.fresh ? '尚未安装 Ubuntu' : 'Ubuntu 已就绪', phase: options.fresh ? 'notInstalled' : 'ready', probes: [], architecture: 'amd64', engineVersion: '5.1', downloadSize: 30 * 1024 ** 2 },
          packages: p, terminal: { running: false, stopping: false, kind: 'linuxShell' }, webSessions: {},
          permissions: { notifications: false, storage: true, battery: false }, logs: [],
        },
      };
      const reply = (id, value) => queueMicrotask(() => window.AgentMHost.onmessage({ data: JSON.stringify({ id, ok: true, value }) }));
      window.AgentMHost = { onmessage: null, postMessage(raw) {
        const request = JSON.parse(raw); window.fixture.requests.push(request);
        const { id, method, params } = request;
        if (method === 'inspect' || method === 'checkLinux') return reply(id, window.fixture.snapshot);
        if (method === 'listProviders') return reply(id, { kind: params.kind, revision: 'fixture', nativeRevision: 'fixture', providers: [], hasCurrent: false });
        if (method === 'readClaudeConfig') return reply(id, { revision: 'test', exists: false, path: '~/.claude/settings.json', baseUrl: '', model: '', authMode: 'native', hasApiKey: false, hasAuthToken: false, canRestore: false, overrides: [], busy: false });
        if (method === 'listClaudeProfiles') return reply(id, { revision: 'test', profiles: [], canCompare: true, limit: 20 });
        if (method === 'installLinux') {
          Object.assign(window.fixture.snapshot.environment, { busy: true, phase: 'downloading', downloadedBytes: 10, totalBytes: 100, operationId: 'linux-fixture' });
          return reply(id, { operationId: 'linux-fixture' });
        }
        if (method === 'managePackages') {
          p.action = params.action; p.operationId = 'fixture-operation'; p.error = null;
          if (params.action === 'checkUpdateClaude') {
            p.busy = false; p.phase = window.fixture.updateMode === 'failed' ? 'failed' : 'done'; p.message = '在线检查已返回';
            p.updates.claude = window.fixture.updateMode === 'failed' ? { status: 'failed', error: 'fixture offline', upstreamVersion: '2.1.294', supportedVersion: '2.1.294', checkedAt: Date.now() - 5000, updateAvailable: false } :
              { status: 'checked', upstreamVersion: '2.1.294', supportedVersion: '2.1.294', checkedAt: Date.now(), updateAvailable: true };
          } else { p.busy = true; p.phase = 'downloading'; p.message = '原生任务执行中'; p.downloadedBytes = 10; p.totalBytes = 100; }
          return reply(id, { operationId: p.operationId });
        }
        if (method === 'storageUsage') return reply(id, { checkedAt: Date.now(), roots: [{ id: 'workspace', name: '工作区', hostPath: '/data/user/0/dev.agentm.app/files/workspaces', guestPath: '/workspace', bytes: 2048, entries: 2, incomplete: false }] });
        if (method === 'listStorage') return reply(id, { root: params.root, path: params.path, offset: 0, more: false, entries: [{ name: 'src', directory: true, link: false, bytes: 0 }, { name: 'external', directory: false, link: true, bytes: 0 }] });
        reply(id, { opened: true });
      } };
    }, options);
    const target = await context.newPage();
    const errors = []; target.on('pageerror', e => errors.push(e.message));
    await target.goto('http://127.0.0.1:5173/');
    return { context, target, errors };
  }
  async function snapshot(target, name) {
    const tree = await target.locator('body').ariaSnapshot();
    check(tree.length > 40, name + ': empty accessibility tree');
    check(await target.evaluate(() => document.documentElement.scrollWidth <= innerWidth), name + ': horizontal page overflow');
    return tree;
  }

  const { context, target, errors } = await scenario({ onboarded: true, width: 320 });
  try {
    await target.locator('nav').waitFor();
    await snapshot(target, 'home');
    check(await target.getByRole('button', { name: /^(安装|卸载|停止)/ }).count() === 0, 'home must only launch or navigate');
    await target.getByRole('button', { name: '配置', exact: true }).click();
    await target.getByRole('tab', { name: 'Claude Code', exact: true }).waitFor();
    const names = ['Claude Code', 'Codex', 'OpenCode', 'Pi', 'DSH'];
    for (const name of names) {
      await snapshot(target, 'config-' + name);
      await target.getByRole('tab', { name, exact: true }).click();
      await target.locator('summary:visible').filter({ hasText: /^版本$/ }).click();
      check(await target.getByRole('button', { name: /^安装 / }).count() === 1, name + ': install must be visible when absent');
      check(await target.getByRole('button', { name: '在线检查更新', exact: true }).count() === 1, name + ': online check missing');
    }
    await target.screenshot({ path: 'output/playwright/native-management-config-320.png' });
    await target.getByRole('button', { name: '安装 0.2.0-rc.2', exact: true }).click();
    await target.getByText('原生任务执行中', { exact: true }).waitFor();
    check(await target.locator('[data-operation-id="fixture-operation"]').count() === 1, 'progress must use native operation id');
    check(await target.getByRole('button', { name: '配置', exact: true }).getAttribute('aria-current') === 'page', 'install must not force navigation to environment');
    await target.evaluate(() => { const p = window.fixture.snapshot.packages; p.busy = false; p.phase = 'failed'; p.message = '测试下载失败'; p.error = 'fixture failure'; });
    await target.getByText('测试下载失败', { exact: true }).waitFor();
    await target.getByRole('button', { name: '环境', exact: true }).click();
    await target.getByRole('button', { name: '本地工具自检', exact: true }).waitFor();
    await snapshot(target, 'environment');
    check(await target.getByRole('button', { name: /在线检查更新|卸载|安装 (Claude|Codex|OpenCode|Pi|DSH)/ }).count() === 0, 'environment exposes Agent management');
    await target.getByRole('button', { name: '本地工具自检', exact: true }).click();
    check(await target.evaluate(() => window.fixture.requests.filter(r => r.method === 'managePackages').at(-1).params.action) === 'checkTools', 'environment ran Agent probes');
    await target.getByRole('button', { name: '统计存储占用', exact: true }).click();
    await target.getByRole('button', { name: /工作区 ·/ }).click();
    await target.getByRole('button', { name: /链接（不进入）/ }).waitFor();
    check(await target.getByRole('button', { name: /链接（不进入）/ }).isDisabled(), 'symlink must not be browseable');
    await target.screenshot({ path: 'output/playwright/native-management-storage-320.png' });
    check(errors.length === 0, errors.join('\n'));
    results.push('320px: all five absent Agent tabs, real action dispatch/progress, failed task, page responsibilities, storage links');
  } finally { await context.close(); }

  const update = await scenario({ onboarded: true, installed: true, dark: true, width: 480 });
  try {
    const t = update.target;
    await t.locator('nav').waitFor(); await snapshot(t, 'update-home');
    await t.getByRole('button', { name: '配置', exact: true }).click();
    await t.locator('summary:visible').filter({ hasText: /^版本$/ }).click();
    await t.getByRole('button', { name: '在线检查更新', exact: true }).waitFor(); await snapshot(t, 'update-config');
    await t.getByRole('button', { name: '在线检查更新', exact: true }).click();
    await t.getByText(/在线检查失败，无法确认最新状态/).waitFor();
    check(await t.getByRole('button', { name: /^更新至/ }).count() === 0, 'offline check exposed update');
    check(await t.getByText('当前安装与上游版本一致', { exact: true }).count() === 0, 'offline check claimed latest');
    await t.evaluate(() => { window.fixture.updateMode = 'available'; });
    await t.getByRole('button', { name: '在线检查更新', exact: true }).click();
    await t.getByRole('button', { name: '更新至 2.1.294', exact: true }).click();
    check(await t.evaluate(() => window.fixture.requests.filter(r => r.method === 'managePackages').at(-1).params.action) === 'updateClaude', 'update was mapped to an install');
    await t.evaluate(() => { const p = window.fixture.snapshot.packages; p.busy = false; p.phase = 'failed'; p.message = '更新校验失败，原版本保留'; p.error = 'digest mismatch'; });
    await t.getByText('更新校验失败，原版本保留', { exact: true }).waitFor();
    check(await t.locator('dd').first().innerText() === '2.1.293', 'UI replaced old version after failed update');
    await t.getByRole('button', { name: '卸载', exact: true }).click();
    await t.getByText(/配置、登录信息、会话和工作区将保留/).waitFor();
    await t.getByRole('button', { name: '取消', exact: true }).click();
    await t.getByRole('tab', { name: 'Claude Code', exact: true }).scrollIntoViewIfNeeded();
    await t.screenshot({ path: 'output/playwright/native-management-update-dark.png' });
    await snapshot(t, 'update-failed'); check(update.errors.length === 0, update.errors.join('\n'));
    results.push('480px dark: offline check, retry, update action, retained installed version and uninstall confirmation');
  } finally { await update.context.close(); }

  const onboarding = await scenario({ onboarded: false, step: 2, width: 360 });
  try {
    const t = onboarding.target;
    await t.getByRole('heading', { name: '准备基础环境', exact: true }).waitFor();
    await snapshot(t, 'onboarding-resume');
    await t.reload();
    await t.getByRole('heading', { name: '准备基础环境', exact: true }).waitFor();
    check(await t.getByRole('button', { name: /^安装 Ubuntu/ }).count() === 0, 'existing environment prompted reinstall');
    await t.getByRole('button', { name: '继续', exact: true }).click();
    await t.getByRole('button', { name: '安装 2.1.293', exact: true }).waitFor(); await snapshot(t, 'onboarding-agents');
    await t.getByRole('button', { name: '安装 2.1.293', exact: true }).click();
    await t.evaluate(() => { const p = window.fixture.snapshot.packages; p.busy = false; p.phase = 'failed'; p.message = '测试 Agent 安装失败'; p.error = 'fixture failure'; });
    await t.getByText('测试 Agent 安装失败', { exact: true }).waitFor();
    await t.getByRole('button', { name: '查看准备结果 / 跳过', exact: true }).click();
    await t.getByRole('heading', { name: '基础环境已就绪', exact: true }).waitFor();
    await t.screenshot({ path: 'output/playwright/native-management-onboarding.png' });
    await t.getByRole('button', { name: '进入工作台', exact: true }).click(); await t.locator('nav').waitFor();
    check(await t.evaluate(() => JSON.parse(localStorage.getItem('agentm-native-ui-v1')).state.onboarded), 'onboarding completion not persisted');
    check(await t.evaluate(() => !window.fixture.requests.some(r => r.method === 'installLinux' || r.method === 'openPermission')), 'onboarding reinstalled or forced permissions');
    check(onboarding.errors.length === 0, onboarding.errors.join('\n'));
    results.push('360px onboarding: persisted step, existing environment reuse, optional Agent failure, completion with permissions denied');
  } finally { await onboarding.context.close(); }

  const fresh = await scenario({ onboarded: false, fresh: true, width: 360 });
  try {
    const t = fresh.target;
    await t.getByRole('heading', { name: '你的移动开发工作台', exact: true }).waitFor(); await snapshot(t, 'fresh-welcome');
    await t.getByRole('button', { name: '继续', exact: true }).click();
    await t.getByRole('heading', { name: '检查设备与依赖', exact: true }).waitFor();
    await t.getByRole('button', { name: '继续', exact: true }).click();
    await t.getByRole('heading', { name: '准备基础环境', exact: true }).waitFor();
    check(await t.getByRole('button', { name: '继续', exact: true }).isDisabled(), 'unprepared environment allowed advancing to Agent setup');
    check(await t.getByRole('button', { name: '安装开发工具', exact: true }).isDisabled(), 'tools were installable without Linux');
    await t.getByRole('button', { name: '安装 Ubuntu', exact: true }).click();
    check(await t.evaluate(() => window.fixture.requests.some(r => r.method === 'installLinux')), 'Linux preparation did not reach native bridge');
    await t.evaluate(() => Object.assign(window.fixture.snapshot.environment, { busy: false, phase: 'ready', linuxReady: true, reason: 'Ubuntu 已就绪' }));
    await t.getByRole('button', { name: '打开 Linux 终端', exact: true }).waitFor();
    await t.getByRole('button', { name: '安装开发工具', exact: true }).click();
    check(await t.evaluate(() => window.fixture.requests.filter(r => r.method === 'managePackages').at(-1).params.action) === 'installTools', 'tools bypassed shared installer');
    await t.evaluate(() => Object.assign(window.fixture.snapshot.packages, { busy: false, phase: 'done', toolsReady: true, message: '开发工具安装完成' }));
    await t.getByText('开发工具已就绪', { exact: true }).waitFor();
    await t.getByRole('button', { name: '继续', exact: true }).click();
    await t.getByRole('button', { name: '查看准备结果 / 跳过', exact: true }).click();
    await t.getByRole('button', { name: '进入工作台', exact: true }).click(); await t.locator('nav').waitFor();
    await t.getByRole('button', { name: '环境', exact: true }).click();
    await t.getByRole('button', { name: '设置向导', exact: true }).click();
    await t.getByRole('heading', { name: '检查设备与依赖', exact: true }).waitFor();
    check(await t.evaluate(() => window.fixture.snapshot.environment.linuxReady && window.fixture.snapshot.packages.toolsReady), 'manual onboarding reentry cleared environment');
    check(fresh.errors.length === 0, fresh.errors.join('\n'));
    results.push('360px fresh onboarding: dependency gating, native Linux/tools actions, skip all Agents, manual guide reentry without clearing data');
  } finally { await fresh.context.close(); }
  return { passed: results };
}

import { useState } from 'react';
import { MdPlayArrow } from 'react-icons/md';
import type { AgentDef } from '@/data/agents';
import { useApp } from '@/store/useApp';
import { nativeRequest, type ManagedPackage, type NativeSnapshot } from '@/platform/native';
import { Button } from '@/components/md/Button';
import { AgentIcon } from '@/components/Brand';
import { ListGroup } from '@/components/md/Layout';
import { managedAgent, packageAction, type ManagedAgentId } from '@/platform/managedAgents';

export function NativeAgentCard({ def }: { def: AgentDef }) {
  const native = useApp(s => s.native);
  const launch = useApp(s => s.launchAgent);
  const agent = managedAgent(def.id)!;
  const record = native?.packages[agent.id];
  const terminal = native?.terminal.kind === def.id && native.terminal.running;
  const web = def.id === 'opencode' || def.id === 'dsh' ? native?.webSessions[def.id] : undefined;
  const ready = native?.packages[agent.ready] && native.packages.toolsReady && native.environment.linuxReady;
  const stopping = (terminal && native?.terminal.stopping) || web?.state === 'stopping' || web?.state === 'stop-unconfirmed';
  const prepare = () => {
    useApp.setState({ configAgent: agent.id });
    useApp.getState().setTab(native?.packages.toolsReady ? 'config' : 'env');
  };
  const openTerminal = async () => {
    try { await nativeRequest('openTerminal', { kind: def.id }); }
    catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '终端启动失败'); }
  };
  return <div className={`flex items-center gap-4 rounded-[28px] py-4 pl-4 pr-3 ${terminal || web?.running ? 'bg-secondary-container/70' : 'bg-surface-container-low'}`}>
    <AgentIcon agent={def} size={48} />
    <div className="min-w-0 flex-1">
      <div className="type-title-medium">{def.name}</div>
      <p className="mt-1 type-body-medium text-on-surface-variant">{web?.running ? web.message : terminal ? '终端运行中' : ready ? `已安装 · ${record?.version}` : native?.packages.toolsReady ? '前往配置页准备 Agent' : '请先准备开发环境'}</p>
      {(def.id === 'opencode' || def.id === 'dsh') && <p className="mt-1 type-body-small text-on-surface-variant">{def.id === 'opencode' ? 'TUI / WebUI' : 'WebUI'}</p>}
      {web?.state === 'failed' && <p className="mt-1 type-body-small text-error">Web 启动未完成，详情见配置页</p>}
    </div>
    <div className="flex shrink-0 flex-col gap-2">
      {ready ? <Button variant="tonal" icon={<MdPlayArrow />} disabled={native?.packages.busy || stopping || (def.id === 'opencode' && terminal)} onClick={() => void launch(def.id)}>{def.id === 'opencode' || def.id === 'dsh' ? 'WebUI' : terminal ? '打开' : '启动'}</Button> : <Button variant="text" onClick={prepare}>去准备</Button>}
      {def.id === 'opencode' && ready && <Button variant="outlined" disabled={native?.packages.busy || stopping || web?.running} onClick={() => void openTerminal()}>{terminal ? '打开终端' : 'TUI'}</Button>}
    </div>
  </div>;
}

function usePackages() {
  const native = useApp(s => s.native);
  const [submitting, setSubmitting] = useState(false);
  const active = native?.terminal.running || Object.values(native?.webSessions ?? {}).some(s => s.running);
  const run = async (action: string) => {
    setSubmitting(true);
    try {
      await nativeRequest('managePackages', { action });
      const snapshot = await nativeRequest<NativeSnapshot>('inspect');
      useApp.setState({ native: snapshot, logs: snapshot.logs });
    } catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '软件管理未完成'); }
    finally { setSubmitting(false); }
  };
  return { native, packages: native?.packages, run, active, unavailable: submitting || native?.packages.busy || native?.environment.busy || !native?.environment.linuxReady || active };
}

export function PackageProgress({ actions }: { actions: string[] }) {
  const p = useApp(s => s.native?.packages);
  if (!p?.action || !actions.includes(p.action)) return null;
  return <div className="mt-3" role="status" aria-live="polite" data-operation-id={p.operationId}>
    <p className="type-body-medium">{p.message}</p>
    {p.busy && p.phase === 'downloading' && <>
      <progress className="mt-2 h-2 w-full accent-primary" max={p.totalBytes || undefined} value={p.totalBytes ? p.downloadedBytes : undefined} />
      <p className="type-body-small">{((p.downloadedBytes || 0) / 1024 ** 2).toFixed(1)} MiB{p.totalBytes ? ` / ${(p.totalBytes / 1024 ** 2).toFixed(1)} MiB` : ''}</p>
    </>}
    {p.error && <p className="mt-2 whitespace-pre-wrap break-all rounded-xl bg-error-container p-3 type-body-small text-on-error-container">{p.error}</p>}
  </div>;
}

function Probe({ record }: { record?: ManagedPackage }) {
  if (!record) return null;
  return <details className="mt-4">
    <summary className="cursor-pointer type-label-large">本地自检 · {record.verified ? '通过' : '需检查'}{record.checkedAt ? ` · ${new Date(record.checkedAt).toLocaleString()}` : ''}</summary>
    <pre className="mt-2 whitespace-pre-wrap break-all text-xs">{record.probeOutput}</pre>
    {record.sandboxProbe && <><p className="mt-3 type-body-small">命令沙箱：{record.sandboxProbe.status === 'passed' ? '自检通过' : '自检未通过；与登录及会话使用状态独立'}</p><pre className="mt-2 whitespace-pre-wrap break-all text-xs">{record.sandboxProbe.output}</pre></>}
  </details>;
}

export function NativeTools() {
  const { packages: p, run, active, unavailable } = usePackages();
  return <ListGroup title="开发工具"><div className="rounded-[24px] bg-surface-container-low p-5">
    <h3 className="type-title-medium">{p?.toolsReady ? '开发工具已就绪' : '准备开发工具'}</h3>
    <p className="mt-2 type-body-medium text-on-surface-variant">Node.js {p?.nodeVersion} · npm · Git · Python · CA 证书</p>
    <PackageProgress actions={['installTools', 'checkTools']} />
    {active && <p className="mt-3 type-body-small">请在会话菜单停止终端和 Web 服务后管理软件。</p>}
    <div className="mt-4 flex flex-wrap gap-2">
      {!p?.toolsReady && <Button disabled={unavailable} onClick={() => void run('installTools')}>{p?.node ? '修复开发工具' : '安装开发工具'}</Button>}
      <Button variant="tonal" disabled={unavailable || !p?.node} onClick={() => void run('checkTools')}>本地工具自检</Button>
    </div>
    <Probe record={p?.node} />
  </div></ListGroup>;
}

export function NativeAgentPackage({ id, onPrepare }: { id: ManagedAgentId; onPrepare?: () => void }) {
  const { native, packages: p, run, active, unavailable } = usePackages();
  const [remove, setRemove] = useState(false);
  const agent = managedAgent(id)!;
  const record = p?.[id];
  const update = p?.updates?.[id];
  const pinned = p?.[agent.version];
  const actions = [agent.install, agent.remove, ...(['check', 'checkUpdate', 'update'] as const).map(a => packageAction(agent, a))];
  const fresh = update?.status === 'checked' && !!update.checkedAt && Date.now() - update.checkedAt >= 0 && Date.now() - update.checkedAt < 86400000;
  const canUpdate = fresh && record && update.updateAvailable;
  const web = id === 'opencode' || id === 'dsh' ? native?.webSessions[id] : undefined;
  return <div className="rounded-[24px] bg-surface-container-low p-5">
    <h2 className="type-title-large">{agent.name}</h2>
    <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 type-body-medium">
      <dt>已安装</dt><dd>{record?.version ?? '未安装'}{record && !p?.[agent.ready] ? ' · 需自检或修复' : ''}</dd>
      <dt>内置适配</dt><dd>{pinned ?? '读取中'}</dd>
      <dt>上游版本</dt><dd>{update?.upstreamVersion ?? '尚未在线检查'}{update?.status === 'failed' && update.upstreamVersion ? '（上次结果）' : ''}</dd>
      <dt>可适配版本</dt><dd>{fresh ? update.supportedVersion : pinned}{!fresh && update?.supportedVersion ? '（请重新检查）' : ''}</dd>
    </dl>
    {update?.checkedAt && <p className="mt-3 type-body-small text-on-surface-variant">最近成功检查：{new Date(update.checkedAt).toLocaleString()}</p>}
    {update?.status === 'failed' && <p className="mt-2 type-body-small text-error">在线检查失败，无法确认最新状态：{update.error}</p>}
    {fresh && <p className="mt-2 type-body-medium">{canUpdate ? '有可适配更新' : update.upstreamVersion !== update.supportedVersion ? '上游版本与适配版本不同，请等待适配发布' : record?.version === update.upstreamVersion ? '当前安装与上游版本一致' : '上游检查完成'}</p>}
    <p className="mt-3 type-body-small text-on-surface-variant">{update?.policy ?? (id === 'pi' || id === 'dsh' ? '固定依赖与适配组件；新版本需随 agentM 适配发布。' : '支持当前次版本系列的原生补丁；校验与自检通过后更新。')}</p>
    <PackageProgress actions={actions} />
    {!p?.toolsReady && <Button variant="text" onClick={onPrepare ?? (() => useApp.getState().setTab('env'))}>准备 Ubuntu 与开发工具</Button>}
    {active && <p className="mt-3 type-body-small">请先在会话菜单停止终端和 Web 服务，再安装、检查或卸载。</p>}
    <div className="mt-4 flex flex-wrap gap-2">
      {!p?.[agent.ready] && <Button disabled={unavailable || !p?.toolsReady} onClick={() => void run(agent.install)}>{record ? `重装内置 ${pinned}` : `安装 ${pinned ?? ''}`}</Button>}
      <Button variant="tonal" disabled={unavailable} onClick={() => void run(packageAction(agent, 'checkUpdate'))}>在线检查更新</Button>
      {canUpdate && <Button disabled={unavailable || !p?.toolsReady} onClick={() => void run(packageAction(agent, 'update'))}>更新至 {update.supportedVersion}</Button>}
      {record && <><Button variant="outlined" disabled={unavailable} onClick={() => void run(packageAction(agent, 'check'))}>本地自检</Button><Button variant="text" disabled={unavailable} onClick={() => setRemove(true)}>卸载</Button></>}
    </div>
    {remove && <div className="mt-4 rounded-xl bg-surface-container-high p-4">
      <p className="type-body-medium">卸载 {agent.name} {record?.version}？配置、登录信息、会话和工作区将保留。</p>
      <div className="mt-3 flex gap-2"><Button variant="text" onClick={() => setRemove(false)}>取消</Button><Button disabled={unavailable} onClick={() => { setRemove(false); void run(agent.remove); }}>确认卸载</Button></div>
    </div>}
    {web && <p className="mt-4 whitespace-pre-wrap break-all type-body-small">WebUI：{web.message}</p>}
    <Probe record={record} />
  </div>;
}

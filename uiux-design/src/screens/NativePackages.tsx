import { useState } from 'react';
import { MdOutlineFileDownload, MdPlayArrow, MdStop } from 'react-icons/md';
import type { AgentDef } from '@/data/agents';
import { useApp } from '@/store/useApp';
import { nativeRequest, type NativeSnapshot } from '@/platform/native';
import { Button, IconButton } from '@/components/md/Button';
import { AgentIcon } from '@/components/Brand';
import { ListGroup } from '@/components/md/Layout';
import { managedAgent, managedAgents } from '@/platform/managedAgents';

export function NativeAgentCard({ def }: { def: AgentDef }) {
  const native = useApp(s => s.native);
  const launch = useApp(s => s.launchAgent);
  const install = useApp(s => s.installAgent);
  const stop = useApp(s => s.stopAgent);
  const setTab = useApp(s => s.setTab);
  const packages = native?.packages;
  const supported = managedAgent(def.id);
  const record = supported ? packages?.[supported.id] : undefined;
  const candidate = supported ? packages?.[supported.version] : undefined;
  const terminalRunning = native?.terminal.kind === def.id && native.terminal.running;
  const web = def.id === 'opencode' || def.id === 'dsh' ? native?.webSessions?.[def.id] : undefined;
  const running = terminalRunning || web?.running;
  const stopping = (terminalRunning && native?.terminal.stopping) || web?.state === 'stopping' || web?.state === 'stop-unconfirmed';
  const ready = supported ? packages?.[supported.ready] : false;
  const busy = supported && packages?.busy;
  const message = !supported ? '尚未接入原生管理' : web?.running ? web.message : terminalRunning ? (stopping ? '正在停止…' : '终端运行中') :
    busy ? packages.message : ready ? `已安装 · ${record?.version}` : record ? '需要重新检查安装' :
    packages?.toolsReady ? `可安装 ${candidate}` : '请先准备开发工具';
  const openTerminal = async () => {
    try { await nativeRequest('openTerminal', { kind: def.id }); }
    catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '终端启动失败'); }
  };
  return <div className={`flex items-center gap-4 rounded-[28px] py-4 pl-4 pr-3 ${running ? 'bg-secondary-container/70' : 'bg-surface-container-low'}`}>
    <AgentIcon agent={def} size={48} />
    <div className="min-w-0 flex-1">
      <div className="type-title-medium">{def.name}</div>
      <div className="mt-0.5 type-body-medium text-on-surface-variant">{message}</div>
      {(def.id === 'opencode' || def.id === 'dsh') && <p className="mt-1 type-body-small text-on-surface-variant">{def.id === 'opencode' ? 'TUI / WebUI' : 'WebUI'}</p>}
      {web?.state === 'failed' && <p className="mt-1 type-body-small text-error">Web 启动未完成，可重试或在环境页查看原因</p>}
      {record?.sandboxProbe?.status === 'unavailable' && <p className="mt-1 type-body-small text-tertiary">命令沙箱检查未通过</p>}
    </div>
    {supported && <div className="flex shrink-0 flex-col items-end gap-2">
      {running && <IconButton aria-label={`停止 ${def.name}`} disabled={web?.state === 'stopping' || (terminalRunning && native?.terminal.stopping)} onClick={() => void stop(def.id)}><MdStop /></IconButton>}
      {ready ? <Button variant="tonal" icon={<MdPlayArrow />} disabled={busy || stopping || (def.id === 'opencode' && terminalRunning)} onClick={() => void launch(def.id)}>{def.id === 'opencode' || def.id === 'dsh' ? 'WebUI' : running ? '打开' : '启动'}</Button> :
        packages?.toolsReady && !record ? <Button variant="outlined" icon={<MdOutlineFileDownload />} disabled={busy} onClick={() => void install(def.id)}>安装</Button> :
        <Button variant="text" onClick={() => setTab('env')}>{busy ? '查看进度' : '准备'}</Button>}
      {def.id === 'opencode' && ready && <Button variant="outlined" disabled={busy || stopping || web?.running} onClick={() => void openTerminal()}>{terminalRunning ? '打开终端' : 'TUI'}</Button>}
    </div>}
  </div>;
}

export function NativePackages() {
  const native = useApp(s => s.native);
  const packages = native?.packages;
  const [submitting, setSubmitting] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState<typeof managedAgents[number] | null>(null);
  const webActive = Object.values(native?.webSessions ?? {}).some(session => session.running);
  const unavailable = submitting || packages?.busy || !native?.environment.linuxReady || native.terminal.running || webActive;
  const run = async (action: string) => {
    setSubmitting(true);
    try {
      await nativeRequest('managePackages', { action });
      const snapshot = await nativeRequest<NativeSnapshot>('inspect');
      useApp.setState({ native: snapshot, logs: snapshot.logs });
    } catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '软件管理未完成'); }
    finally { setSubmitting(false); setConfirmRemove(null); }
  };
  return <ListGroup title="开发工具与 Agent">
    <div className="rounded-[24px] bg-surface-container-low p-5">
      <h3 className="type-title-medium">{packages?.toolsReady ? '开发工具已就绪' : '准备开发工具'}</h3>
      <p className="mt-2 type-body-medium text-on-surface-variant">Node.js {packages?.nodeVersion ?? ''} · npm · Git · Python · CA 证书</p>
      <p className="mt-3 type-body-medium" role="status">{packages?.message ?? '正在读取状态…'}</p>
      {packages?.busy && packages.phase === 'downloading' && <div className="mt-3">
        <progress className="h-2 w-full accent-primary" max={packages.totalBytes || 1} value={packages.downloadedBytes || 0} />
        <p className="mt-1 type-body-small text-on-surface-variant">{((packages.downloadedBytes || 0) / 1024 ** 2).toFixed(1)} / {((packages.totalBytes || 0) / 1024 ** 2).toFixed(1)} MiB</p>
      </div>}
      {packages?.error && <p className="mt-3 whitespace-pre-wrap break-all rounded-xl bg-error-container p-3 type-body-small text-on-error-container">{packages.error}</p>}
      {native?.terminal.running && <p className="mt-3 type-body-small text-on-surface-variant">软件管理前需关闭当前终端会话。</p>}
      {webActive && <p className="mt-3 type-body-small text-on-surface-variant">软件管理前需停止 Web 服务。</p>}
      {Object.values(native?.webSessions ?? {}).map(session => <div key={session.id} className="mt-3 rounded-xl bg-surface-container p-3">
        <p className="type-label-large">{session.kind === 'opencode' ? 'OpenCode' : 'DSH'} WebUI</p>
        <p className="mt-1 whitespace-pre-wrap break-all type-body-small">{session.message}</p>
        {session.running && <Button variant="text" disabled={session.state === 'stopping'} onClick={() => void useApp.getState().stopAgent(session.kind)}>停止服务</Button>}
      </div>)}
      <div className="mt-4 flex flex-wrap gap-2">
        {!packages?.toolsReady && <Button disabled={unavailable} onClick={() => void run('installTools')}>{packages?.phase === 'failed' || packages?.phase === 'interrupted' ? '重试准备工具' : '安装开发工具'}</Button>}
        {managedAgents.map(agent => packages?.toolsReady && !packages[agent.ready] && <Button key={agent.id} disabled={unavailable} onClick={() => void run(agent.install)}>安装 {agent.name}</Button>)}
        <Button variant="tonal" disabled={unavailable} onClick={() => void run('checkPackages')}>检查版本</Button>
        {managedAgents.map(agent => packages?.[agent.id] && <Button key={agent.id} variant="text" disabled={unavailable} onClick={() => setConfirmRemove(agent)}>卸载 {agent.name}</Button>)}
      </div>
      {confirmRemove && <div className="mt-4 rounded-xl bg-surface-container-high p-4">
        <p className="type-body-medium">卸载 {confirmRemove.name} {packages?.[confirmRemove.id]?.version}？配置、登录信息、会话和工作区将保留。</p>
        <div className="mt-3 flex gap-2"><Button variant="text" onClick={() => setConfirmRemove(null)}>取消</Button><Button disabled={unavailable} onClick={() => void run(confirmRemove.remove)}>确认卸载</Button></div>
      </div>}
      <p className="mt-4 type-body-small text-on-surface-variant">当前使用固定版本。OpenCode 支持 TUI 与 WebUI，DSH 使用 WebUI；请在各 Agent 原生界面登录和配置。</p>
      {packages?.codex?.sandboxProbe?.status === 'unavailable' && <p className="mt-4 rounded-xl bg-secondary-container p-4 type-body-medium text-on-secondary-container">Codex 的命令沙箱自检未通过。终端可用于登录和查看设置，当前环境的工具执行兼容性尚未确认。</p>}
      {[{ name: 'Node.js 与基础工具', record: packages?.node }, ...managedAgents.map(agent => ({ name: agent.name, record: packages?.[agent.id] }))].map(({ name, record }) => record && <details key={record.slot} className="mt-4">
        <summary className="cursor-pointer type-label-large">{name} · {record.version} · 检查结果</summary>
        <pre className="mt-2 whitespace-pre-wrap break-all font-mono text-xs text-on-surface-variant">{record.probeOutput}</pre>
        {record.sandboxProbe && <><p className="mt-3 type-body-small">命令沙箱：{record.sandboxProbe.status === 'passed' ? '自检通过' : '自检未通过'}</p><pre className="mt-2 whitespace-pre-wrap break-all font-mono text-xs text-on-surface-variant">{record.sandboxProbe.output}</pre></>}
      </details>)}
    </div>
  </ListGroup>;
}

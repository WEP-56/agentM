import { useState } from 'react';
import { MdOutlineFileDownload, MdPlayArrow, MdStop } from 'react-icons/md';
import type { AgentDef } from '@/data/agents';
import { useApp } from '@/store/useApp';
import { nativeRequest, type NativeSnapshot } from '@/platform/native';
import { Button, IconButton } from '@/components/md/Button';
import { AgentIcon } from '@/components/Brand';
import { ListGroup } from '@/components/md/Layout';

export function NativeAgentCard({ def }: { def: AgentDef }) {
  const native = useApp(s => s.native);
  const launch = useApp(s => s.launchAgent);
  const install = useApp(s => s.installAgent);
  const stop = useApp(s => s.stopAgent);
  const setTab = useApp(s => s.setTab);
  const packages = native?.packages;
  const supported = def.id === 'claude';
  const running = supported && native?.terminal.kind === 'claude' && native.terminal.running;
  const ready = supported && packages?.claudeReady;
  const busy = supported && packages?.busy;
  const message = !supported ? '尚未接入原生管理' : running ? (native?.terminal.stopping ? '正在停止…' : '终端运行中') :
    busy ? packages.message : ready ? `已安装 · ${packages?.claude?.version}` : packages?.claude ? '需要重新检查安装' :
    packages?.toolsReady ? `可安装 ${packages.claudeVersion}` : '请先准备开发工具';
  return <div className={`flex items-center gap-4 rounded-[28px] py-4 pl-4 pr-3 ${running ? 'bg-secondary-container/70' : 'bg-surface-container-low'}`}>
    <AgentIcon agent={def} size={48} />
    <div className="min-w-0 flex-1">
      <div className="type-title-medium">{def.name}</div>
      <div className="mt-0.5 type-body-medium text-on-surface-variant">{message}</div>
    </div>
    {supported && <div className="flex shrink-0 items-center gap-1">
      {running && <IconButton aria-label="停止 Claude Code" disabled={native?.terminal.stopping} onClick={() => void stop('claude')}><MdStop /></IconButton>}
      {ready ? <Button variant="tonal" icon={<MdPlayArrow />} disabled={busy || native?.terminal.stopping} onClick={() => void launch('claude')}>{running ? '打开' : '启动'}</Button> :
        packages?.toolsReady && !packages.claude ? <Button variant="outlined" icon={<MdOutlineFileDownload />} disabled={busy} onClick={() => void install('claude')}>安装</Button> :
        <Button variant="text" onClick={() => setTab('env')}>{busy ? '查看进度' : '准备'}</Button>}
    </div>}
  </div>;
}

export function NativePackages() {
  const native = useApp(s => s.native);
  const packages = native?.packages;
  const [submitting, setSubmitting] = useState(false);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const unavailable = submitting || packages?.busy || !native?.environment.linuxReady || native.terminal.running;
  const run = async (action: string) => {
    setSubmitting(true);
    try {
      await nativeRequest('managePackages', { action });
      const snapshot = await nativeRequest<NativeSnapshot>('inspect');
      useApp.setState({ native: snapshot, logs: snapshot.logs });
    } catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '软件管理未完成'); }
    finally { setSubmitting(false); setConfirmRemove(false); }
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
      <div className="mt-4 flex flex-wrap gap-2">
        {!packages?.toolsReady && <Button disabled={unavailable} onClick={() => void run('installTools')}>{packages?.phase === 'failed' || packages?.phase === 'interrupted' ? '重试准备工具' : '安装开发工具'}</Button>}
        {packages?.toolsReady && !packages.claudeReady && <Button disabled={unavailable} onClick={() => void run('installClaude')}>安装 Claude Code</Button>}
        <Button variant="tonal" disabled={unavailable} onClick={() => void run('checkPackages')}>检查版本</Button>
        {packages?.claude && <Button variant="text" disabled={unavailable} onClick={() => setConfirmRemove(true)}>卸载 Claude Code</Button>}
      </div>
      {confirmRemove && <div className="mt-4 rounded-xl bg-surface-container-high p-4">
        <p className="type-body-medium">卸载 Claude Code {packages?.claude?.version}？配置、登录信息、会话和工作区将保留。</p>
        <div className="mt-3 flex gap-2"><Button variant="text" onClick={() => setConfirmRemove(false)}>取消</Button><Button disabled={unavailable} onClick={() => void run('removeClaude')}>确认卸载</Button></div>
      </div>}
      <p className="mt-4 type-body-small text-on-surface-variant">当前使用固定版本。可在配置页管理 Claude 的连接与模型，原生登录仍在终端中完成。</p>
      {[packages?.node, packages?.claude].map(record => record && <details key={record.slot} className="mt-4">
        <summary className="cursor-pointer type-label-large">{record.entry.endsWith('/node') ? 'Node.js 与基础工具' : 'Claude Code'} · {record.version} · 检查结果</summary>
        <pre className="mt-2 whitespace-pre-wrap break-all font-mono text-xs text-on-surface-variant">{record.probeOutput}</pre>
      </details>)}
    </div>
  </ListGroup>;
}

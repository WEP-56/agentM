import { useState } from 'react';
import { MdCheckCircle, MdInfoOutline, MdOutlineTerminal, MdRefresh, MdChevronRight } from 'react-icons/md';
import { useApp } from '@/store/useApp';
import { nativeRequest } from '@/platform/native';
import { Button } from '@/components/md/Button';
import { ListGroup, ListItem, TabPage } from '@/components/md/Layout';
import { AppLogo } from '@/components/Brand';
import type { NativeSnapshot } from '@/platform/native';
import { NativePackages } from './NativePackages';
import { managedAgent } from '@/platform/managedAgents';

export async function openDeviceTerminal() {
  try { await nativeRequest('openTerminal'); }
  catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '无法打开终端'); }
}
export async function openLinuxTerminal() {
  try { await nativeRequest('openTerminal', { kind: 'linuxShell' }); }
  catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '无法打开 Linux 终端'); }
}

export function NativeEnvironment() {
  const native = useApp((s) => s.native);
  const check = useApp((s) => s.checkSystem);
  const push = useApp((s) => s.push);
  const [checking, setChecking] = useState(false);
  const [starting, setStarting] = useState(false);
  const environment = native?.environment;
  const install = async () => {
    setStarting(true);
    try {
      await nativeRequest('installLinux');
      const snapshot = await nativeRequest<NativeSnapshot>('inspect');
      useApp.setState({ native: snapshot, logs: snapshot.logs });
    } catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '安装未能开始'); }
    finally { setStarting(false); }
  };
  const cancel = async () => {
    try { await nativeRequest('cancelLinuxInstall'); useApp.getState().showSnack('已请求取消，正在完成当前清理'); }
    catch (error) { useApp.getState().showSnack(error instanceof Error ? error.message : '取消未完成'); }
  };
  const refresh = async () => { setChecking(true); try { await check(); } finally { setChecking(false); } };
  return <TabPage title="环境">
    <div className="rounded-[28px] bg-primary-container p-5">
      <h2 className="type-title-large text-on-primary-container">{native?.environment.distribution ?? 'Ubuntu 环境'}</h2>
      <p className="mt-2 type-body-medium text-on-primary-container">{native?.environment.reason ?? '正在读取 Android 设备信息…'}</p>
      {environment && <p className="mt-2 type-body-small text-on-primary-container">{environment.architecture} · proot {environment.engineVersion} · 镜像 {(environment.downloadSize / 1024 ** 2).toFixed(1)} MiB</p>}
      {environment?.busy && <div className="mt-4 type-body-medium text-on-primary-container" role="status" aria-live="polite">
        {environment.phase === 'downloading' ? <>
          <progress className="h-2 w-full accent-current" max={environment.totalBytes || 1} value={environment.downloadedBytes} />
          <p className="mt-2">{(environment.downloadedBytes / 1024 ** 2).toFixed(1)} / {(environment.totalBytes / 1024 ** 2).toFixed(1)} MiB</p>
        </> : <p>{environment.phase === 'extracting' ? `已解压 ${(environment.extractedBytes / 1024 ** 2).toFixed(1)} MiB` : environment.reason}</p>}
      </div>}
      {environment?.error && <p className="mt-3 break-all rounded-xl bg-error-container p-3 type-body-small text-on-error-container">{environment.error}</p>}
      <div className="mt-4 flex flex-wrap gap-2">
        {environment?.linuxReady ? <Button onClick={() => void openLinuxTerminal()}>打开 Linux 终端</Button> : !environment?.busy && <Button disabled={starting || !environment?.supported} onClick={() => void install()}>{starting ? '准备中…' : ['failed', 'cancelled', 'interrupted'].includes(environment?.phase ?? '') ? '重试安装' : '安装 Ubuntu'}</Button>}
        {environment?.canCancel && <Button variant="tonal" onClick={() => void cancel()}>取消安装</Button>}
        <Button variant="tonal" icon={<MdRefresh />} disabled={checking || environment?.busy} onClick={() => void refresh()}>{checking ? '检查中…' : '重新检查'}</Button>
        <Button variant="tonal" icon={<MdOutlineTerminal />} onClick={() => void openDeviceTerminal()}>设备终端</Button>
      </div>
    </div>
    <NativePackages />
    <ListGroup title="设备信息">
      <ListItem headline={native?.device.model ?? '等待连接'} supporting={native ? `Android ${native.device.androidVersion} · API ${native.device.sdk}` : 'Android 原生接口'} />
      <ListItem headline="CPU 架构" supporting={native?.device.abis.join(' / ') ?? '读取中'} />
      <ListItem headline="System WebView" supporting={native?.device.webViewVersion ?? '读取中'} />
      <ListItem headline="可用存储" supporting={native ? `${(native.device.availableBytes / 1024 ** 3).toFixed(1)} GiB` : '读取中'} />
      <ListItem headline="私有工作区" supporting={<span className="break-all">{native?.device.workspace ?? '读取中'}</span>} />
    </ListGroup>
    <ListGroup title="组件检查">
      {native?.environment.probes.map((probe) => <ListItem key={probe.name} headline={probe.name} supporting={probe.detail}
        trailing={probe.status === 'passed' ? <MdCheckCircle className="text-success" /> : <MdInfoOutline className="text-on-surface-variant" />} />)}
    </ListGroup>
    <ListGroup title="诊断">
      <ListItem headline="运行日志" supporting="仅记录原生设备与生命周期事件" trailing={<MdChevronRight />} onClick={() => push({ name: 'logs' })} />
      <ListItem headline="终端状态" supporting={native?.terminal.running ? `${managedAgent(native.terminal.kind)?.name ?? (native.terminal.kind === 'linuxShell' ? 'Linux' : '设备')}会话运行中，返回后可继续使用` : '暂无运行中的会话'} />
    </ListGroup>
    {environment?.probeOutput && <ListGroup title="真实运行自检"><pre className="whitespace-pre-wrap break-all rounded-xl bg-surface-container-low p-4 font-mono text-xs text-on-surface">{environment.probeOutput}</pre></ListGroup>}
  </TabPage>;
}

export function NativeWelcome() {
  const native = useApp((s) => s.native);
  const complete = useApp((s) => s.completeOnboarding);
  const check = useApp((s) => s.checkSystem);
  return <div className="absolute inset-0 flex flex-col justify-between overflow-y-auto bg-surface px-6 pb-[calc(24px+var(--sab))] pt-[calc(64px+var(--sat))]">
    <div>
      <AppLogo size={96} />
      <h1 className="mt-8 type-display-small">agentM</h1>
      <p className="mt-3 type-body-large text-on-surface-variant">你的移动开发工作台</p>
      <div className="mt-8 rounded-[24px] bg-surface-container-low p-5">
        <h2 className="type-title-medium">Android 开发版已连接</h2>
        <p className="mt-2 type-body-medium text-on-surface-variant">{native ? `${native.device.model} · Android ${native.device.androidVersion}` : '设备信息暂不可用，请重试检查。'}</p>
        <p className="mt-4 type-body-medium text-on-surface-variant">进入工作台后可安装 Ubuntu、开发工具与 Claude Code，并使用真实 Linux 终端。</p>
        {!native && <Button variant="text" onClick={() => void check()}>重试检查</Button>}
      </div>
    </div>
    <div className="mt-8 flex flex-col gap-3">
      <Button size="lg" onClick={() => complete([], 'auto', [])}>进入工作台</Button>
      <Button size="lg" variant="tonal" icon={<MdOutlineTerminal />} onClick={() => void openDeviceTerminal()}>试用设备终端</Button>
    </div>
  </div>;
}

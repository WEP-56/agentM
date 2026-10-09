import { useEffect, useState } from 'react';
import { AppLogo } from '@/components/Brand';
import { Button } from '@/components/md/Button';
import { useApp } from '@/store/useApp';
import { managedAgents, type ManagedAgentId } from '@/platform/managedAgents';
import { NativeLinuxSetup } from './NativeEnvironment';
import { NativeTools, NativeAgentPackage } from './NativePackages';
import { AgentTabs } from './NativeConfig';

const steps = ['欢迎', '设备检查', '准备环境', '选择 Agent', '准备结果'];

export function NativeOnboarding() {
  const native = useApp(s => s.native);
  const savedStep = useApp(s => s.onboardingStep);
  const step = Math.max(0, Math.min(savedStep, steps.length - 1));
  const [agent, setAgent] = useState<ManagedAgentId>('claude');
  const [checking, setChecking] = useState(false);
  const ready = native?.environment.linuxReady && native.packages.toolsReady;
  const busy = native?.environment.busy || native?.packages.busy;
  const move = (value: number) => useApp.setState({ onboardingStep: value });
  const finish = () => useApp.getState().completeOnboarding([], 'auto', []);
  useEffect(() => {
    window.agentMBack = () => {
      const current = useApp.getState().onboardingStep;
      if (current > 0) { useApp.setState({ onboardingStep: current - 1 }); return true; }
      return false;
    };
    return () => { delete window.agentMBack; };
  }, []);
  const check = async () => { setChecking(true); try { await useApp.getState().checkSystem(); } finally { setChecking(false); } };
  return <div className="absolute inset-0 flex flex-col bg-surface pb-[var(--sab)] pt-[var(--sat)]">
    <header className="shrink-0 px-6 py-5"><p className="type-label-large text-primary">{step + 1} / {steps.length} · {steps[step]}</p></header>
    <main className="min-h-0 flex-1 space-y-5 overflow-y-auto px-5 pb-5">
      {step === 0 && <><AppLogo size={88} /><h1 className="type-display-small">你的移动开发工作台</h1><p className="type-body-large text-on-surface-variant">准备 Ubuntu 与开发工具，选择 Claude Code、Codex、OpenCode、Pi 或 DSH，在手机上进入终端和 WebUI。</p><p className="type-body-medium">已有的环境和 Agent 会直接复用。准备进度会保留，退出后可以继续。</p></>}
      {step === 1 && <><h1 className="type-headline-medium">检查设备与依赖</h1>
        <div className="space-y-3 rounded-[24px] bg-surface-container-low p-5">
          <p>{native ? `${native.device.model} · Android ${native.device.androidVersion}` : '设备信息未能读取，请重试。'}</p>
          <p>架构：{native?.device.abis.join(' / ') ?? '未知'} · {native?.environment.supported ? '支持' : '尚未确认支持'}</p>
          <p>可用存储：{native ? `${(native.device.availableBytes / 1024 ** 3).toFixed(1)} GiB` : '未知'}</p>
          <p>Ubuntu：{native?.environment.linuxReady ? '已就绪，将复用' : '待准备'}；开发工具：{native?.packages.toolsReady ? '已就绪，将复用' : '待准备'}</p>
          <p className="type-body-small text-on-surface-variant">安装 Ubuntu 至少需 768 MiB 空间；工具和 Agent 还需额外空间，DSH 安装需至少 3 GiB。通知与电池权限可稍后在设置中调整，拒绝后也可继续。</p>
          <Button variant="tonal" disabled={checking || busy} onClick={() => void check()}>{checking ? '检查中…' : '重新检查'}</Button>
        </div></>}
      {step === 2 && <><h1 className="type-headline-medium">准备基础环境</h1><p className="type-body-medium">先准备 Ubuntu，再安装开发工具。已就绪的组件无需重复安装。</p><NativeLinuxSetup /><NativeTools /></>}
      {step === 3 && <><h1 className="type-headline-medium">选择需要的 Agent</h1><p className="type-body-medium">按需逐个安装，也可以全部跳过，稍后在配置页安装。单个 Agent 安装失败不会影响进入工作台。</p>
        <AgentTabs value={agent} onChange={setAgent} /><div role="tabpanel" id={`agent-panel-${agent}`} aria-labelledby={`agent-tab-${agent}`}><NativeAgentPackage key={agent} id={agent} onPrepare={() => move(2)} /></div></>}
      {step === 4 && <><h1 className="type-headline-medium">{ready ? '基础环境已就绪' : '稍后继续准备'}</h1><p className="type-body-medium">{ready ? '从首页启动 Agent；配置页管理 Agent；环境页管理 Ubuntu 与开发工具。' : '可以先进入工作台，之后到环境页补齐基础环境。'}</p>
        <div className="space-y-3 rounded-[24px] bg-surface-container-low p-5"><p>Ubuntu：{native?.environment.linuxReady ? '已就绪' : '未就绪'}</p><p>开发工具：{native?.packages.toolsReady ? '已就绪' : '未就绪'}</p>{managedAgents.map(a => <p key={a.id}>{a.name}：{native?.packages[a.ready] ? `已安装 ${native.packages[a.id]?.version}` : native?.packages[a.id] ? '需检查' : '未安装'}</p>)}</div>
        {busy && <p role="status" className="type-body-medium">{native?.environment.busy ? native.environment.reason : native?.packages.message}。进入工作台后任务会继续。</p>}</>}
    </main>
    <footer className="flex shrink-0 flex-wrap items-center gap-2 border-t border-outline-variant px-5 py-4">
      {step > 0 && <Button variant="text" onClick={() => move(step - 1)}>上一步</Button>}
      <Button disabled={(step === 1 && !native?.environment.supported) || (step === 2 && !ready)} onClick={() => step === 4 ? finish() : move(step + 1)}>{step === 4 ? '进入工作台' : step === 3 ? '查看准备结果 / 跳过' : '继续'}</Button>
      {step < 4 && <Button variant="text" onClick={() => move(4)}>稍后准备</Button>}
    </footer>
  </div>;
}

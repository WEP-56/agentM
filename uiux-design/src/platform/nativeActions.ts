import type { StoreApi } from 'zustand';
import type { AppState } from '@/store/useApp';
import { nativeRequest, type NativeSnapshot } from './native';
import { managedAgent, type ManagedAgentId } from './managedAgents';

/** Overrides every simulated platform mutation in the Android build. */
export function nativeActions(set: StoreApi<AppState>['setState'], get: StoreApi<AppState>['getState']): Partial<AppState> {
  const inspect = async (method = 'inspect') => {
    try {
      const snapshot = await nativeRequest<NativeSnapshot>(method);
      set({ native: snapshot, logs: snapshot.logs, permissions: snapshot.permissions, booting: false });
    } catch (error) {
      set({ booting: false });
      get().showSnack(error instanceof Error ? error.message : '设备检查失败');
    }
  };
  const unavailable = () => {
    get().showSnack(get().native?.environment.linuxReady ? '此功能尚未接入，可在环境页查看已支持的程序' : '请先在环境页安装或检查 Ubuntu');
    get().setTab('env');
  };
  const configUnavailable = () => get().showSnack('配置文件管理尚未接入，未写入任何配置');
  const manage = async (action: string) => {
    try { await nativeRequest('managePackages', { action }); await inspect(); }
    catch (error) { get().showSnack(error instanceof Error ? error.message : '软件管理未完成'); }
  };
  const openAgent = async (kind: ManagedAgentId) => {
    try { await nativeRequest(kind === 'opencode' || kind === 'dsh' ? 'openWeb' : 'openTerminal', { kind }); await inspect(); }
    catch (error) { get().showSnack(error instanceof Error ? error.message : '启动未完成'); }
  };
  return {
    finishBoot: () => { void (async () => {
      await inspect();
      if (get().settings.autoStartRuntime && get().native?.environment.linuxReady) await inspect('checkLinux');
    })(); },
    completeOnboarding: () => { set({ onboarded: true, tab: 'home', stack: [] }); },
    resetPreview: () => { set({ onboarded: false, onboardingStep: 0, stack: [] }); },
    startRuntime: async () => { await inspect('checkLinux'); get().setTab('env'); },
    ensureRuntime: async () => { unavailable(); },
    stopRuntime: async () => {
      if (get().native?.terminal.kind !== 'deviceShell' && get().native?.terminal.running) {
        try { await nativeRequest('stopTerminal'); await inspect(); }
        catch (error) { get().showSnack(error instanceof Error ? error.message : '停止未完成'); }
      } else get().showSnack('当前没有运行中的 Linux 终端');
    },
    restartRuntime: async () => { await inspect('checkLinux'); get().setTab('env'); },
    installAgent: async (id) => { const agent = managedAgent(id); if (agent) await manage(agent.install); else unavailable(); },
    uninstallAgent: async (id) => { const agent = managedAgent(id); if (agent) await manage(agent.remove); else get().showSnack('未发现受管 Agent 安装'); },
    launchAgent: async (id) => { const agent = managedAgent(id); if (agent) await openAgent(agent.id); else unavailable(); },
    stopAgent: async (id) => {
      if ((id === 'opencode' || id === 'dsh') && get().native?.webSessions?.[id]?.running) {
        try { await nativeRequest('stopWeb', { kind: id }); await inspect(); }
        catch (error) { get().showSnack(error instanceof Error ? error.message : '停止未完成'); }
        return;
      }
      if (managedAgent(id) && get().native?.terminal.kind === id && get().native?.terminal.running) {
        try { await nativeRequest('stopTerminal'); await inspect(); }
        catch (error) { get().showSnack(error instanceof Error ? error.message : '停止未完成'); }
      } else get().showSnack('当前没有运行中的该 Agent');
    },
    openAgent: (id) => { const agent = managedAgent(id); if (agent) void openAgent(agent.id); else unavailable(); },
    checkSystem: () => inspect('checkLinux'),
    checkRuntimes: () => inspect('checkLinux'),
    reinstallSystem: async () => { unavailable(); },
    reinstallRuntime: async () => { unavailable(); },
    clearCache: () => get().showSnack('当前没有受管的 Linux 下载缓存'),
    clearLogs: () => { void nativeRequest('clearLogs').then(() => inspect()).catch((e: Error) => get().showSnack(e.message)); },
    setPermission: (permission) => { void nativeRequest('openPermission', { permission }).catch((e: Error) => get().showSnack(e.message)); },
    setActiveProvider: configUnavailable,
    upsertProvider: configUnavailable,
    deleteProvider: configUnavailable,
    setModel: configUnavailable,
    ensureSession: () => {},
    patchSession: () => {},
  };
}

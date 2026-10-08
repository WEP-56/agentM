import type { StoreApi } from 'zustand';
import type { AppState } from '@/store/useApp';
import { nativeRequest, type NativeSnapshot } from './native';

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
    get().showSnack(get().native?.environment.linuxReady ? 'Ubuntu 已就绪；Agent 包管理将在下一阶段接入' : '请先在环境页安装或检查 Ubuntu');
    get().setTab('env');
  };
  const configUnavailable = () => get().showSnack('配置文件管理尚未接入，未写入任何配置');
  const manage = async (action: string) => {
    try { await nativeRequest('managePackages', { action }); await inspect(); get().setTab('env'); }
    catch (error) { get().showSnack(error instanceof Error ? error.message : '软件管理未完成'); }
  };
  const openClaude = async () => {
    try { await nativeRequest('openTerminal', { kind: 'claude' }); await inspect(); }
    catch (error) { get().showSnack(error instanceof Error ? error.message : '启动未完成'); }
  };
  return {
    finishBoot: () => { void (async () => {
      await inspect();
      if (get().settings.autoStartRuntime && get().native?.environment.linuxReady) await inspect('checkLinux');
    })(); },
    completeOnboarding: () => { set({ onboarded: true, tab: 'home', stack: [] }); },
    resetPreview: () => { get().showSnack('开发版不会通过重置预览清除原生数据'); },
    startRuntime: async () => { await inspect('checkLinux'); get().setTab('env'); },
    ensureRuntime: async () => { unavailable(); },
    stopRuntime: async () => {
      if (get().native?.terminal.kind !== 'deviceShell' && get().native?.terminal.running) {
        try { await nativeRequest('stopTerminal'); await inspect(); }
        catch (error) { get().showSnack(error instanceof Error ? error.message : '停止未完成'); }
      } else get().showSnack('当前没有运行中的 Linux 终端');
    },
    restartRuntime: async () => { await inspect('checkLinux'); get().setTab('env'); },
    installAgent: async (id) => { if (id === 'claude') await manage('installClaude'); else unavailable(); },
    uninstallAgent: async (id) => { if (id === 'claude') await manage('removeClaude'); else get().showSnack('未发现受管 Agent 安装'); },
    launchAgent: async (id) => { if (id === 'claude') await openClaude(); else unavailable(); },
    stopAgent: async (id) => {
      if (id === 'claude' && get().native?.terminal.kind === 'claude' && get().native?.terminal.running) {
        try { await nativeRequest('stopTerminal'); await inspect(); }
        catch (error) { get().showSnack(error instanceof Error ? error.message : '停止未完成'); }
      } else get().showSnack('当前没有运行中的该 Agent');
    },
    openAgent: (id) => { if (id === 'claude') void openClaude(); else unavailable(); },
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

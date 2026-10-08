export interface ManagedPackage {
  slot: string; entry: string; version: string; sha256: string; source: string;
  verified: boolean; probeOutput: string; checkedAt: number;
}
export interface NativeSnapshot {
  protocolVersion: 1;
  appVersion: string;
  device: { model: string; androidVersion: string; sdk: number; abis: string[]; webViewVersion: string; availableBytes: number; totalBytes: number; workspace: string };
  environment: {
    status: string; phase: string; linuxReady: boolean; distribution: string; reason: string;
    busy: boolean; supported: boolean; canCancel: boolean; operationId: string; engineVersion: string; architecture: string;
    downloadedBytes: number; totalBytes: number; extractedBytes: number; downloadSize: number;
    error: string | null; probeOutput: string; checkedAt: number;
    probes: { name: string; detail: string; status: string }[];
  };
  packages: {
    busy: boolean; phase: string; message: string; action?: string; operationId?: string; error?: string | null;
    downloadedBytes?: number; totalBytes?: number; toolsReady: boolean; claudeReady: boolean;
    nodeVersion: string; claudeVersion: string; node?: ManagedPackage; claude?: ManagedPackage;
  };
  terminal: { id: string | null; running: boolean; stopping: boolean; kind: 'deviceShell' | 'linuxShell' | 'claude'; pid: number };
  permissions: { notifications: boolean; storage: boolean; battery: boolean };
  logs: { id: number; t: number; level: 'D' | 'I' | 'W' | 'E'; tag: string; msg: string }[];
}

interface NativeHost {
  postMessage(value: string): void;
  onmessage: ((event: MessageEvent<string>) => void) | null;
}
declare global {
  interface Window { AgentMHost?: NativeHost; agentMBack?: () => boolean }
}
export const isNative = typeof window !== 'undefined' && !!window.AgentMHost;
type Pending = { resolve(value: unknown): void; reject(error: Error): void; timer: ReturnType<typeof setTimeout> };
const pending = new Map<string, Pending>();

if (isNative) {
  window.AgentMHost!.onmessage = (event) => {
    let reply: { id: string; ok: boolean; value?: unknown; error?: { message: string } };
    try { reply = JSON.parse(event.data); } catch { return; }
    const request = pending.get(reply.id);
    if (!request) return;
    clearTimeout(request.timer);
    pending.delete(reply.id);
    if (reply.ok) request.resolve(reply.value);
    else request.reject(new Error(reply.error?.message ?? '原生操作未完成'));
  };
}

export function nativeRequest<T = { opened: boolean }>(method: string, params: Record<string, unknown> = {}): Promise<T> {
  if (!window.AgentMHost) return Promise.reject(new Error('当前为浏览器预览，未连接 Android'));
  if (pending.size >= 16) return Promise.reject(new Error('操作较多，请稍后重试'));
  const id = crypto.randomUUID();
  return new Promise<T>((resolve, reject) => {
    const timer = setTimeout(() => { pending.delete(id); reject(new Error('原生接口响应超时，请重试')); }, 15000);
    pending.set(id, { resolve: (value) => resolve(value as T), reject, timer });
    try { window.AgentMHost!.postMessage(JSON.stringify({ id, method, params })); }
    catch (error) { clearTimeout(timer); pending.delete(id); reject(error); }
  });
}

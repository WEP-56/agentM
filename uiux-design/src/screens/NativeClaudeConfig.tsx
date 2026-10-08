import { useEffect, useState } from 'react';
import { MdRefresh, MdOutlineTune } from 'react-icons/md';
import { useApp } from '@/store/useApp';
import { nativeRequest, NativeError } from '@/platform/native';
import { TabPage } from '@/components/md/Layout';
import { Button } from '@/components/md/Button';
import { TextField } from '@/components/md/Controls';
import { BottomSheet } from '@/components/md/Overlay';

type AuthMode = 'native' | 'apiKey' | 'authToken' | 'conflict';
interface Config {
  revision: string; exists: boolean; path: string; baseUrl: string; model: string; authMode: AuthMode;
  hasApiKey: boolean; hasAuthToken: boolean; canRestore: boolean; overrides: string[]; busy: boolean;
}
interface Preview {
  token: string | null; expiresAt: number; changed: boolean; action: 'save' | 'restore'; deletesFile: boolean;
  changes: { field: string; before: string; after: string; operation: string }[];
}
const labels: Record<string, string> = {
  ANTHROPIC_BASE_URL: 'API 端点', ANTHROPIC_MODEL: '默认模型', ANTHROPIC_API_KEY: 'API Key', ANTHROPIC_AUTH_TOKEN: 'Auth Token',
};

export function NativeClaudeConfig() {
  const native = useApp(s => s.native);
  const launch = useApp(s => s.launchAgent);
  const [config, setConfig] = useState<Config | null>(null);
  const [baseUrl, setBaseUrl] = useState('');
  const [model, setModel] = useState('');
  const [mode, setMode] = useState<AuthMode>('native');
  // Credentials exist only in this component's transient input state, never in Zustand/persist.
  const [secret, setSecret] = useState('');
  const [working, setWorking] = useState(true);
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState(false);
  const [preview, setPreview] = useState<Preview | null>(null);
  const [dirty, setDirty] = useState(false);
  const accept = (next: Config, preserve = false) => {
    setConfig(next); setError(''); setConflict(false); setPreview(null);
    if (!preserve) { setBaseUrl(next.baseUrl); setModel(next.model); setMode(next.authMode); setSecret(''); setDirty(false); }
  };
  const failed = (failure: unknown) => {
    setError(failure instanceof Error ? failure.message : '配置操作未完成');
    setConflict(failure instanceof NativeError && failure.code === 'CONFIG_CONFLICT');
  };
  useEffect(() => {
    let cancelled = false;
    void nativeRequest<Config>('readClaudeConfig').then(next => { if (!cancelled) accept(next); })
      .catch(failure => { if (!cancelled) failed(failure); }).finally(() => { if (!cancelled) setWorking(false); });
    return () => { cancelled = true; };
  }, []);
  const reload = async (preserve: boolean) => {
    setWorking(true);
    try { accept(await nativeRequest<Config>('readClaudeConfig'), preserve); }
    catch (failure) { failed(failure); }
    finally { setWorking(false); }
  };
  const prepare = async (restore: boolean) => {
    if (!config) return;
    setWorking(true); setError(''); setConflict(false);
    try {
      const next = restore ? await nativeRequest<Preview>('previewClaudeRestore', { revision: config.revision }) :
        await nativeRequest<Preview>('previewClaudeConfig', { revision: config.revision, baseUrl: mode === 'native' ? '' : baseUrl, model, authMode: mode, secretAction: secret ? 'replace' : 'keep', secret });
      if (!next.changed) useApp.getState().showSnack('配置没有变化');
      else setPreview(next);
    } catch (failure) { failed(failure); }
    finally { setWorking(false); }
  };
  const apply = async () => {
    if (!preview?.token) return;
    setWorking(true);
    try { accept(await nativeRequest<Config>('applyClaudeConfig', { token: preview.token })); useApp.getState().showSnack('配置已保存，下次启动 Claude Code 时生效'); }
    catch (failure) { failed(failure); setPreview(null); }
    finally { setWorking(false); }
  };
  const blocked = working || !native?.environment.linuxReady || native.packages.busy || native.terminal.running;
  const hasSelectedSecret = mode === 'apiKey' ? config?.hasApiKey : config?.hasAuthToken;
  const changed = () => { setDirty(true); setPreview(null); };
  return <TabPage title="配置">
    <div className="rounded-[28px] bg-surface-container-low p-5">
      <div className="flex items-center gap-3"><MdOutlineTune className="text-2xl text-primary" /><h2 className="type-title-large">Claude Code</h2></div>
      <p className="mt-2 type-body-medium text-on-surface-variant">{config?.path ?? '~/.claude/settings.json'} · {config ? config.exists ? '读取自设备' : '保存时创建' : '正在读取'}</p>
      <p className="mt-3 type-body-medium text-on-surface-variant">管理当前连接与默认模型。Claude 的登录、权限、MCP 和其他设置继续由它自己管理。</p>
      {error && <div role="alert" className="mt-4 rounded-xl bg-error-container p-4 type-body-medium text-on-error-container">
        <p>{error}</p>
        {conflict && <Button variant="text" disabled={working} onClick={() => void reload(true)}>重新读取并保留草稿</Button>}
      </div>}
      {config && <fieldset disabled={working} className="mt-6 min-w-0 space-y-5 border-0 p-0">
        <div>
          <label htmlFor="claude-auth-mode" className="type-label-large text-on-surface-variant">认证方式</label>
          <select id="claude-auth-mode" value={mode} onChange={e => { setMode(e.target.value as AuthMode); setSecret(''); changed(); }}
            className="mt-2 h-14 w-full rounded-[12px] border border-outline bg-surface-container-low px-3 type-body-large">
            {mode === 'conflict' && <option value="conflict" disabled>发现两类密钥，请选择要保留的方式</option>}
            <option value="native">使用 Claude 原生登录</option>
            <option value="apiKey">API Key</option>
            <option value="authToken">Auth Token（兼容服务）</option>
          </select>
        </div>
        {mode !== 'native' && <>
          <TextField label="API 端点" value={baseUrl} onChange={value => { setBaseUrl(value); changed(); }} mono
            supporting="留空使用官方端点；自定义服务需要兼容 Anthropic API。" />
          <TextField label={mode === 'authToken' ? 'Auth Token' : 'API Key'} value={secret} type="password" onChange={value => { setSecret(value); changed(); }}
            supporting={hasSelectedSecret ? '已有同类密钥，留空保留；输入新值将替换。密钥不回显。' : '请输入该认证方式的密钥。不会保存到浏览器存储。'} />
        </>}
        {mode === 'native' && <p className="type-body-small text-on-surface-variant">保存此方式会移除文件中的自定义端点与两类 API 密钥，不会修改原生登录资料。</p>}
        <TextField label="默认模型（可选）" value={model} onChange={value => { setModel(value); changed(); }} mono
          supporting="填写服务实际提供的模型 ID；留空使用 Claude 默认值。" />
      </fieldset>}
      {config?.overrides.length ? <div className="mt-4 rounded-xl bg-secondary-container p-4 type-body-small text-on-secondary-container">
        <p>发现可能影响实际生效配置的文件：</p>
        {config.overrides.map(path => <p key={path} className="mt-1 break-all font-mono">{path}</p>)}
        <p className="mt-2">本页仅编辑用户级设置；最终生效值还可能受项目、组织策略和启动参数影响。</p>
      </div> : null}
      {native?.terminal.running && <p className="mt-4 type-body-medium text-tertiary">请先关闭当前终端会话，再保存或恢复配置。</p>}
      <div className="mt-6 flex flex-wrap gap-2">
        <Button disabled={blocked || !config || mode === 'conflict'} onClick={() => void prepare(false)}>{working ? '处理中…' : '预览保存'}</Button>
        <Button variant="tonal" disabled={working} icon={<MdRefresh />} onClick={() => void reload(dirty)}>{dirty ? '刷新并保留草稿' : '重新读取'}</Button>
        {config?.canRestore && <Button variant="text" disabled={blocked} onClick={() => void prepare(true)}>恢复上次保存前</Button>}
        {native?.packages.claudeReady && <Button variant="text" disabled={working} onClick={() => void launch('claude')}>打开 Claude Code</Button>}
      </div>
      <p className="mt-4 type-body-small text-on-surface-variant">保存会写入 Claude 的私有配置文件，供其读取密钥；上一份配置以加密备份保存。其余 Agent 的配置管理尚未接入。</p>
    </div>
    <BottomSheet open={!!preview} onClose={() => { if (!working) setPreview(null); }} title={preview?.action === 'restore' ? '确认恢复配置' : '确认配置变更'}
      footer={<div className="flex justify-end gap-2"><Button variant="text" disabled={working} onClick={() => setPreview(null)}>取消</Button><Button disabled={blocked} onClick={() => void apply()}>{working ? '保存中…' : '确认应用'}</Button></div>}>
      <div className="px-6 pb-6">
        <p className="type-body-medium text-on-surface-variant">只应用下面的变更。预览有效期为 2 分钟，文件发生变化时会阻止保存。</p>
        {preview?.deletesFile && <p className="mt-3 type-body-medium">恢复后 settings.json 将回到原本不存在的状态。</p>}
        {preview?.changes.map(change => <div key={change.field} className="mt-4 rounded-xl bg-surface-container-high p-4">
          <p className="type-title-small">{labels[change.field]} · {change.operation}</p>
          <p className="mt-2 break-all type-body-small text-on-surface-variant">原值：{change.before}</p>
          <p className="mt-1 break-all type-body-medium">新值：{change.after}</p>
        </div>)}
      </div>
    </BottomSheet>
  </TabPage>;
}

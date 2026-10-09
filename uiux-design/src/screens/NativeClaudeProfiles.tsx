import { useEffect, useRef, useState } from 'react';
import { MdAdd, MdDeleteOutline, MdOutlineEdit, MdRefresh } from 'react-icons/md';
import { nativeRequest } from '@/platform/native';
import { useApp } from '@/store/useApp';
import { Button, IconButton } from '@/components/md/Button';
import { TextField } from '@/components/md/Controls';
import { BottomSheet, Dialog } from '@/components/md/Overlay';
import { ListGroup } from '@/components/md/Layout';

type Mode = 'native' | 'apiKey' | 'authToken';
export interface ClaudeProfile {
  id: string; revision: string; name: string; updatedAt: number; baseUrl: string; model: string;
  authMode: Mode; hasSecret: boolean; matchesCurrent: boolean;
}
interface Library { revision: string; profiles: ClaudeProfile[]; canCompare: boolean; limit: number }
interface Draft {
  libraryRevision: string; id?: string; name: string; baseUrl: string; model: string; authMode: Mode;
  secret: string; originalMode: Mode; hasSecret: boolean; capture: boolean;
}
export function NativeClaudeProfiles({ nativeRevision, blocked, onApply }: {
  nativeRevision?: string; blocked: boolean; onApply: (profile: ClaudeProfile) => void;
}) {
  const [library, setLibrary] = useState<Library | null>(null);
  const [loading, setLoading] = useState(true);
  const [sending, setSending] = useState(false);
  const [error, setError] = useState('');
  const [formError, setFormError] = useState('');
  const [draft, setDraft] = useState<Draft | null>(null);
  const [remove, setRemove] = useState<{ profile: ClaudeProfile; revision: string } | null>(null);
  const generation = useRef(0);
  const refresh = async () => {
    const request = ++generation.current;
    setLoading(true);
    try { const result = await nativeRequest<Library>('listClaudeProfiles'); if (request === generation.current) { setLibrary(result); setError(''); } }
    catch (failure) { if (request === generation.current) setError(failure instanceof Error ? failure.message : '模板读取失败'); }
    finally { if (request === generation.current) setLoading(false); }
  };
  useEffect(() => { void refresh(); return () => { generation.current++; }; }, [nativeRevision]);
  const open = (profile?: ClaudeProfile, capture = false) => {
    if (!library) return;
    setFormError('');
    setDraft({ libraryRevision: library.revision, id: profile?.id, name: profile?.name ?? '', baseUrl: profile?.baseUrl ?? '',
      model: profile?.model ?? '', authMode: profile?.authMode ?? 'apiKey', originalMode: profile?.authMode ?? 'apiKey',
      secret: '', hasSecret: profile?.hasSecret ?? false, capture });
  };
  const change = (patch: Partial<Draft>) => setDraft(previous => previous && ({ ...previous, ...patch }));
  const store = async () => {
    if (!draft) return;
    setSending(true); setFormError('');
    try {
      const result = await nativeRequest<Library>('saveClaudeProfile', {
        libraryRevision: draft.libraryRevision, id: draft.id ?? '', name: draft.name,
        captureCurrent: draft.capture, nativeRevision,
        baseUrl: draft.authMode === 'native' ? '' : draft.baseUrl, model: draft.model, authMode: draft.authMode,
        secretAction: draft.secret ? 'replace' : 'keep', secret: draft.secret,
      });
      generation.current++; setLibrary(result); setLoading(false); setError(''); setDraft(null);
      useApp.getState().showSnack('模板已保存，当前配置文件保持不变');
    } catch (failure) { setFormError(failure instanceof Error ? failure.message : '模板保存失败'); }
    finally { setSending(false); }
  };
  const deleteProfile = async () => {
    if (!remove) return;
    setSending(true); setFormError('');
    try {
      const result = await nativeRequest<Library>('deleteClaudeProfile', { libraryRevision: remove.revision, id: remove.profile.id });
      generation.current++; setLibrary(result); setLoading(false); setRemove(null); setError('');
      useApp.getState().showSnack('模板已删除，Claude 当前配置与会话保持不变');
    } catch (failure) { setFormError(failure instanceof Error ? failure.message : '删除失败'); }
    finally { setSending(false); }
  };
  return <ListGroup title="提供商模板" action={<IconButton aria-label="刷新模板" disabled={sending || loading} onClick={() => void refresh()}><MdRefresh /></IconButton>}>
    <div className="rounded-[24px] bg-surface-container-low p-5">
      <p className="type-body-medium text-on-surface-variant">保存多套 Claude 连接配置，需要时预览并应用。模板及密钥加密保存在设备上。</p>
      {error && <p role="alert" className="mt-3 break-words type-body-medium text-error">{error}</p>}
      {!loading && !error && !library?.profiles.length && <p className="mt-4 type-body-medium">还没有模板</p>}
      {loading && <p className="mt-3 type-body-small">正在读取模板…</p>}
      {library && !library.canCompare && <p className="mt-3 type-body-small text-tertiary">当前配置无法读取，暂不能判断模板是否与文件一致。</p>}
      <div className="mt-4 space-y-3">
        {library?.profiles.map(profile => <div key={profile.id} className="rounded-[20px] bg-surface-container p-4">
          <div className="flex items-start gap-2">
            <div className="min-w-0 flex-1">
              <h3 className="break-words type-title-medium">{profile.name}</h3>
              <p className="mt-1 break-all type-body-small text-on-surface-variant">{profile.authMode === 'native' ? '原生登录' : profile.baseUrl || '官方 API 端点'}</p>
              <p className="mt-1 break-all type-body-small text-on-surface-variant">{profile.model || '默认模型'}{profile.hasSecret ? ' · 已保存密钥' : ''}</p>
              {profile.matchesCurrent && <p className="mt-2 type-label-medium text-primary">与当前用户配置一致</p>}
            </div>
            <IconButton aria-label={`编辑 ${profile.name}`} disabled={sending || loading || !!error} onClick={() => open(profile)}><MdOutlineEdit /></IconButton>
            <IconButton aria-label={`删除 ${profile.name}`} disabled={sending || loading || !!error} onClick={() => { setFormError(''); setRemove({ profile, revision: library.revision }); }}><MdDeleteOutline /></IconButton>
          </div>
          <div className="mt-3 flex justify-end"><Button variant="tonal" disabled={blocked || sending || loading || !!error || !nativeRevision} onClick={() => onApply(profile)}>预览应用</Button></div>
        </div>)}
      </div>
      <div className="mt-4 flex flex-wrap gap-2">
        <Button variant="tonal" icon={<MdAdd />} disabled={!library || loading || sending || !!error} onClick={() => open()}>新增模板</Button>
        <Button variant="text" disabled={!library || !nativeRevision || loading || sending || !!error} onClick={() => open(undefined, true)}>保存当前文件为模板</Button>
      </div>
    </div>
    <BottomSheet open={!!draft} onClose={() => { if (!sending) setDraft(null); }} title={draft?.capture ? '保存当前文件为模板' : draft?.id ? '编辑提供商模板' : '新增提供商模板'}
      footer={<div className="flex justify-end gap-2"><Button variant="text" disabled={sending} onClick={() => setDraft(null)}>取消</Button><Button disabled={sending} onClick={() => void store()}>{sending ? '保存中…' : '保存模板'}</Button></div>}>
      {draft && <fieldset disabled={sending} className="min-w-0 space-y-5 border-0 p-0 pt-3">
        <p className="type-body-medium text-on-surface-variant">{draft.capture ? '复制设备文件中的四个连接与模型字段，不包含本页未保存的草稿。已有密钥在原生侧复制，不回显。' : '保存只更新模板库。应用到 Claude 前仍需预览并确认。'}</p>
        {formError && <p role="alert" className="rounded-xl bg-error-container p-3 type-body-medium text-on-error-container">{formError}</p>}
        <TextField label="模板名称" value={draft.name} onChange={name => change({ name })} />
        {!draft.capture && <>
          <div><label htmlFor="profile-auth-mode" className="type-label-large">认证方式</label>
            <select id="profile-auth-mode" value={draft.authMode} onChange={e => change({ authMode: e.target.value as Mode, secret: '' })} className="mt-2 h-14 w-full rounded-xl border border-outline bg-surface-container-low px-3">
              <option value="apiKey">API Key</option><option value="authToken">Auth Token（兼容服务）</option><option value="native">使用 Claude 原生登录</option>
            </select>
          </div>
          {draft.authMode !== 'native' && <>
            <TextField label="模板 API 端点" mono value={draft.baseUrl} onChange={baseUrl => change({ baseUrl })} supporting="留空使用官方端点，自定义服务需要兼容 Anthropic API。" />
            <TextField label={draft.authMode === 'apiKey' ? '模板 API Key' : '模板 Auth Token'} type="password" value={draft.secret} onChange={secret => change({ secret })}
              supporting={draft.hasSecret && draft.authMode === draft.originalMode ? '留空保留该模板自己的密钥；不会使用当前配置或其他模板的密钥。' : '请输入该模板的密钥。'} />
          </>}
          <TextField label="模板默认模型（可选）" mono value={draft.model} onChange={model => change({ model })} />
        </>}
      </fieldset>}
    </BottomSheet>
    <Dialog open={!!remove} onClose={() => { if (!sending) setRemove(null); }} title="删除提供商模板？"
      actions={<><Button variant="text" disabled={sending} onClick={() => setRemove(null)}>取消</Button><Button variant="dangerText" disabled={sending} onClick={() => void deleteProfile()}>删除模板</Button></>}>
      <p>删除“{remove?.profile.name}”的保存模板。Claude 当前配置、登录和会话不会随之删除。</p>
      {formError && <p role="alert" className="mt-3 text-error">{formError}</p>}
    </Dialog>
  </ListGroup>;
}

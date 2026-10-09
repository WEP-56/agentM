import { useCallback, useEffect, useRef, useState } from 'react';
import { MdAdd, MdMoreVert, MdCheck, MdContentCopy, MdDeleteOutline, MdEdit } from 'react-icons/md';
import { nativeRequest } from '@/platform/native';
import { providerTitle, providersChanged, type ProviderKind, type ProviderLibrary, type ProviderRow } from '@/platform/providers';
import { useApp } from '@/store/useApp';
import { Button, IconButton } from '@/components/md/Button';
import { Dialog, Menu } from '@/components/md/Overlay';

function ProviderCard({ row, busy, onSelect, onEdit, onCopy, onDelete }: { row: ProviderRow; busy: boolean; onSelect: () => void; onEdit: () => void; onCopy: () => void; onDelete: () => void }) {
  const anchor = useRef<HTMLButtonElement>(null);
  const [menu, setMenu] = useState(false);
  return <div className={`relative rounded-[22px] border ${row.active ? 'border-primary bg-primary-container/35' : 'border-outline-variant bg-surface-container-low'}`}>
    <button disabled={busy} onClick={onSelect} className="block w-full rounded-[22px] py-4 pl-4 pr-14 text-left disabled:opacity-60" aria-label={`切换到 ${row.name}`}>
      <div className="flex items-center gap-2"><span className="min-w-0 break-words type-title-medium">{row.name}</span>{row.active && <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-primary-container px-2 py-1 type-label-small text-on-primary-container"><MdCheck />当前</span>}</div>
      <p className="mt-1 break-all type-body-small text-primary">{row.baseUrl || (row.official ? 'Claude 原生登录' : '原生默认端点')}</p>
      {row.providerKey && <p className="mt-1 type-body-small text-on-surface-variant">{row.providerKey}</p>}
    </button>
    <div className="absolute right-1 top-2"><IconButton ref={anchor} aria-label={`${row.name} 菜单`} disabled={busy} onClick={() => setMenu(true)}><MdMoreVert /></IconButton></div>
    <Menu open={menu} onClose={() => setMenu(false)} anchor={anchor} items={[
      { label: '编辑', icon: <MdEdit />, onClick: onEdit }, { label: '复制', icon: <MdContentCopy />, onClick: onCopy },
      { label: '删除', icon: <MdDeleteOutline />, onClick: onDelete, danger: true, disabled: row.official },
    ]} />
  </div>;
}

export function NativeProviders({ kind, active = true }: { kind: ProviderKind; active?: boolean }) {
  const [library, setLibrary] = useState<ProviderLibrary | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [confirm, setConfirm] = useState<{ action: 'switch' | 'delete'; row: ProviderRow } | null>(null);
  const generation = useRef(0);
  const refresh = useCallback(async () => {
    const request = ++generation.current;
    try { const value = await nativeRequest<ProviderLibrary>('listProviders', { kind }); if (request === generation.current) { setLibrary(value); setError(''); } }
    catch (e) { if (request === generation.current) setError(e instanceof Error ? e.message : '读取提供商失败'); }
  }, [kind]);
  useEffect(() => { if (active) void refresh(); }, [active, refresh]);
  useEffect(() => {
    const reload = () => { if (active) void refresh(); };
    window.addEventListener('agentm:providers', reload); window.addEventListener('agentm:resume', reload);
    return () => { generation.current++; window.removeEventListener('agentm:providers', reload); window.removeEventListener('agentm:resume', reload); };
  }, [active, refresh]);
  const edit = (row?: ProviderRow) => useApp.getState().push({ name: 'providerEdit', agentId: kind, providerId: row?.id });
  const mutate = async (action: 'copy' | 'delete' | 'switch', row: ProviderRow) => {
    if (!library) return;
    setBusy(true); setError('');
    try {
      const next = await nativeRequest<ProviderLibrary>(`${action}Provider`, { kind, id: row.id, revision: library.revision, nativeRevision: library.nativeRevision });
      setLibrary(next); setConfirm(null);
      useApp.getState().showSnack(action === 'switch' ? `已切换到 ${row.name}，请重启 ${providerTitle(kind)} 后使用` : action === 'copy' ? '已复制提供商，可编辑副本' : `已删除提供商，请重启 ${providerTitle(kind)} 后使用`);
      providersChanged();
    } catch (e) { setError(e instanceof Error ? e.message : '提供商操作未完成'); setConfirm(null); }
    finally { setBusy(false); }
  };
  return <section className="pt-3">
    <header className="mb-2 flex items-center justify-between pl-1"><h2 className="type-title-large">提供商</h2><IconButton aria-label={`新增 ${providerTitle(kind)} 提供商`} disabled={busy || !library || !!error} onClick={() => edit()}><MdAdd /></IconButton></header>
    {error && <div role="alert" className="mb-3 rounded-xl bg-error-container p-3 type-body-small text-on-error-container"><p>{error}</p><Button variant="text" disabled={busy} onClick={() => void refresh()}>重新读取</Button></div>}
    {!library && !error && <p role="status" className="p-4 type-body-medium text-on-surface-variant">正在读取提供商…</p>}
    {library && !library.providers.length && <p className="rounded-[22px] bg-surface-container-low p-5 type-body-medium text-on-surface-variant">暂无提供商，点击右上角 + 添加。</p>}
    <div className="space-y-3">{library?.providers.map(row => <ProviderCard key={row.id} row={row} busy={busy} onEdit={() => edit(row)} onCopy={() => void mutate('copy', row)} onDelete={() => setConfirm({ action: 'delete', row })} onSelect={() => setConfirm({ action: 'switch', row })} />)}</div>
    {library && !library.hasCurrent && <p className="mt-3 px-1 type-body-small text-on-surface-variant">当前原生配置与列表中的已保存配置不同。选择提供商并确认后切换。</p>}
    <Dialog open={!!confirm} onClose={() => { if (!busy) setConfirm(null); }} title={confirm?.action === 'switch' ? '切换提供商？' : '删除提供商？'} actions={<><Button variant="text" disabled={busy} onClick={() => setConfirm(null)}>取消</Button><Button disabled={busy} onClick={() => confirm && void mutate(confirm.action, confirm.row)}>{busy ? '处理中…' : confirm?.action === 'switch' ? '确认切换' : '确认删除'}</Button></>}>
      {confirm?.action === 'switch' ? <p>切换到「{confirm.row.name}」。配置写入后，请重启 {providerTitle(kind)} 使其生效；当前会话不会自动重启。</p> : <p>删除「{confirm?.row.name}」？{kind === 'codex' ? '如果正在使用此提供商，将恢复 OpenAI Official。' : kind !== 'claude' ? `会移除 ${providerTitle(kind)} 中同标识的提供商，并清除指向它的默认模型选择。` : '如果正在使用此提供商，将恢复 Claude Official。'}请重启 {providerTitle(kind)} 后使用。登录信息和工作区会保留。</p>}
    </Dialog>
  </section>;
}

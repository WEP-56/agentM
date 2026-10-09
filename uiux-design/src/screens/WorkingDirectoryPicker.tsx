import { useEffect, useRef, useState } from 'react';
import { MdArrowUpward, MdChevronRight, MdCreateNewFolder, MdFolderOpen, MdRefresh } from 'react-icons/md';
import { Button, IconButton } from '@/components/md/Button';
import { BottomSheet } from '@/components/md/Overlay';
import { nativeRequest, type NativeSnapshot } from '@/platform/native';
import { useApp } from '@/store/useApp';

interface Listing { path: string; parent: string | null; roots: { name: string; path: string }[]; entries: string[]; offset: number; more: boolean }
type Selection = NonNullable<NativeSnapshot['workingDirectory']>;

/** Lives inside the existing Ubuntu panel; browsing never changes the saved launch directory. */
export function WorkingDirectoryPicker() {
  const native = useApp(s => s.native);
  const selected = native?.workingDirectory;
  const [open, setOpen] = useState(false);
  const [listing, setListing] = useState<Listing | null>(null);
  const [requested, setRequested] = useState('/workspace');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [newFolder, setNewFolder] = useState(false);
  const [name, setName] = useState('');
  const pending = useRef(false);
  const generation = useRef(0);
  const close = () => { if (!pending.current) { generation.current++; setOpen(false); setNewFolder(false); } };
  const currentClose = useRef(close); currentClose.current = close;
  useEffect(() => {
    if (!open) return;
    const previous = window.agentMBack;
    const back = () => { currentClose.current(); return true; };
    window.agentMBack = back;
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); e.stopImmediatePropagation(); back(); } };
    window.addEventListener('keydown', escape, true);
    return () => { if (window.agentMBack === back) window.agentMBack = previous; window.removeEventListener('keydown', escape, true); };
  }, [open]);
  useEffect(() => () => { generation.current++; }, []);
  const run = async (action: () => Promise<void>) => {
    if (pending.current) return;
    pending.current = true; setBusy(true); setError('');
    const request = generation.current;
    try { await action(); }
    catch (e) { if (request === generation.current) setError(e instanceof Error ? e.message : '目录操作失败'); }
    finally { pending.current = false; if (request === generation.current) setBusy(false); }
  };
  const browse = async (path: string, offset = 0) => run(async () => {
    const request = generation.current;
    setRequested(path); setNewFolder(false); setName('');
    const value = await nativeRequest<Listing>('listWorkingDirectories', { path, offset });
    if (request === generation.current) setListing(value);
  });
  const show = () => { setOpen(true); setListing(null); void browse(selected?.path || '/workspace'); };
  const select = () => run(async () => {
    if (!listing) return;
    const value = await nativeRequest<Selection>('setWorkingDirectory', { path: listing.path });
    useApp.setState(state => ({ native: state.native ? { ...state.native, workingDirectory: value } : state.native }));
    setOpen(false); setNewFolder(false);
    useApp.getState().showSnack('工作目录已保存，新启动的终端和 Agent 将使用此目录');
  });
  const create = () => run(async () => {
    if (!listing || !name.trim()) return;
    const request = generation.current;
    const value = await nativeRequest<Listing>('createWorkingDirectory', { path: listing.path, name });
    if (request === generation.current) { setListing(value); setRequested(value.path); setNewFolder(false); setName(''); }
  });
  const roots = listing?.roots ?? [{ name: '工作区', path: '/workspace' }, { name: 'Linux home', path: '/root' }];
  return <>
    <button type="button" aria-label="选择工作目录" disabled={!native?.environment.linuxReady} onClick={show}
      className="mt-4 flex w-full min-w-0 items-center gap-3 rounded-xl border border-on-primary-container/25 px-3 py-3 text-left text-on-primary-container disabled:opacity-50">
      <MdFolderOpen className="shrink-0 text-xl" /><span className="min-w-0 flex-1"><span className="block type-label-large">工作目录</span><span className="mt-1 block break-all font-mono text-xs">{selected?.path || '/workspace'}</span></span><MdChevronRight className="shrink-0 text-xl" />
    </button>
    {selected?.available === false && native?.environment.linuxReady && <p className="mt-2 type-body-small" role="alert">目录不可用，请重新选择后启动。</p>}
    <BottomSheet open={open} onClose={close} title="选择工作目录" label="选择工作目录" footer={<div className="flex flex-wrap justify-end gap-2"><Button variant="text" disabled={busy} onClick={close}>取消</Button><Button disabled={busy || !listing || !!error || newFolder} onClick={() => void select()}>{busy ? '处理中…' : '使用此目录'}</Button></div>}>
      <div className="space-y-3">
        <p className="type-body-small text-on-surface-variant">新启动的终端类 Agent 和 Linux 终端使用所选目录。已有会话保持原样，WebUI 内选择项目。</p>
        <div className="flex flex-wrap gap-2">{roots.map(root => <Button key={root.path} size="sm" variant={requested === root.path || requested.startsWith(root.path + '/') ? 'tonal' : 'text'} disabled={busy} onClick={() => void browse(root.path)}>{root.name}</Button>)}</div>
        <p aria-label="正在浏览的目录" className="break-all font-mono text-xs text-on-surface">{requested}</p>
        <div className="flex flex-wrap items-center gap-1">
          <Button size="sm" variant="text" icon={<MdArrowUpward />} disabled={busy || !listing?.parent} onClick={() => listing?.parent && void browse(listing.parent)}>上级目录</Button>
          <IconButton aria-label="刷新目录" disabled={busy} onClick={() => void browse(requested)}><MdRefresh /></IconButton>
          <IconButton aria-label="新建目录" disabled={busy || !listing || !!error} onClick={() => { setNewFolder(true); setName(''); }}><MdCreateNewFolder /></IconButton>
        </div>
        {error && <p role="alert" className="break-words type-body-small text-error">{error}</p>}
        {newFolder && <form onSubmit={e => { e.preventDefault(); void create(); }} className="space-y-2 rounded-xl bg-surface-container p-3">
          <label className="block type-label-large">新目录名称<input autoFocus disabled={busy} value={name} onChange={e => setName(e.target.value)} className="mt-2 w-full min-w-0 rounded-xl border border-outline bg-surface px-3 py-3 type-body-medium outline-none focus:border-primary" /></label>
          <div className="flex flex-wrap justify-end gap-2"><Button size="sm" type="button" variant="text" disabled={busy} onClick={() => setNewFolder(false)}>取消新建</Button><Button size="sm" type="submit" disabled={busy || !name.trim()}>创建目录</Button></div>
        </form>}
        {busy && <p role="status" className="type-body-small text-on-surface-variant">正在读取或保存目录…</p>}
        {listing && !error && <>
          {!listing.entries.length && !busy && <p className="py-3 type-body-small text-on-surface-variant">当前目录下没有子目录，可以使用此目录或新建目录。</p>}
          <ul className="space-y-1">{listing.entries.map(entry => <li key={entry}><button disabled={busy} onClick={() => void browse(`${listing.path}/${entry}`)} className="flex w-full min-w-0 items-center gap-3 rounded-xl bg-surface-container px-3 py-3 text-left text-on-surface disabled:opacity-50"><MdFolderOpen className="shrink-0 text-xl" /><span className="min-w-0 flex-1 break-all type-body-medium">{entry}</span><MdChevronRight className="shrink-0" /></button></li>)}</ul>
          {(listing.offset > 0 || listing.more) && <div className="flex justify-between gap-2"><Button variant="text" size="sm" disabled={busy || !listing.offset} onClick={() => void browse(listing.path, listing.offset - 50)}>上一页</Button><Button variant="text" size="sm" disabled={busy || !listing.more} onClick={() => void browse(listing.path, listing.offset + 50)}>下一页</Button></div>}
        </>}
      </div>
    </BottomSheet>
  </>;
}

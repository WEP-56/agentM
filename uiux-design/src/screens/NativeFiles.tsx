import { useCallback, useEffect, useRef, useState } from 'react';
import { MdArrowBack, MdCheckBox, MdCheckBoxOutlineBlank, MdClose, MdCreateNewFolder, MdDeleteOutline, MdDriveFileMoveOutline, MdDriveFileRenameOutline, MdFileDownload, MdFolder, MdInsertDriveFile, MdMoreVert, MdNoteAdd, MdRefresh, MdSearch, MdShare, MdUploadFile } from 'react-icons/md';
import { Button, IconButton } from '@/components/md/Button';
import { BottomSheet, Dialog } from '@/components/md/Overlay';
import { useApp } from '@/store/useApp';
import { isNative, nativeRequest } from '@/platform/native';

interface Entry { path: string; name: string; directory: boolean; link: boolean; bytes: number; modified: number; readable: boolean; writable: boolean }
interface Listing { path: string; parent: string | null; entries: Entry[]; offset: number; more: boolean; total: number; incomplete: boolean; query: string; writable: boolean }
interface Transfer { id?: string; phase: 'idle' | 'choosing' | 'running' | 'done' | 'failed' | 'cancelled'; message?: string }
interface Batch { completed: number; errors: string[] }
type Edit = { kind: 'file' | 'folder' | 'rename'; entry?: Entry };
let lastPath = '/workspace';
const size = (n: number) => n < 1024 ? `${n} B` : n < 1024 ** 2 ? `${(n / 1024).toFixed(1)} KiB` : n < 1024 ** 3 ? `${(n / 1024 ** 2).toFixed(1)} MiB` : `${(n / 1024 ** 3).toFixed(2)} GiB`;
const parent = (path: string) => path.substring(0, path.lastIndexOf('/')) || '/';
const inputClass = 'w-full min-w-0 rounded-xl border border-outline bg-surface px-4 py-3 text-on-surface outline-none focus:border-primary';

export function NativeFiles() {
  const ready = useApp(s => s.native?.environment.linuxReady);
  const [listing, setListing] = useState<Listing | null>(null);
  const [path, setPath] = useState(lastPath);
  const [query, setQuery] = useState('');
  const [search, setSearch] = useState('');
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [selection, setSelection] = useState<Entry[]>([]);
  const [selecting, setSelecting] = useState(false);
  const [menu, setMenu] = useState<Entry | null>(null);
  const [edit, setEdit] = useState<Edit | null>(null);
  const [name, setName] = useState('');
  const [removing, setRemoving] = useState<Entry[] | null>(null);
  const [moving, setMoving] = useState<Entry[] | null>(null);
  const [transfer, setTransfer] = useState<Transfer>({ phase: 'idle' });
  const [revision, setRevision] = useState(0);
  const requestId = useRef(0);
  const locked = useRef(false);
  const transferBusy = transfer.phase === 'choosing' || transfer.phase === 'running';
  const disabled = busy || transferBusy;
  const navigate = useCallback((next: string) => { setPath(next); lastPath = next; setOffset(0); setSearch(''); setQuery(''); setSelection([]); setSelecting(false); }, []);
  const refresh = () => { setSelection([]); setRevision(n => n + 1); };

  useEffect(() => {
    if (!isNative || !ready) return;
    const id = ++requestId.current;
    setLoading(true); setError('');
    void nativeRequest<Listing>('filesList', { path, offset, query }).then(result => {
      if (id === requestId.current) { setListing(result); lastPath = result.path; }
    }).catch(e => { if (id === requestId.current) { setListing(null); setError(e.message); } })
      .finally(() => { if (id === requestId.current) setLoading(false); });
    return () => { requestId.current++; };
  }, [path, offset, query, revision, ready]);

  useEffect(() => {
    if (!isNative) return;
    let active = true, fetching = false, seen = '';
    const poll = async () => {
      if (fetching) return;
      fetching = true;
      try {
        const result = await nativeRequest<Transfer>('filesTransferStatus');
        if (!active) return;
        setTransfer(result);
        const key = `${result.id}:${result.phase}`;
        if (key !== seen && ['done', 'failed'].includes(result.phase)) {
          setRevision(n => n + 1);
          if (result.phase === 'failed') setError(result.message ?? '传输失败');
        }
        seen = key;
      } catch { /* Foreground list and explicit actions report bridge errors. */ }
      finally { fetching = false; }
    };
    void poll();
    const interval = window.setInterval(() => { void poll(); }, 1000);
    return () => { active = false; clearInterval(interval); };
  }, []);

  useEffect(() => {
    const back = (event: Event) => {
      if (menu) setMenu(null);
      else if (edit && !busy) setEdit(null);
      else if (removing && !busy) setRemoving(null);
      else if (moving && !busy) setMoving(null);
      else if (selecting) { setSelecting(false); setSelection([]); }
      else if (query) { setQuery(''); setSearch(''); setOffset(0); }
      else if (path !== '/workspace') navigate(listing?.parent ?? '/workspace');
      else return;
      event.preventDefault();
    };
    window.addEventListener('agentm:files-back', back);
    return () => window.removeEventListener('agentm:files-back', back);
  }, [menu, edit, removing, moving, busy, selecting, query, path, listing, navigate]);

  const run = async (action: () => Promise<void>) => {
    if (locked.current || transferBusy) return;
    locked.current = true; setBusy(true); setError('');
    try { await action(); }
    catch (e) { setError(e instanceof Error ? e.message : '文件操作未完成'); }
    finally { locked.current = false; setBusy(false); }
  };
  const batchResult = (result: Batch) => {
    refresh(); setSelecting(false);
    if (result.errors.length) setError(`完成 ${result.completed} 项；${result.errors.join('；')}`);
    else useApp.getState().showSnack(`已完成 ${result.completed} 项`);
  };
  const toggle = (entry: Entry) => setSelection(items => items.some(item => item.path === entry.path) ? items.filter(item => item.path !== entry.path) : [...items, entry]);
  const beginEdit = (kind: Edit['kind'], entry?: Entry) => { setError(''); setMenu(null); setName(entry?.name ?? ''); setEdit({ kind, entry }); };
  const transmit = (kind: string, target: string) => { setMenu(null); void run(async () => {
    const result = await nativeRequest<Transfer>('filesTransfer', { kind, path: target });
    setTransfer(result);
    if (result.phase === 'failed') setError(result.message ?? '无法打开系统文件界面');
  }); };

  return <div className="flex h-full flex-col bg-surface">
    <header className="shrink-0 px-4 pb-3 pt-[calc(16px+var(--sat))]">
      <div className="flex items-center gap-2"><h1 className="min-w-0 flex-1 type-headline-medium">文件</h1>
        <Button variant="text" disabled={disabled || !listing} onClick={() => { setSelecting(!selecting); setSelection([]); }}>{selecting ? '取消多选' : '多选'}</Button>
        <IconButton aria-label="刷新文件" disabled={disabled || loading || !ready} onClick={refresh}><MdRefresh /></IconButton></div>
      <div className="mt-3 flex gap-2 overflow-x-auto">{[['/workspace', '工作区'], ['/root', '主目录'], ['/', 'Ubuntu /']].map(([target, label]) =>
        <Button key={target} size="sm" variant={path === target ? 'tonal' : 'text'} disabled={disabled || !ready} onClick={() => navigate(target)}>{label}</Button>)}</div>
    </header>
    {!isNative || !ready ? <div className="space-y-4 p-6 type-body-medium"><p>{!isNative ? '请在 Android 应用中访问真实 Ubuntu 文件。' : '准备好 Ubuntu 后，即可管理工作区和系统文件。'}</p>
      <Button onClick={() => useApp.getState().setTab('env')}>前往环境</Button></div> : <>
      <div className="shrink-0 space-y-3 px-4 pb-3">
        <div className="flex items-center gap-1 rounded-2xl bg-surface-container-low pr-3">
          <IconButton aria-label="上级目录" disabled={disabled || path === '/'} onClick={() => navigate(listing?.parent ?? parent(path))}><MdArrowBack /></IconButton>
          <p className="min-w-0 break-all font-mono text-xs" aria-label="当前路径">{listing?.path ?? path}</p>
        </div>
        <form className="flex gap-1" onSubmit={e => { e.preventDefault(); setQuery(search.trim()); setOffset(0); setSelection([]); }}>
          <input aria-label="搜索文件" placeholder="在当前目录及子目录搜索名称" className={`${inputClass} py-2`} value={search} maxLength={128} onChange={e => setSearch(e.target.value)} disabled={disabled} />
          <IconButton type="submit" aria-label="搜索" disabled={disabled || loading}><MdSearch /></IconButton>
          {query && <IconButton aria-label="清除搜索" onClick={() => { setQuery(''); setSearch(''); setOffset(0); }}><MdClose /></IconButton>}
        </form>
        {selecting ? <div className="flex flex-wrap items-center gap-1">
          <Button size="sm" variant="text" disabled={disabled || loading} onClick={() => setSelection(listing?.entries.filter(e => e.writable) ?? [])}>全选本页</Button>
          <span className="type-label-medium">已选 {selection.length} 项</span>
          <Button size="sm" variant="tonal" disabled={disabled || !selection.length} onClick={() => setMoving(selection)}>移动</Button>
          <Button size="sm" variant="dangerText" disabled={disabled || !selection.length} onClick={() => setRemoving(selection)}>删除</Button>
        </div> : <div className="flex flex-wrap gap-2">
          <Button variant="tonal" size="sm" disabled={disabled || loading || !listing?.writable} onClick={() => beginEdit('file')}><MdNoteAdd />新建文件</Button>
          <Button variant="tonal" size="sm" disabled={disabled || loading || !listing?.writable} onClick={() => beginEdit('folder')}><MdCreateNewFolder />新建文件夹</Button>
          <Button variant="text" size="sm" disabled={disabled || loading || !listing?.writable} onClick={() => transmit('upload', listing!.path)}><MdUploadFile />上传</Button>
        </div>}
      </div>
      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-4" aria-busy={loading || busy}>
        {error && <p role="alert" className="mb-3 break-words rounded-xl bg-error-container p-3 type-body-small text-on-error-container">{error}</p>}
        {transfer.phase !== 'idle' && <p role="status" className="mb-3 rounded-xl bg-secondary-container p-3 type-body-small text-on-secondary-container">{transfer.message}</p>}
        {busy && <p role="status" className="py-3 type-body-medium">正在处理文件，请稍候…</p>}
        {loading ? <p role="status" className="py-8 text-center type-body-medium">读取目录中…</p> : listing && <>
          <div className="mb-2 flex justify-between type-label-medium text-on-surface-variant"><span>{query ? `搜索结果 · ${listing.total} 项` : `${listing.total} 项`}</span><span>{!listing.writable && '只读目录'}</span></div>
          {listing.incomplete && <p className="mb-3 type-body-small text-on-surface-variant">部分目录不可访问或搜索达到上限，请缩小搜索范围。</p>}
          {!listing.entries.length && <div className="py-12 text-center text-on-surface-variant"><MdFolder className="mx-auto mb-3 text-5xl opacity-40" /><p>{query ? '没有找到匹配项目' : '目录为空'}</p></div>}
          <ul className="overflow-hidden rounded-2xl bg-surface-container-low">{listing.entries.map(entry => <li key={entry.path} className="flex items-center gap-1 border-b border-outline-variant/40 last:border-0">
            <button disabled={disabled || (selecting && !entry.writable)} onClick={() => selecting ? toggle(entry) : entry.directory ? navigate(entry.path) : setMenu(entry)}
              className="flex min-h-[72px] min-w-0 flex-1 items-center gap-3 p-3 text-left disabled:opacity-50">
              {selecting ? selection.some(e => e.path === entry.path) ? <MdCheckBox className="shrink-0 text-2xl text-primary" /> : <MdCheckBoxOutlineBlank className="shrink-0 text-2xl" />
                : entry.directory ? <MdFolder className="shrink-0 text-[28px] text-primary" /> : <MdInsertDriveFile className="shrink-0 text-[28px] text-on-surface-variant" />}
              <span className="min-w-0 flex-1"><span className="block break-all type-body-large">{entry.name}</span>
                <span className="mt-0.5 block break-all type-body-small text-on-surface-variant">{query ? parent(entry.path) : `${entry.link ? '符号链接 · ' : ''}${entry.directory ? '文件夹' : entry.readable ? size(entry.bytes) : '不可访问'}${entry.modified ? ` · ${new Date(entry.modified).toLocaleDateString()}` : ''}`}</span></span>
            </button>
            {!selecting && <IconButton aria-label={`${entry.name} 的操作`} disabled={disabled} onClick={() => setMenu(entry)}><MdMoreVert /></IconButton>}
          </li>)}</ul>
          {(offset > 0 || listing.more) && <div className="mt-3 flex items-center justify-between">
            <Button variant="text" disabled={disabled || !offset} onClick={() => { setOffset(Math.max(0, offset - 100)); setSelection([]); }}>上一页</Button>
            <span className="type-label-medium">第 {offset / 100 + 1} 页</span>
            <Button variant="text" disabled={disabled || !listing.more} onClick={() => { setOffset(offset + 100); setSelection([]); }}>下一页</Button>
          </div>}
        </>}
      </div>
    </>}
    <BottomSheet open={!!menu} onClose={() => setMenu(null)} title={<span className="block break-all">{menu?.name}</span>} label="文件操作">
      {menu && <><p className="mb-2 break-all font-mono text-xs">{menu.path}</p><p className="mb-4 type-body-small">{menu.directory ? '文件夹' : size(menu.bytes)}{menu.link ? ' · 符号链接' : ''}</p>
        <div className="flex flex-col gap-2">
          {menu.directory && menu.readable && <Button variant="tonal" onClick={() => { navigate(menu.path); setMenu(null); }}>打开文件夹</Button>}
          <Button variant="tonal" disabled={!menu.writable} onClick={() => beginEdit('rename', menu)}><MdDriveFileRenameOutline />重命名</Button>
          <Button variant="tonal" disabled={!menu.writable} onClick={() => { setMoving([menu]); setMenu(null); }}><MdDriveFileMoveOutline />移动到…</Button>
          <Button variant="tonal" disabled={!menu.readable} onClick={() => transmit('save', menu.path)}><MdFileDownload />另存为…</Button>
          <Button variant="tonal" disabled={!menu.readable} onClick={() => transmit('share', menu.path)}><MdShare />分享到…</Button>
          <Button variant="dangerText" disabled={!menu.writable} onClick={() => { setRemoving([menu]); setMenu(null); }}><MdDeleteOutline />删除</Button>
        </div>{menu.directory && <p className="mt-3 type-body-small text-on-surface-variant">文件夹保存或分享为 ZIP，不包含符号链接和虚拟设备目录。</p>}</>}
    </BottomSheet>
    <Dialog open={!!edit} onClose={() => { if (!busy) setEdit(null); }} title={edit?.kind === 'rename' ? '重命名' : edit?.kind === 'folder' ? '新建文件夹' : '新建文件'}
      actions={<><Button variant="text" disabled={busy} onClick={() => setEdit(null)}>取消</Button><Button disabled={busy || !name.trim()} onClick={() => void run(async () => {
        if (!edit) return;
        if (edit.kind === 'rename') batchResult(await nativeRequest<Batch>('filesMove', { paths: [edit.entry!.path], destination: parent(edit.entry!.path), name }));
        else { await nativeRequest('filesCreate', { path: listing!.path, name, directory: edit.kind === 'folder' }); refresh(); }
        setEdit(null);
      })}>{busy ? '处理中…' : '确定'}</Button></>}>
      <input aria-label="名称" className={inputClass} value={name} onChange={e => setName(e.target.value)} autoFocus />
      {error && <p role="alert" className="mt-2 text-error">{error}</p>}
    </Dialog>
    <Dialog open={!!removing} onClose={() => { if (!busy) setRemoving(null); }} title={`删除 ${removing?.length ?? 0} 项？`}
      actions={<><Button variant="text" disabled={busy} onClick={() => setRemoving(null)}>取消</Button><Button variant="dangerText" disabled={busy} onClick={() => void run(async () => {
        batchResult(await nativeRequest<Batch>('filesDelete', { paths: removing!.map(e => e.path) })); setRemoving(null);
      })}>{busy ? '删除中…' : '确认删除'}</Button></>}>
      <p>文件夹及其内容将被永久删除，无法撤销。符号链接只删除链接本身。</p>
      <ul className="mt-3 max-h-40 overflow-y-auto">{removing?.map(e => <li key={e.path} className="break-all py-1">{e.path}</li>)}</ul>
      {error && <p role="alert" className="mt-2 text-error">{error}</p>}
    </Dialog>
    <MovePicker open={!!moving} initialPath={listing?.path ?? path} busy={busy} error={error} onClose={() => { if (!busy) setMoving(null); }} onMove={destination => void run(async () => {
      batchResult(await nativeRequest<Batch>('filesMove', { paths: moving!.map(e => e.path), destination })); setMoving(null);
    })} />
  </div>;
}

function MovePicker({ open, initialPath, busy, error, onClose, onMove }: { open: boolean; initialPath: string; busy: boolean; error: string; onClose(): void; onMove(path: string): void }) {
  const [path, setPath] = useState(initialPath);
  const [listing, setListing] = useState<Listing | null>(null);
  const [offset, setOffset] = useState(0);
  const [loading, setLoading] = useState(false);
  const [failure, setFailure] = useState('');
  useEffect(() => { if (open) { setPath(initialPath); setOffset(0); } }, [open, initialPath]);
  useEffect(() => {
    if (!open) return;
    let active = true; setLoading(true); setFailure(''); setListing(null);
    void nativeRequest<Listing>('filesList', { path, offset }).then(result => { if (active) setListing(result); }).catch(e => { if (active) setFailure(e.message); }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [open, path, offset]);
  const navigate = (target: string) => { setPath(target); setOffset(0); };
  return <BottomSheet open={open} onClose={onClose} title="移动到文件夹" label="选择移动目标" footer={<div className="flex justify-end gap-2"><Button variant="text" disabled={busy} onClick={onClose}>取消</Button><Button disabled={busy || loading || !listing?.writable} onClick={() => onMove(listing!.path)}>{busy ? '移动中…' : '移动到此处'}</Button></div>}>
    <div className="mb-3 flex gap-1">{['/workspace', '/root', '/'].map(root => <Button key={root} size="sm" variant="text" disabled={busy} onClick={() => navigate(root)}>{root === '/' ? 'Ubuntu /' : root}</Button>)}</div>
    <div className="mb-3 flex items-center"><IconButton aria-label="目标的上级目录" disabled={busy || path === '/'} onClick={() => navigate(listing?.parent ?? parent(path))}><MdArrowBack /></IconButton><p className="break-all font-mono text-xs">{listing?.path ?? path}</p></div>
    {(failure || error) && <p role="alert" className="mb-3 text-error">{failure || error}</p>}
    {loading ? <p>读取目录中…</p> : <ul>{listing?.entries.filter(e => e.directory).map(entry => <li key={entry.path}><button disabled={busy} className="flex w-full items-center gap-3 rounded-xl p-3 text-left hover:bg-surface-container" onClick={() => navigate(entry.path)}><MdFolder className="shrink-0 text-2xl text-primary" /><span className="break-all">{entry.name}</span></button></li>)}</ul>}
    {!loading && listing && !listing.entries.some(e => e.directory) && <p className="py-3 type-body-small">此页没有子文件夹，可将项目移动到当前目录。</p>}
    {(offset > 0 || listing?.more) && <div className="mt-2 flex justify-between"><Button variant="text" disabled={busy || loading || offset === 0} onClick={() => setOffset(offset - 100)}>上一页</Button><Button variant="text" disabled={busy || loading || !listing?.more} onClick={() => setOffset(offset + 100)}>下一页</Button></div>}
  </BottomSheet>;
}

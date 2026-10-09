import { useRef, useState } from 'react';
import { Button } from '@/components/md/Button';
import { ListGroup } from '@/components/md/Layout';
import { nativeRequest } from '@/platform/native';

interface Root { id: string; name: string; hostPath: string; guestPath: string; bytes: number; entries: number; incomplete: boolean }
interface Usage { roots: Root[]; checkedAt: number }
interface Listing { root: string; path: string; offset: number; more: boolean; entries: { name: string; directory: boolean; link: boolean; bytes: number }[] }
const size = (bytes: number) => bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(2)} GiB` : bytes >= 1024 ** 2 ? `${(bytes / 1024 ** 2).toFixed(1)} MiB` : bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${bytes} B`;

export function NativeStorage() {
  const [usage, setUsage] = useState<Usage | null>(null);
  const [listing, setListing] = useState<Listing | null>(null);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [error, setError] = useState('');
  const run = async (action: () => Promise<void>) => {
    if (pending.current) return;
    pending.current = true; setBusy(true); setError('');
    try { await action(); } catch (e) { setError(e instanceof Error ? e.message : '读取失败'); }
    finally { pending.current = false; setBusy(false); }
  };
  const browse = (root: string, path = '', offset = 0) => run(async () => {
    setListing(await nativeRequest<Listing>('listStorage', { root, path, offset }));
  });
  const selected = usage?.roots.find(r => r.id === listing?.root);
  return <ListGroup title="存储与文件树"><div className="rounded-[24px] bg-surface-container-low p-5">
    <p className="type-body-medium text-on-surface-variant">只读查看应用私有目录。统计普通文件大小，不跟随符号链接；硬链接按目录项计数，结果不等同于磁盘实际分配空间。Ubuntu 中绑定的工作区、home 与受管软件单独统计。</p>
    <Button className="mt-3" variant="tonal" disabled={busy} onClick={() => void run(async () => setUsage(await nativeRequest<Usage>('storageUsage')))}>{busy ? '读取中…' : '统计存储占用'}</Button>
    {error && <p role="alert" className="mt-3 type-body-small text-error">{error}</p>}
    {usage && <><p className="mt-3 type-body-small text-on-surface-variant">{new Date(usage.checkedAt).toLocaleString()}</p><div className="mt-2 space-y-2">{usage.roots.map(root => <button key={root.id} disabled={busy} onClick={() => void browse(root.id)} className="block w-full rounded-xl bg-surface-container p-3 text-left">
      <p className="type-label-large">{root.name} · {root.incomplete ? '至少 ' : ''}{size(root.bytes)} →</p><p className="mt-1 break-all type-body-small">Linux：{root.guestPath}</p>
      {root.incomplete && <p className="type-body-small">达到扫描上限或部分目录无法读取</p>}
    </button>)}</div></>}
    {listing && <div className="mt-5 border-t border-outline-variant pt-4">
      <h3 className="type-title-medium">{selected?.name}</h3><p className="mt-2 break-all type-body-small">宿主根目录：{selected?.hostPath}</p><p className="mt-2 break-all font-mono text-xs">相对路径：/{listing.path}</p>
      <div className="my-2 flex gap-2"><Button variant="text" disabled={busy || !listing.path} onClick={() => void browse(listing.root, listing.path.split('/').slice(0, -1).join('/'))}>上级目录</Button><Button variant="text" disabled={busy} onClick={() => void browse(listing.root, listing.path)}>刷新</Button></div>
      {!listing.entries.length && <p className="type-body-small">目录为空或尚未创建</p>}
      <ul className="space-y-1">{listing.entries.map(entry => <li key={entry.name}><button disabled={busy || !entry.directory || entry.link} className="block w-full break-all rounded-lg bg-surface-container p-3 text-left type-body-small" onClick={() => void browse(listing.root, [listing.path, entry.name].filter(Boolean).join('/'))}>
        {entry.directory ? '目录' : entry.link ? '链接（不进入）' : '文件'} · {entry.name}{!entry.directory && !entry.link ? ` · ${size(entry.bytes)}` : ''}
      </button></li>)}</ul>
      <div className="mt-3 flex gap-2"><Button variant="text" disabled={busy || listing.offset === 0} onClick={() => void browse(listing.root, listing.path, Math.max(0, listing.offset - 100))}>上一页</Button><Button variant="text" disabled={busy || !listing.more || listing.offset >= 50000} onClick={() => void browse(listing.root, listing.path, listing.offset + 100)}>下一页</Button></div>
      <p className="type-body-small text-on-surface-variant">每页最多 100 项；浏览时目录变化可能影响分页，请刷新。</p>
    </div>}
  </div></ListGroup>;
}

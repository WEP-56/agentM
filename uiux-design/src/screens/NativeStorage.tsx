import { useRef, useState } from 'react';
import { Button } from '@/components/md/Button';
import { ListGroup } from '@/components/md/Layout';
import { nativeRequest } from '@/platform/native';

interface Root { id: string; name: string; guestPath: string; bytes: number; entries: number; incomplete: boolean }
interface Usage { roots: Root[]; checkedAt: number }
const size = (bytes: number) => bytes >= 1024 ** 3 ? `${(bytes / 1024 ** 3).toFixed(2)} GiB` : bytes >= 1024 ** 2 ? `${(bytes / 1024 ** 2).toFixed(1)} MiB` : bytes >= 1024 ? `${(bytes / 1024).toFixed(1)} KiB` : `${bytes} B`;

export function NativeStorage() {
  const [usage, setUsage] = useState<Usage | null>(null);
  const [busy, setBusy] = useState(false);
  const pending = useRef(false);
  const [error, setError] = useState('');
  const scan = async () => {
    if (pending.current) return;
    pending.current = true; setBusy(true); setError('');
    try { setUsage(await nativeRequest<Usage>('storageUsage')); }
    catch (e) { setError(e instanceof Error ? e.message : '统计失败'); }
    finally { pending.current = false; setBusy(false); }
  };
  return <ListGroup title="存储统计"><div className="rounded-[24px] bg-surface-container-low p-5">
    <p className="type-body-medium text-on-surface-variant">分别统计 Ubuntu、工作区、主目录、受管软件和应用缓存。按普通文件大小估算，不跟随符号链接，可能与磁盘实际占用不同。</p>
    <Button className="mt-3" variant="tonal" disabled={busy} onClick={() => void scan()}>{busy ? '统计中…' : '统计存储占用'}</Button>
    {error && <p role="alert" className="mt-3 type-body-small text-error">{error}</p>}
    {usage && <><p className="mt-3 type-body-small text-on-surface-variant">{new Date(usage.checkedAt).toLocaleString()}</p><div className="mt-2 space-y-2">{usage.roots.map(root => <div key={root.id} className="rounded-xl bg-surface-container p-3">
      <p className="type-label-large">{root.name} · {root.incomplete ? '至少 ' : ''}{size(root.bytes)}</p><p className="mt-1 break-all type-body-small">{root.guestPath}</p>
      {root.incomplete && <p className="type-body-small">达到扫描上限或部分目录无法读取</p>}
    </div>)}</div></>}
  </div></ListGroup>;
}

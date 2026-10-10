import { MdCheckCircle, MdOutlineBatteryChargingFull, MdOutlineFolder, MdOutlineNotifications } from 'react-icons/md';
import { Button } from './md/Button';
import { ListGroup, ListItem } from './md/Layout';
import { useApp, type Perm } from '@/store/useApp';
import { isNative } from '@/platform/native';

const permissions = [
  { key: 'notifications', title: '通知', description: '显示任务进度与后台运行状态', icon: <MdOutlineNotifications /> },
  { key: 'storage', title: '文件访问', description: isNative ? 'Ubuntu 文件可直接访问，导入导出时选择授权' : '在系统文件选择器中授权读写文件', icon: <MdOutlineFolder /> },
  { key: 'battery', title: '后台运行', description: '忽略电池优化，减少后台任务被中断', icon: <MdOutlineBatteryChargingFull /> },
] as const;

export function Permissions() {
  const granted = useApp(s => s.permissions);
  const request = (permission: Perm) => useApp.getState().setPermission(permission, true);
  return <ListGroup title="权限">
    {permissions.map(item => <ListItem key={item.key} icon={item.icon} headline={item.title}
      supporting={<span className="whitespace-normal">{item.description}</span>}
      trailing={granted[item.key] ? <span className="flex shrink-0 items-center gap-1 type-label-large text-success"><MdCheckCircle />{item.key === 'storage' && isNative ? '可访问' : '已授权'}</span>
        : <Button variant="tonal" size="sm" onClick={() => request(item.key)}>授权</Button>} />)}
  </ListGroup>;
}

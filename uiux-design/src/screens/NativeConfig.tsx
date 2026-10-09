import { managedAgents, type ManagedAgentId } from '@/platform/managedAgents';
import { useApp } from '@/store/useApp';
import { TabPage } from '@/components/md/Layout';
import { NativeAgentPackage } from './NativePackages';
import { NativeProviders } from './NativeProviders';
import { IconButton } from '@/components/md/Button';
import { MdAdd } from 'react-icons/md';

export function AgentTabs({ value, onChange }: { value: ManagedAgentId; onChange: (id: ManagedAgentId) => void }) {
  return <div role="tablist" aria-label="Agent" className="flex gap-2 overflow-x-auto pb-2">
    {managedAgents.map((agent, index) => <button key={agent.id} role="tab" id={`agent-tab-${agent.id}`} aria-controls={`agent-panel-${agent.id}`} aria-selected={value === agent.id} tabIndex={value === agent.id ? 0 : -1}
      className={`shrink-0 rounded-full px-4 py-3 type-label-large ${value === agent.id ? 'bg-secondary-container text-on-secondary-container' : 'bg-surface-container-low text-on-surface-variant'}`}
      onClick={() => onChange(agent.id)} onKeyDown={e => {
        const next = e.key === 'ArrowRight' ? (index + 1) % 5 : e.key === 'ArrowLeft' ? (index + 4) % 5 : e.key === 'Home' ? 0 : e.key === 'End' ? 4 : -1;
        if (next >= 0) { e.preventDefault(); onChange(managedAgents[next].id); document.getElementById(`agent-tab-${managedAgents[next].id}`)?.focus(); }
      }}>{agent.name}</button>)}
  </div>;
}

export function NativeConfig() {
  const id = useApp(s => s.configAgent);
  return <TabPage title="配置">
    <AgentTabs value={id} onChange={configAgent => useApp.setState({ configAgent })} />
    {managedAgents.map(agent => <div key={agent.id} hidden={agent.id !== id} role="tabpanel" id={`agent-panel-${agent.id}`} aria-labelledby={`agent-tab-${agent.id}`} className="space-y-5">
      <details className="mt-2 rounded-[24px] bg-surface-container-low"><summary className="cursor-pointer px-5 py-4 type-title-large">版本</summary><NativeAgentPackage id={agent.id} showTitle={false} /></details>
      {agent.id !== 'dsh' ? <NativeProviders kind={agent.id} active={agent.id === id} /> : <section className="pt-3">
        <header className="mb-2 flex items-center justify-between pl-1"><h2 className="type-title-large">提供商</h2><IconButton disabled aria-label={`新增 ${agent.name} 提供商（待接入）`}><MdAdd /></IconButton></header>
        <div className="rounded-[24px] bg-surface-container-low p-5">
        <p className="mt-2 type-body-medium text-on-surface-variant">请从首页进入 {agent.id === 'dsh' ? 'WebUI' : '终端'}，使用 {agent.name} 自带的登录和配置功能。更新和卸载受管程序会保留这些数据。</p>
      </div></section>}
    </div>)}
  </TabPage>;
}

import { managedAgents, type ManagedAgentId } from '@/platform/managedAgents';
import { useApp } from '@/store/useApp';
import { TabPage } from '@/components/md/Layout';
import { NativeAgentPackage } from './NativePackages';
import { NativeClaudeConfig } from './NativeClaudeConfig';

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
      <NativeAgentPackage id={agent.id} />
      {agent.id === 'claude' ? <NativeClaudeConfig /> : <div className="rounded-[24px] bg-surface-container-low p-5">
        <h3 className="type-title-medium">{agent.name} 配置</h3>
        <p className="mt-2 type-body-medium text-on-surface-variant">请从首页进入 {agent.id === 'dsh' ? 'WebUI' : agent.id === 'opencode' ? 'TUI 或 WebUI' : '终端'}，使用 {agent.name} 自带的登录和配置功能。更新和卸载受管程序会保留这些数据。</p>
      </div>}
    </div>)}
  </TabPage>;
}

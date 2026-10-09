import { useEffect, useMemo, useRef, useState } from 'react';
import { parse, stringify } from 'smol-toml';
import { MdAdd, MdDeleteOutline } from 'react-icons/md';
import { Button, IconButton } from '@/components/md/Button';
import { TopBar } from '@/components/md/Layout';
import { Dialog } from '@/components/md/Overlay';
import { Switch } from '@/components/md/Controls';
import { nativeRequest } from '@/platform/native';
import { providersChanged, type ProviderDraft } from '@/platform/providers';
import { codexImageDefault } from '@/platform/codexModels';
import { useApp } from '@/store/useApp';
import { Field, Select, Pairs, inputClass, object, string, type Json } from './ProviderControls';

const efforts = ['none', 'minimal', 'low', 'medium', 'high', 'xhigh', 'max', 'ultra'];
const decode = (text: string) => { try { const value = JSON.parse(text); return value && typeof value === 'object' && !Array.isArray(value) ? value as Json : null; } catch { return null; } };

export function CodexProviderEditor({ id }: { id?: string }) {
  const [draft, setDraft] = useState<ProviderDraft | null>(null);
  const [authText, setAuthText] = useState('{}');
  const [configText, setConfigText] = useState('');
  const [catalogText, setCatalogText] = useState('{"models":[]}');
  const [busy, setBusy] = useState(true);
  const [fetching, setFetching] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [discard, setDiscard] = useState(false);
  const [models, setModels] = useState<{ id: string; name: string }[]>([]);
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const [choose, setChoose] = useState(false);
  const generation = useRef(0);
  const parsed = useMemo(() => { try { return { root: parse(configText) as Json, valid: true }; } catch { return { root: {} as Json, valid: false }; } }, [configText]);
  const root = parsed.root;
  const auth = decode(authText);
  const catalog = decode(catalogText);
  const rows: Json[] = Array.isArray(catalog?.models) ? catalog.models.map(object) : [];
  const validCatalog = !!catalog && Array.isArray(catalog.models) && catalog.models.every((row: unknown) => row && typeof row === 'object' && !Array.isArray(row));
  const valid = parsed.valid && !!auth && validCatalog;
  const source = JSON.stringify({ auth, config: configText, modelCatalog: catalog });
  const latestSource = useRef(source); latestSource.current = source;
  const selected = string(root.model_provider);
  const provider = object(object(root.model_providers)[selected]);
  const secret = string(auth?.OPENAI_API_KEY) || string(provider.experimental_bearer_token) || string(root.experimental_bearer_token);
  const back = () => { if (busy) return; if (dirty) setDiscard(true); else useApp.getState().pop(); };
  const latestBack = useRef(back); latestBack.current = back;
  useEffect(() => {
    const previous = window.agentMBack;
    const handler = () => { latestBack.current(); return true; };
    window.agentMBack = handler;
    const escape = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); e.stopImmediatePropagation(); handler(); } };
    window.addEventListener('keydown', escape, true);
    return () => { generation.current++; if (window.agentMBack === handler) window.agentMBack = previous; window.removeEventListener('keydown', escape, true); };
  }, []);
  const read = async () => {
    const request = ++generation.current; setBusy(true); setError('');
    try {
      const value = await nativeRequest<ProviderDraft>('readProvider', { kind: 'codex', id: id ?? '' });
      if (request !== generation.current) return;
      const source = decode(value.source); if (!source) throw new Error('提供商源码格式无效');
      setDraft(value); setAuthText(JSON.stringify(source.auth ?? {}, null, 2)); setConfigText(string(source.config));
      setCatalogText(JSON.stringify(source.modelCatalog ?? { models: [] }, null, 2)); setDirty(false);
    } catch (e) { if (request === generation.current) setError(e instanceof Error ? e.message : '读取失败'); }
    finally { if (request === generation.current) setBusy(false); }
  };
  useEffect(() => { void read(); }, [id]);
  const toml = (edit: (value: Json) => void) => { if (!parsed.valid) return; const next = structuredClone(root); edit(next); setConfigText(stringify(next)); setDirty(true); };
  const top = (key: string, value: unknown) => toml(next => { if (value === '' || value == null) delete next[key]; else next[key] = value; });
  const connection = (edit: (value: Json) => void) => toml(next => {
    let key = string(next.model_provider);
    if (!key || ['openai', 'ollama', 'lmstudio'].includes(key)) key = 'custom';
    const route = { name: 'Custom', wire_api: 'responses', requires_openai_auth: false, ...object(object(next.model_providers)[key]) };
    edit(route); next.model_provider = key; next.model_providers = { ...object(next.model_providers), [key]: route };
    delete next.openai_base_url;
  });
  const setRows = (models: Json[]) => { setCatalogText(JSON.stringify({ ...catalog, models }, null, 2)); setDirty(true); };
  const setModel = (index: number, values: Json) => {
    const next = rows.map((row, i) => i === index ? { ...row, ...values } : row);
    for (const [key, value] of Object.entries(values)) if (value === undefined) delete next[index][key];
    setRows(next);
    if (values.model !== undefined && root.model === rows[index].model) top('model', values.model);
  };
  const addModels = (additions: Json[]) => {
    setRows([...rows, ...additions]);
    if (!root.model && additions[0]?.model) top('model', additions[0].model);
  };
  const fetchModels = async () => {
    if (!valid || fetching || draft?.official) return;
    setFetching(true); setError(''); const request = generation.current; const snapshot = source;
    try {
      const result = await nativeRequest<{ models: { id: string; name: string }[] }>('fetchProviderModels', { kind: 'codex', source });
      if (request !== generation.current) return;
      if (latestSource.current !== snapshot) throw new Error('配置已改变，请重新获取模型列表');
      setModels(result.models); setChosen(new Set()); setChoose(true);
    } catch (e) { if (request === generation.current) setError(e instanceof Error ? e.message : '查询模型失败'); }
    finally { if (request === generation.current) setFetching(false); }
  };
  const save = async () => {
    if (!draft || !valid) return;
    setBusy(true); setError('');
    try {
      await nativeRequest('saveProvider', { kind: 'codex', id: draft.id, name: draft.name, providerKey: '', source, revision: draft.revision });
      providersChanged(); useApp.getState().pop(); useApp.getState().showSnack('提供商已保存，点击列表中的提供商并确认后生效');
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败'); }
    finally { setBusy(false); }
  };
  return <div className="flex h-full flex-col bg-surface">
    <TopBar title={id ? '编辑提供商' : '新增提供商'} subtitle="Codex" onBack={back} />
    <main className="min-h-0 flex-1 overflow-y-auto px-5 pb-8">
      {error && <div role="alert" className="my-3 rounded-xl bg-error-container p-4 type-body-medium text-on-error-container">{error}{!draft && <Button variant="text" onClick={() => void read()}>重新读取</Button>}</div>}
      {!draft && !error && <p role="status" className="py-5">正在读取提供商…</p>}
      {draft && <>
        <fieldset disabled={busy || !valid} className="min-w-0 space-y-5 border-0 py-4">
          <Field label="名称" value={draft.name} disabled={draft.official} onChange={name => { setDraft({ ...draft, name }); setDirty(true); }} />
          {draft.official ? <p className="rounded-xl bg-primary-container p-4 type-body-medium text-on-primary-container">OpenAI Official 使用 Codex 原生登录。请从首页进入 Codex 完成登录，已有登录信息会保留。</p> : <>
            <p className="type-body-small text-on-surface-variant">接口格式：OpenAI Responses（原生）</p>
            <Field label="API Key" type="password" autoComplete="new-password" value={secret} onChange={value => {
              setAuthText(JSON.stringify({ ...auth, OPENAI_API_KEY: value }, null, 2));
              toml(next => { delete next.experimental_bearer_token; const route = object(object(next.model_providers)[selected]); if (Object.prototype.hasOwnProperty.call(route, 'experimental_bearer_token')) { if (value) route.experimental_bearer_token = value; else delete route.experimental_bearer_token; } });
            }} note="按 CC Switch 直连方式写入提供商的 bearer token，保留 Codex 官方登录。" />
            <Field label="请求地址" type="url" value={string(provider.base_url) || string(root.openai_base_url)} onChange={value => connection(next => { next.base_url = value; })} placeholder="https://api.example.com/v1" />
            <details className="rounded-[22px] bg-surface-container-low p-4"><summary className="cursor-pointer type-title-medium">请求头</summary><div className="mt-4"><Pairs title="请求头" value={object(provider.http_headers)} onChange={value => connection(next => { next.http_headers = value; })} note="使用 Codex 原生 http_headers，可添加 User-Agent 等请求头。" /></div></details>
          </>}
          <Field label="默认模型" value={string(root.model)} onChange={value => top('model', value)} note="留空使用 Codex 默认模型；配置了模型目录时，请从目录内选择。" />
          {rows.length > 0 && <Select label="选择默认模型" value={string(root.model)} onChange={value => top('model', value)}><option value="">原生默认</option>{rows.filter(row => string(row.model)).map((row, i) => <option key={i} value={row.model}>{string(row.displayName) || row.model}</option>)}</Select>}
          <Select label="推理档位" value={string(root.model_reasoning_effort)} onChange={value => top('model_reasoning_effort', value)}><option value="">原生默认</option>{efforts.map(e => <option key={e} value={e}>{e}</option>)}</Select>
          <details className="rounded-[22px] bg-surface-container-low p-4"><summary className="cursor-pointer type-title-medium">高级选项</summary><div className="mt-4 space-y-4">
            {!draft.official && <div className="flex items-center justify-between gap-3"><span className="type-body-medium">远程压缩</span><Switch label="远程压缩" checked={provider.name === 'OpenAI'} onChange={enabled => connection(next => { next.name = enabled ? 'OpenAI' : draft.name || 'Custom'; })} /></div>}
            {!draft.official && <p className="type-body-small text-on-surface-variant">按 CC Switch 设置原生提供商名称为 OpenAI；仅在服务支持 Responses 远程压缩时启用。</p>}
            <div className="flex items-center justify-between gap-3"><span className="type-body-medium">1M 上下文</span><Switch label="1M 上下文" checked={root.model_context_window === 1000000} onChange={enabled => toml(next => {
              if (enabled) { next.model_context_window = 1000000; if (next.model_auto_compact_token_limit == null) next.model_auto_compact_token_limit = 900000; }
              else { delete next.model_context_window; delete next.model_auto_compact_token_limit; }
            })} /></div>
            <Field label="上下文窗口" type="number" min={1} value={root.model_context_window == null ? '' : String(root.model_context_window)} onChange={value => top('model_context_window', value ? Number(value) : undefined)} />
            <Field label="自动压缩阈值" type="number" min={1} value={root.model_auto_compact_token_limit == null ? '' : String(root.model_auto_compact_token_limit)} onChange={value => top('model_auto_compact_token_limit', value ? Number(value) : undefined)} />
            <Field label="审查模型" value={string(root.review_model)} onChange={value => top('review_model', value)} />
            <Select label="计划模式推理档位" value={string(root.plan_mode_reasoning_effort)} onChange={value => top('plan_mode_reasoning_effort', value)}><option value="">原生默认</option>{efforts.map(e => <option key={e} value={e}>{e}</option>)}</Select>
            <div className="flex items-center justify-between gap-3"><span className="type-body-medium">禁用响应存储</span><Switch label="禁用响应存储" checked={root.disable_response_storage === true} onChange={value => top('disable_response_storage', value)} /></div>
          </div></details>
          {!draft.official && <section className="space-y-4"><div className="flex flex-wrap items-center justify-between gap-2"><h3 className="type-title-large">模型目录</h3><div className="flex flex-wrap gap-1">
            <Button variant="tonal" size="sm" disabled={fetching} onClick={() => void fetchModels()}>{fetching ? '获取中…' : '获取模型列表'}</Button><Button variant="text" size="sm" icon={<MdAdd />} onClick={() => addModels([{ model: '', displayName: '' }])}>添加模型</Button>
          </div></div><p className="type-body-small text-on-surface-variant">用于 Codex 的模型选择菜单。可声明名称、上下文、输入与推理能力；不添加时保留原生模型发现。命中 GPT 的条目按 CC Switch 保留 Codex 官方模型定义，全局上下文可另行设置。</p>
            {rows.map((row, index) => <section key={index} className="space-y-4 rounded-[22px] bg-surface-container-low p-4">
              <div className="flex items-center justify-between"><h4 className="type-title-medium">模型 {index + 1}</h4><IconButton aria-label={`删除模型 ${index + 1}`} onClick={() => { const next = rows.filter((_, i) => i !== index); setRows(next); if (root.model === row.model) top('model', next[0]?.model); }}><MdDeleteOutline /></IconButton></div>
              <Field label={`模型 ID ${index + 1}`} value={string(row.model)} onChange={value => setModel(index, { model: value })} />
              <Field label={`显示名称 ${index + 1}`} value={string(row.displayName)} onChange={value => setModel(index, { displayName: value })} />
              <Field label={`上下文长度 ${index + 1}`} type="number" min={1} value={row.contextWindow == null ? '' : String(row.contextWindow)} onChange={value => setModel(index, { contextWindow: value ? Number(value) : undefined })} note="留空采用原生模板或全局上下文设置。" />
              <div className="flex items-center justify-between gap-3"><span className="type-body-medium">支持图片输入</span><Switch label={`模型 ${index + 1} 支持图片输入`} checked={Array.isArray(row.inputModalities) && row.inputModalities.length ? row.inputModalities.includes('image') : codexImageDefault(string(row.model))} onChange={value => setModel(index, { inputModalities: value ? ['text', 'image'] : ['text'] })} /></div>
              <div className="flex items-center justify-between gap-3"><span className="type-body-medium">并行工具调用</span><Switch label={`模型 ${index + 1} 并行工具调用`} checked={row.supportsParallelToolCalls === true} onChange={value => setModel(index, { supportsParallelToolCalls: value })} /></div>
              <fieldset className="space-y-2"><legend className="type-label-large">支持的推理档位</legend><div className="flex flex-wrap gap-3">{efforts.map(e => <label key={e} className="inline-flex items-center gap-2 type-body-small"><input aria-label={`模型 ${index + 1} 支持 ${e}`} type="checkbox" checked={Array.isArray(row.reasoningLevels) && row.reasoningLevels.includes(e)} onChange={event => {
                const previous: string[] = Array.isArray(row.reasoningLevels) ? row.reasoningLevels : [];
                const selected: string[] = event.target.checked ? [...previous, e] : previous.filter((v: string) => v !== e);
                const levels = efforts.filter(v => selected.includes(v));
                setModel(index, { reasoningLevels: levels.length ? levels : undefined, defaultReasoningLevel: levels.includes(row.defaultReasoningLevel) ? row.defaultReasoningLevel : undefined });
              }} />{e}</label>)}</div></fieldset>
              {Array.isArray(row.reasoningLevels) && row.reasoningLevels.length > 0 && <Select label={`默认推理档位 ${index + 1}`} value={string(row.defaultReasoningLevel)} onChange={value => setModel(index, { defaultReasoningLevel: value || undefined })}><option value="">自动</option>{row.reasoningLevels.map((e: unknown, i: number) => typeof e === 'string' && <option key={i} value={e}>{e}</option>)}</Select>}
            </section>)}
          </section>}
        </fieldset>
        <details className="rounded-[22px] bg-surface-container-low p-4"><summary className="cursor-pointer type-title-medium">配置源码</summary><div className="mt-4 space-y-4">
          <p className="type-body-small text-on-surface-variant">采用 CC Switch 的 Auth JSON、config.toml 和模型目录格式。保存更新提供商库，确认切换后生效；MCP、插件、项目等全局设置不随提供商源码切换。</p>
          {!draft.official && <label className="block"><span className="type-label-large">Auth JSON</span><textarea aria-label="Auth JSON" rows={4} disabled={busy} className={`${inputClass} font-mono text-xs`} value={authText} onChange={e => { setAuthText(e.target.value); setDirty(true); }} /></label>}
          {!auth && <p role="alert" className="type-body-small text-error">Auth JSON 必须是有效 JSON 对象</p>}
          <label className="block"><span className="type-label-large">config.toml</span><textarea aria-label="config.toml" rows={12} disabled={busy} className={`${inputClass} font-mono text-xs`} value={configText} onChange={e => { setConfigText(e.target.value); setDirty(true); }} /></label>
          {!parsed.valid && <p role="alert" className="type-body-small text-error">TOML 格式无效，请修正后保存</p>}
          {!draft.official && <label className="block"><span className="type-label-large">模型目录 JSON</span><textarea aria-label="模型目录 JSON" rows={8} disabled={busy} className={`${inputClass} font-mono text-xs`} value={catalogText} onChange={e => { setCatalogText(e.target.value); setDirty(true); }} /></label>}
          {!validCatalog && <p role="alert" className="type-body-small text-error">模型目录需为含 models 对象数组的 JSON 对象</p>}
        </div></details>
      </>}
    </main>
    <footer className="flex shrink-0 justify-end gap-2 border-t border-outline-variant px-5 pb-[calc(16px+var(--sab))] pt-3"><Button variant="text" disabled={busy} onClick={back}>取消</Button><Button disabled={busy || fetching || !draft || !dirty || !valid} onClick={() => void save()}>{busy ? '处理中…' : '保存提供商'}</Button></footer>
    <Dialog open={discard} onClose={() => setDiscard(false)} title="放弃未保存的修改？" actions={<><Button variant="text" onClick={() => setDiscard(false)}>继续编辑</Button><Button onClick={() => useApp.getState().pop()}>放弃修改</Button></>}><p>离开后，本页未保存的修改会丢失。</p></Dialog>
    <Dialog open={choose} onClose={() => setChoose(false)} title="选择要添加的模型" actions={<><Button variant="text" onClick={() => setChoose(false)}>取消</Button><Button disabled={!chosen.size} onClick={() => { addModels(models.filter(m => chosen.has(m.id) && !rows.some(row => row.model === m.id)).map(m => ({ model: m.id, displayName: m.name }))); setChoose(false); }}>添加所选模型</Button></>}>
      <div className="max-h-72 space-y-3 overflow-y-auto">{models.map(m => <label key={m.id} className="flex items-start gap-3 break-all"><input type="checkbox" checked={chosen.has(m.id)} onChange={e => setChosen(previous => { const next = new Set(previous); if (e.target.checked) next.add(m.id); else next.delete(m.id); return next; })} /><span>{m.name}<span className="block type-body-small">{m.id}</span></span></label>)}</div>
    </Dialog>
  </div>;
}

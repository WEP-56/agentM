import { useEffect, useMemo, useRef, useState } from 'react';
import { MdAdd, MdDeleteOutline, MdOutlineFileDownload } from 'react-icons/md';
import { Button, IconButton } from '@/components/md/Button';
import { TopBar } from '@/components/md/Layout';
import { Dialog } from '@/components/md/Overlay';
import { Switch } from '@/components/md/Controls';
import { nativeRequest } from '@/platform/native';
import { CLAUDE_MODELS, OPENCODE_SDKS, PI_APIS, providersChanged, providerTitle, type ProviderDraft, type ProviderKind } from '@/platform/providers';
import { useApp } from '@/store/useApp';
import { Field, Select, Pairs, inputClass, object, string, type Json } from './ProviderControls';
import { CodexProviderEditor } from './CodexProviderEditor';

const blankModel = (id = '', name = id) => ({ id, name, reasoning: false, input: ['text'], contextWindow: 128000, maxTokens: 16384 });
const blankOpenCodeModel = (name = '') => ({ name, limit: { context: 128000, output: 16384 } });
const SDK_FIELDS = ['apiKey', 'baseURL', 'headers'];
const MODEL_FIELDS = ['name', 'limit', 'reasoning', 'modalities'];
const except = (value: Json, keys: readonly string[]) => Object.fromEntries(Object.entries(value).filter(([key]) => !keys.includes(key)));
function OpenCodeFields({ config, change, fetching, fetchModels }: { config: Json; change: (edit: (value: Json) => void) => void; fetching: boolean; fetchModels: () => void }) {
  const options = object(config.options);
  const rows = Object.entries(object(config.models)).map(([id, value]) => [id, object(value)] as const);
  const modelChange = (index: number, edit: (value: Json) => void) => change(next => { edit(next.models[rows[index][0]]); });
  return <>
    <details className="rounded-[22px] bg-surface-container-low p-4"><summary className="cursor-pointer type-title-medium">请求头与 SDK 选项</summary><div className="mt-4 space-y-5">
      <Pairs title="请求头" value={object(options.headers)} onChange={headers => change(next => { next.options = { ...object(next.options), headers }; })} />
      <Pairs title="额外 SDK 选项" typed reserved={SDK_FIELDS} value={except(options, SDK_FIELDS)} onChange={extra => change(next => { next.options = { ...Object.fromEntries(Object.entries(options).filter(([key]) => SDK_FIELDS.includes(key))), ...extra }; })} note="值支持布尔值、数字、JSON 对象或字符串，例如 timeout、setCacheKey。按所选 SDK 支持的 options 保存。" />
    </div></details>
    <section className="space-y-4"><div className="flex flex-wrap items-center justify-between gap-2"><h3 className="type-title-large">模型配置</h3><div className="flex flex-wrap gap-1">
      <Button variant="tonal" size="sm" disabled={fetching} onClick={fetchModels}>{fetching ? '获取中…' : '获取模型列表'}</Button>
      <Button variant="text" size="sm" icon={<MdAdd />} disabled={Object.prototype.hasOwnProperty.call(object(config.models), '')} onClick={() => change(next => { next.models = { ...object(next.models), '': blankOpenCodeModel() }; })}>添加模型</Button>
    </div></div><p className="type-body-small text-on-surface-variant">切换时使用列表中的第一个模型。能力与长度请按服务实际支持情况填写。</p>
      {rows.map(([id, model], index) => <section key={index} aria-label={`模型 ${index + 1}`} className="space-y-4 rounded-[22px] bg-surface-container-low p-4">
        <div className="flex items-center justify-between"><h4 className="type-title-medium">模型 {index + 1}</h4><IconButton aria-label={`删除模型 ${index + 1}`} onClick={() => change(next => { next.models = Object.fromEntries(rows.filter((_, i) => i !== index)); })}><MdDeleteOutline /></IconButton></div>
        <Field label={`模型 ID ${index + 1}`} value={id} onChange={value => {
          if (rows.some(([other], i) => i !== index && value === other)) { useApp.getState().showSnack('模型 ID 已存在'); return; }
          change(next => { next.models = Object.fromEntries(rows.map((row, i) => i === index ? [value, row[1]] : row)); });
        }} />
        <Field label={`显示名称 ${index + 1}`} value={string(model.name)} onChange={value => modelChange(index, next => { next.name = value; })} />
        <div className="flex items-center justify-between gap-3"><span className="type-body-medium">支持思考</span><Switch label={`模型 ${index + 1} 支持思考`} checked={model.reasoning === true} onChange={value => modelChange(index, next => { next.reasoning = value; })} /></div>
        <div className="flex items-center justify-between gap-3"><span className="type-body-medium">支持图片输入</span><Switch label={`模型 ${index + 1} 支持图片输入`} checked={Array.isArray(model.modalities?.input) && model.modalities.input.includes('image')} onChange={value => modelChange(index, next => {
          const modalities = object(next.modalities); const input: string[] = Array.isArray(modalities.input) ? modalities.input : ['text'];
          next.modalities = { ...modalities, input: value ? [...new Set([...input, 'image'])] : input.filter(item => item !== 'image') };
        })} /></div>
        <Field label={`上下文长度 ${index + 1}`} type="number" min={1} step={1} value={model.limit?.context == null ? '' : String(model.limit.context)} onChange={value => modelChange(index, next => { next.limit = { ...object(next.limit), context: value ? Number(value) : null }; })} />
        <Field label={`最大输出 Token 数 ${index + 1}`} type="number" min={1} step={1} value={model.limit?.output == null ? '' : String(model.limit.output)} onChange={value => modelChange(index, next => { next.limit = { ...object(next.limit), output: value ? Number(value) : null }; })} />
        <details><summary className="cursor-pointer type-title-medium">模型属性定义</summary><div className="mt-4"><Pairs title={`模型属性 ${index + 1}`} typed reserved={MODEL_FIELDS} value={except(model, MODEL_FIELDS)} onChange={extra => modelChange(index, next => {
          for (const key of Object.keys(next)) if (!MODEL_FIELDS.includes(key)) delete next[key];
          for (const [key, value] of Object.entries(extra)) Object.defineProperty(next, key, { value, enumerable: true, writable: true, configurable: true });
        })} note="支持原生属性，例如 variants、cost、options、tool_call、provider，以及实际请求模型 id。对象和数组请填写 JSON；名称、能力和长度与表单同步，其余字段也可在源码中编辑。" /></div></details>
      </section>)}
    </section>
  </>;
}

export function ProviderEditor({ kind, id }: { kind: ProviderKind; id?: string }) {
  return kind === 'codex' ? <CodexProviderEditor id={id} /> : <StandardProviderEditor kind={kind} id={id} />;
}

function StandardProviderEditor({ kind, id }: { kind: Exclude<ProviderKind, 'codex'>; id?: string }) {
  const [draft, setDraft] = useState<ProviderDraft | null>(null);
  const [busy, setBusy] = useState(true);
  const [fetching, setFetching] = useState(false);
  const [error, setError] = useState('');
  const [dirty, setDirty] = useState(false);
  const [discard, setDiscard] = useState(false);
  const [models, setModels] = useState<{ id: string; name: string }[]>([]);
  const [chooseModels, setChooseModels] = useState(false);
  const [chosen, setChosen] = useState<Set<string>>(new Set());
  const requestGeneration = useRef(0);
  const latestDraft = useRef(draft); latestDraft.current = draft;
  const parsed = useMemo(() => { try { const value = JSON.parse(draft?.source ?? '{}'); if (!value || typeof value !== 'object' || Array.isArray(value)) throw Error(); return { config: value as Json, valid: true }; } catch { return { config: {} as Json, valid: false }; } }, [draft?.source]);
  const config = parsed.config;
  const connection = kind === 'opencode' ? object(config.options) : config;
  const env = object(config.env);
  const modelRows: Json[] = Array.isArray(config.models) ? config.models.map(object) : [];
  const authField = Object.prototype.hasOwnProperty.call(env, 'ANTHROPIC_API_KEY') ? 'ANTHROPIC_API_KEY' : 'ANTHROPIC_AUTH_TOKEN';
  const back = () => { if (busy) return; if (dirty) setDiscard(true); else useApp.getState().pop(); };
  const latestBack = useRef(back); latestBack.current = back;
  useEffect(() => {
    const previous = window.agentMBack;
    const handle = () => { latestBack.current(); return true; };
    window.agentMBack = handle;
    const key = (e: KeyboardEvent) => { if (e.key === 'Escape') { e.preventDefault(); e.stopImmediatePropagation(); handle(); } };
    window.addEventListener('keydown', key, true);
    return () => { if (window.agentMBack === handle) window.agentMBack = previous; window.removeEventListener('keydown', key, true); requestGeneration.current++; };
  }, []);
  const read = async () => {
    setBusy(true); setError('');
    const request = ++requestGeneration.current;
    try { const value = await nativeRequest<ProviderDraft>('readProvider', { kind, id: id ?? '' }); if (request === requestGeneration.current) { setDraft(value); setDirty(false); } }
    catch (e) { if (request === requestGeneration.current) setError(e instanceof Error ? e.message : '读取失败'); }
    finally { if (request === requestGeneration.current) setBusy(false); }
  };
  useEffect(() => { void read(); }, [kind, id]);
  const patch = (values: Partial<ProviderDraft>) => { setDraft(previous => previous && ({ ...previous, ...values })); setDirty(true); };
  const change = (edit: (value: Json) => void) => { if (!parsed.valid) return; const next = structuredClone(config); edit(next); patch({ source: JSON.stringify(next, null, 2) }); };
  const setEnv = (key: string, value: string) => change(next => { next.env = object(next.env); if (value) next.env[key] = value; else delete next.env[key]; });
  const setRoot = (key: string, value: unknown) => change(next => { if (kind === 'opencode' && key === 'npm' && value === '') delete next[key]; else next[key] = value; });
  const setConnection = (key: string, value: unknown) => kind === 'opencode' ? change(next => {
    next.options = { ...object(next.options) };
    if (value === '') delete next.options[key]; else next.options[key] = value;
  }) : setRoot(key, value);
  const setModel = (index: number, values: Json) => change(next => { next.models[index] = { ...next.models[index], ...values }; });
  const fetchModels = async () => {
    if (!draft || fetching) return;
    setFetching(true); setError('');
    const source = draft.source;
    const request = requestGeneration.current;
    try {
      const result = await nativeRequest<{ models: { id: string; name: string }[] }>('fetchProviderModels', { kind, source });
      if (request !== requestGeneration.current) return;
      if (latestDraft.current?.source !== source) throw new Error('配置已改变，请重新获取模型列表');
      setModels(result.models);
      if (kind !== 'claude') { setChosen(new Set()); setChooseModels(true); }
      else useApp.getState().showSnack(`已获取 ${result.models.length} 个模型，可在各角色下选择`);
    } catch (e) { if (request === requestGeneration.current) setError(e instanceof Error ? e.message : '获取模型失败'); }
    finally { if (request === requestGeneration.current) setFetching(false); }
  };
  const save = async () => {
    if (!draft || !parsed.valid) return;
    setBusy(true); setError('');
    try {
      await nativeRequest('saveProvider', { kind, id: draft.id, name: draft.name, providerKey: draft.providerKey, source: draft.source, revision: draft.revision });
      providersChanged(); useApp.getState().pop();
      useApp.getState().showSnack('提供商已保存，点击列表中的提供商并确认后生效');
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败'); }
    finally { setBusy(false); }
  };
  const customHeaders = string(env.ANTHROPIC_CUSTOM_HEADERS);
  const ua = customHeaders.split('\n').find(line => /^user-agent\s*:/i.test(line))?.split(':').slice(1).join(':').trim() ?? '';
  return <div className="flex h-full flex-col bg-surface">
    <TopBar title={id ? '编辑提供商' : '新增提供商'} subtitle={providerTitle(kind)} onBack={back} />
    <main className="min-h-0 flex-1 overflow-y-auto px-5 pb-8">
      {error && <div role="alert" className="my-3 rounded-xl bg-error-container p-4 type-body-medium text-on-error-container"><p>{error}</p>{!draft && <Button variant="text" onClick={() => void read()}>重新读取</Button>}</div>}
      {!draft && !error && <p role="status" className="py-5">正在读取提供商…</p>}
      {draft && <>
        <fieldset disabled={busy || !parsed.valid} className="min-w-0 space-y-5 border-0 py-4">
          <Field label="名称" value={draft.name} disabled={draft.official} onChange={name => patch({ name })} />
          {kind !== 'claude' && <><Field label="供应商标识" value={draft.providerKey} disabled={!!draft.id} onChange={providerKey => patch({ providerKey })} note={`写入 ${providerTitle(kind)} 的 ${kind === 'opencode' ? 'provider' : 'providers'} 键；已有标识不可改名，复制会生成新标识。`} />
            {kind === 'pi' ? <Select label="接口格式" value={string(config.api)} onChange={value => setRoot('api', value)}>{PI_APIS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</Select> : <>
              <Select label="接口格式 / SDK" value={OPENCODE_SDKS.some(([value]) => value === config.npm) ? config.npm : 'custom'} onChange={value => setRoot('npm', value === 'custom' ? '' : value)}>{OPENCODE_SDKS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}<option value="custom">自定义 SDK / 原生默认</option></Select>
              <Field label="SDK npm 包" value={string(config.npm)} onChange={value => setRoot('npm', value)} note="使用 OpenCode 原生 SDK；内置提供商可留空采用原生默认。" />
            </>}</>}
          {draft.official ? <p className="rounded-xl bg-primary-container p-4 type-body-medium text-on-primary-container">Claude Official 使用 Claude Code 原生登录，无需填写 API Key 或自定义地址。</p> : <>
            <Field label="API Key" type="password" autoComplete="new-password" value={kind === 'claude' ? string(env[authField]) : string(connection.apiKey)} onChange={value => kind === 'claude' ? setEnv(authField, value) : setConnection('apiKey', value)} />
            <Field label={kind === 'claude' ? '请求地址' : 'Base URL'} type="url" value={kind === 'claude' ? string(env.ANTHROPIC_BASE_URL) : string(connection[kind === 'opencode' ? 'baseURL' : 'baseUrl'])} onChange={value => kind === 'claude' ? setEnv('ANTHROPIC_BASE_URL', value) : setConnection(kind === 'opencode' ? 'baseURL' : 'baseUrl', value)} placeholder="https://api.example.com" />
          </>}
          {kind === 'claude' ? <details className="rounded-[22px] bg-surface-container-low p-4"><summary className="cursor-pointer type-title-medium">高级选项</summary><div className="mt-5 space-y-5">
            <p className="type-body-small text-on-surface-variant">接口格式：Anthropic Messages（原生）</p>
            {!draft.official && <Select label="认证字段" value={authField} onChange={value => change(next => { const e = object(next.env); const secret = e[authField] ?? ''; delete e.ANTHROPIC_API_KEY; delete e.ANTHROPIC_AUTH_TOKEN; e[value] = secret; next.env = e; })}><option value="ANTHROPIC_AUTH_TOKEN">ANTHROPIC_AUTH_TOKEN（默认）</option><option value="ANTHROPIC_API_KEY">ANTHROPIC_API_KEY</option></Select>}
            <div className="flex flex-wrap items-center justify-between gap-2"><h3 className="type-title-medium">模型映射</h3><Button variant="tonal" size="sm" icon={<MdOutlineFileDownload />} disabled={fetching || draft.official} onClick={() => void fetchModels()}>{fetching ? '获取中…' : '获取模型列表'}</Button></div>
            <p className="type-body-small text-on-surface-variant">显示名称影响 /model 菜单；1M 是向 Claude Code 声明上下文能力，不会改变服务端实际限制。官方登录的模型列表由 Claude 自身提供。</p>
            {CLAUDE_MODELS.map(role => {
              const raw = string(env[role.key]); const oneM = /\[1m\]$/i.test(raw); const model = raw.replace(/\[1m\]$/i, '');
              return <section key={role.key} className="space-y-3 rounded-xl bg-surface-container p-4"><h4 className="type-title-small">{role.role}</h4>
                {role.name && <Field label={`${role.role} 显示名称`} value={string(env[role.name])} onChange={value => setEnv(role.name, value)} />}
                <Field label={`${role.role} 实际请求模型`} value={model} onChange={value => setEnv(role.key, value ? value + (oneM ? '[1m]' : '') : '')} />
                {models.length > 0 && <Select label={`选择 ${role.role} 模型`} value="" onChange={value => change(next => { next.env = object(next.env); next.env[role.key] = value + (oneM ? '[1m]' : ''); if (role.name && !next.env[role.name]) next.env[role.name] = models.find(m => m.id === value)?.name ?? value; })}><option value="" disabled>从已获取列表选择</option>{models.map(m => <option key={m.id} value={m.id}>{m.name}</option>)}</Select>}
                {role.oneM && <label className="flex items-center gap-3 type-body-medium"><input type="checkbox" disabled={!model} checked={oneM} onChange={e => setEnv(role.key, model + (e.target.checked ? '[1m]' : ''))} />声明 {role.role} 支持 1M</label>}
              </section>;
            })}
            <Field label="User-Agent（可选）" value={ua} onChange={value => setEnv('ANTHROPIC_CUSTOM_HEADERS', [...customHeaders.split('\n').filter(line => line.trim() && !/^user-agent\s*:/i.test(line)), ...(value ? [`User-Agent: ${value}`] : [])].join('\n'))} />
            <label className="block"><span className="type-label-large">自定义请求头</span><textarea rows={4} className={`${inputClass} font-mono text-xs`} value={customHeaders} onChange={e => setEnv('ANTHROPIC_CUSTOM_HEADERS', e.target.value)} placeholder={'X-Title: agentM'} /><span className="mt-1 block type-body-small text-on-surface-variant">每行 Header: value，写入 ANTHROPIC_CUSTOM_HEADERS。User-Agent 与此字段同步。</span></label>
          </div></details> : kind === 'opencode' ? <OpenCodeFields config={config} change={change} fetching={fetching} fetchModels={() => void fetchModels()} /> : <>
            <details className="rounded-[22px] bg-surface-container-low p-4"><summary className="cursor-pointer type-title-medium">请求头与兼容性</summary><div className="mt-4 space-y-5"><Pairs title="请求头" value={object(config.headers)} onChange={value => setRoot('headers', value)} /><Pairs title="兼容性选项" typed value={object(config.compat)} onChange={value => setRoot('compat', value)} /></div></details>
            <section className="space-y-4"><div className="flex flex-wrap items-center justify-between gap-2"><h3 className="type-title-large">模型配置</h3><div className="flex flex-wrap gap-1"><Button variant="tonal" size="sm" disabled={fetching} onClick={() => void fetchModels()}>{fetching ? '获取中…' : '获取模型列表'}</Button><Button variant="text" size="sm" icon={<MdAdd />} onClick={() => setRoot('models', [...modelRows, blankModel()])}>添加模型</Button></div></div>
              <p className="type-body-small text-on-surface-variant">切换提供商时使用列表中的第一个模型。能力与长度请按服务实际支持情况填写。</p>
              {modelRows.map((model, index) => <section key={index} className="space-y-4 rounded-[22px] bg-surface-container-low p-4">
                <div className="flex items-center justify-between"><h4 className="type-title-medium">模型 {index + 1}</h4><IconButton aria-label={`删除模型 ${index + 1}`} onClick={() => setRoot('models', modelRows.filter((_, i) => i !== index))}><MdDeleteOutline /></IconButton></div>
                <Field label={`模型 ID ${index + 1}`} value={string(model.id)} onChange={value => setModel(index, { id: value })} />
                <Field label={`显示名称 ${index + 1}`} value={string(model.name)} onChange={value => setModel(index, { name: value })} />
                <div className="flex items-center justify-between gap-3"><span className="type-body-medium">支持思考</span><Switch label={`模型 ${index + 1} 支持思考`} checked={model.reasoning === true} onChange={reasoning => setModel(index, { reasoning })} /></div>
                <div className="flex items-center justify-between gap-3"><span className="type-body-medium">支持图片输入</span><Switch label={`模型 ${index + 1} 支持图片输入`} checked={Array.isArray(model.input) && model.input.includes('image')} onChange={enabled => setModel(index, { input: enabled ? ['text', 'image'] : ['text'] })} /></div>
                <Field label={`上下文长度 ${index + 1}`} type="number" min={1} step={1} value={model.contextWindow == null ? '' : String(model.contextWindow)} onChange={value => setModel(index, { contextWindow: value ? Number(value) : null })} />
                <Field label={`最大输出 Token 数 ${index + 1}`} type="number" min={1} step={1} value={model.maxTokens == null ? '' : String(model.maxTokens)} onChange={value => setModel(index, { maxTokens: value ? Number(value) : null })} />
              </section>)}
            </section>
          </>}
        </fieldset>
        <details className="mt-3 rounded-[22px] bg-surface-container-low p-4"><summary className="cursor-pointer type-title-medium">配置源码</summary><div className="mt-4 space-y-3">
          <p className="type-body-small text-on-surface-variant">{kind === 'claude' ? '该提供商的 Claude settings.json 配置；切换时保留文件中其他设置。' : '对应 models.json 中 providers 下的此供应商对象。'} 源码与表单同步，包含已保存的密钥。</p>
          <div><label htmlFor="provider-source" className="type-label-large">配置 JSON</label><textarea id="provider-source" aria-invalid={!parsed.valid} spellCheck={false} disabled={busy} className={`${inputClass} min-h-80 font-mono text-xs`} value={draft.source} onChange={e => patch({ source: e.target.value })} /></div>
          {!parsed.valid && <p role="alert" className="type-body-small text-error">JSON 格式无效，请先修正源码；表单与保存暂不可用。</p>}
          <Button variant="text" disabled={busy || !parsed.valid} onClick={() => patch({ source: JSON.stringify(config, null, 2) })}>格式化 JSON</Button>
        </div></details>
      </>}
    </main>
    <footer className="flex shrink-0 justify-end gap-2 border-t border-outline-variant px-5 pb-[calc(16px+var(--sab))] pt-3"><Button variant="text" disabled={busy} onClick={back}>取消</Button><Button disabled={busy || fetching || !draft || !parsed.valid || !dirty} onClick={() => void save()}>{busy ? '处理中…' : '保存提供商'}</Button></footer>
    <Dialog open={discard} onClose={() => setDiscard(false)} title="放弃未保存的修改？" actions={<><Button variant="text" onClick={() => setDiscard(false)}>继续编辑</Button><Button onClick={() => useApp.getState().pop()}>放弃修改</Button></>}><p>离开后，本页未保存的修改会丢失。</p></Dialog>
    <Dialog open={chooseModels} onClose={() => setChooseModels(false)} title="选择要添加的模型" actions={<><Button variant="text" onClick={() => setChooseModels(false)}>取消</Button><Button disabled={!chosen.size} onClick={() => {
      if (kind === 'opencode') setRoot('models', { ...object(config.models), ...Object.fromEntries(models.filter(m => chosen.has(m.id) && !Object.prototype.hasOwnProperty.call(object(config.models), m.id)).map(m => [m.id, blankOpenCodeModel(m.name)])) });
      else setRoot('models', [...modelRows, ...models.filter(m => chosen.has(m.id) && !modelRows.some(row => row.id === m.id)).map(m => blankModel(m.id, m.name))]);
      setChooseModels(false);
    }}>添加所选模型</Button></>}>
      <div className="max-h-72 space-y-3 overflow-y-auto">{models.map(model => <label key={model.id} className="flex items-start gap-3 break-all"><input type="checkbox" checked={chosen.has(model.id)} onChange={e => setChosen(previous => { const next = new Set(previous); if (e.target.checked) next.add(model.id); else next.delete(model.id); return next; })} /><span>{model.name}<span className="block type-body-small">{model.id}</span></span></label>)}</div>
    </Dialog>
  </div>;
}

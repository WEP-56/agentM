import { useEffect, useState } from "react";
import {
  MdAdd,
  MdChevronRight,
  MdContentCopy,
  MdDeleteOutline,
  MdOutlineDescription,
  MdOutlineEdit,
  MdOutlineSystemUpdate,
  MdOutlineTune,
  MdOutlineVisibility,
  MdOutlineVisibilityOff,
} from "react-icons/md";
import { AGENTS, agentById, CUSTOM_PRESET, type AgentDef, type AgentId } from "@/data/agents";
import { useApp, type AgentStatus, type Provider } from "@/store/useApp";
import { Button, IconButton } from "@/components/md/Button";
import { Chip, Radio, TextField } from "@/components/md/Controls";
import { ListGroup, ListItem, TabPage, TopBar } from "@/components/md/Layout";
import { BottomSheet, Dialog } from "@/components/md/Overlay";
import { CircularProgress } from "@/components/md/Progress";
import { Ripple } from "@/components/md/Ripple";
import { AgentIcon, Shape } from "@/components/Brand";
import { hostOf, uid } from "@/utils/format";
import { isNative } from '@/platform/native';
import { NativeConfig } from './NativeConfig';

export const isInstalled = (s: AgentStatus) => s !== "not_installed" && s !== "installing";
const needsKey = (p: Provider) => !p.apiKey && p.presetId !== "ollama";

/* ───────────── Tab ───────────── */
export function ConfigScreen() {
  const agents = useApp((s) => s.agents);
  const installed = AGENTS.filter((a) => isInstalled(agents[a.id].status));
  if (isNative) return <NativeConfig />;
  return (
    <TabPage title="配置">
      {installed.length === 0 ? (
        <EmptyState />
      ) : (
        <div className="flex flex-col gap-3">
          {installed.map((a) => (
            <ConfigCard key={a.id} def={a} />
          ))}
        </div>
      )}
    </TabPage>
  );
}

function EmptyState() {
  const setTab = useApp((s) => s.setTab);
  return (
    <div className="flex flex-col items-center px-6 pt-14 text-center">
      <Shape kind="flower" className="size-28 text-surface-container-highest">
        <MdOutlineTune className="text-[40px] text-on-surface-variant" />
      </Shape>
      <p className="mt-6 type-title-medium text-on-surface">还没有安装 Agent</p>
      <p className="mt-1 type-body-medium text-on-surface-variant">安装后可在这里配置提供商与模型</p>
      <Button variant="tonal" className="mt-6" onClick={() => setTab("home")}>
        前往首页
      </Button>
    </div>
  );
}

function ConfigCard({ def }: { def: AgentDef }) {
  const cfg = useApp((s) => s.configs[def.id]);
  const running = useApp((s) => s.agents[def.id].status === "running");
  const setActive = useApp((s) => s.setActiveProvider);
  const push = useApp((s) => s.push);
  const showSnack = useApp((s) => s.showSnack);
  const [sheet, setSheet] = useState(false);
  const active = cfg.providers.find((p) => p.id === cfg.activeId) ?? cfg.providers[0];

  return (
    <div className="rounded-[28px] bg-surface-container-low">
      <div
        role="button"
        tabIndex={0}
        onClick={() => push({ name: "agentConfig", agentId: def.id })}
        className="relative flex cursor-pointer items-center gap-4 rounded-t-[28px] py-4 pl-4 pr-3 outline-none"
      >
        <Ripple />
        <AgentIcon agent={def} size={44} />
        <div className="min-w-0 flex-1">
          <div className="type-title-medium text-on-surface">{def.name}</div>
          <div className="truncate type-body-medium text-on-surface-variant">
            <span className="font-mono text-[13px]">{active.model || "默认模型"}</span>
            {needsKey(active) && <span className="text-tertiary"> · 未设置密钥</span>}
          </div>
        </div>
        <MdChevronRight className="text-[24px] text-on-surface-variant" />
      </div>
      <div className="flex gap-2 overflow-x-auto px-4 pb-4 no-scrollbar">
        {cfg.providers.map((p) => (
          <Chip
            key={p.id}
            selected={p.id === cfg.activeId}
            onClick={() => {
              if (p.id === cfg.activeId) return;
              setActive(def.id, p.id);
              showSnack(`${def.name} 已切换至 ${p.name}${running ? "，新会话生效" : ""}`);
            }}
          >
            {p.name}
          </Chip>
        ))}
        <Chip icon={<MdAdd />} onClick={() => setSheet(true)}>
          添加
        </Chip>
      </div>
      <ProviderSheet open={sheet} onClose={() => setSheet(false)} agentId={def.id} />
    </div>
  );
}

/* ───────────── Agent detail (pushed) ───────────── */
export function AgentConfigScreen({ agentId }: { agentId: AgentId }) {
  const def = agentById(agentId);
  const cfg = useApp((s) => s.configs[agentId]);
  const pop = useApp((s) => s.pop);
  const setActive = useApp((s) => s.setActiveProvider);
  const setModel = useApp((s) => s.setModel);
  const uninstall = useApp((s) => s.uninstallAgent);
  const showSnack = useApp((s) => s.showSnack);
  const [sheet, setSheet] = useState<{ open: boolean; provider: Provider | null }>({ open: false, provider: null });
  const [confirm, setConfirm] = useState(false);
  const [checking, setChecking] = useState(false);

  const active = cfg.providers.find((p) => p.id === cfg.activeId) ?? cfg.providers[0];
  const preset = def.presets.find((p) => p.id === active.presetId);
  const models = Array.from(new Set([...(preset?.models ?? []), active.model].filter(Boolean)));

  const check = () => {
    if (checking) return;
    setChecking(true);
    setTimeout(() => {
      setChecking(false);
      showSnack(`${def.name} 已是最新版本`);
    }, 1400);
  };

  return (
    <div className="flex h-full flex-col bg-surface">
      <TopBar title={def.name} onBack={pop} />
      <div className="min-h-0 flex-1 overflow-y-auto px-4 pb-10 no-scrollbar">
        <div className="flex items-center gap-4 px-2 pb-6 pt-1">
          <AgentIcon agent={def} size={56} />
          <div className="min-w-0">
            <div className="type-title-large text-on-surface">{def.name}</div>
            <div className="type-body-medium text-on-surface-variant">
              {def.vendor} · v{def.version}
            </div>
          </div>
        </div>

        <ListGroup
          title="提供商"
          action={
            <Button variant="text" icon={<MdAdd />} onClick={() => setSheet({ open: true, provider: null })}>
              添加
            </Button>
          }
        >
          {cfg.providers.map((p) => (
            <ListItem
              key={p.id}
              onClick={() => setActive(agentId, p.id)}
              icon={<Radio checked={p.id === cfg.activeId} />}
              headline={p.name}
              supporting={
                <>
                  <span className="font-mono text-[12px]">{hostOf(p.baseUrl)}</span>
                  {needsKey(p) && <span className="text-tertiary"> · 未设置密钥</span>}
                </>
              }
              trailing={
                <IconButton
                  aria-label="编辑"
                  onClick={(e) => {
                    e.stopPropagation();
                    setSheet({ open: true, provider: p });
                  }}
                >
                  <MdOutlineEdit />
                </IconButton>
              }
            />
          ))}
        </ListGroup>

        <ListGroup title="模型">
          <div className="rounded-[4px] bg-surface-container-low p-4">
            <div className="flex flex-wrap gap-2">
              {models.length ? (
                models.map((m) => (
                  <Chip key={m} selected={m === active.model} onClick={() => setModel(agentId, active.id, m)}>
                    <span className="font-mono text-[13px] font-normal">{m}</span>
                  </Chip>
                ))
              ) : (
                <span className="type-body-medium text-on-surface-variant">编辑提供商以设置模型</span>
              )}
            </div>
          </div>
        </ListGroup>

        <ListGroup title="其他">
          <ListItem
            icon={<MdOutlineDescription />}
            headline="配置文件"
            supporting={<span className="font-mono text-[12px]">{def.configPath}</span>}
            trailing={
              <IconButton
                aria-label="复制路径"
                onClick={(e) => {
                  e.stopPropagation();
                  void navigator.clipboard?.writeText(def.configPath).catch(() => {});
                  showSnack("路径已复制");
                }}
              >
                <MdContentCopy />
              </IconButton>
            }
          />
          <ListItem
            icon={<MdOutlineSystemUpdate />}
            headline="检查更新"
            supporting={<span className="font-mono text-[12px]">{def.pkg}</span>}
            onClick={check}
            trailing={checking ? <CircularProgress size={22} stroke={2.5} className="mr-2" /> : null}
          />
          <ListItem
            icon={<MdDeleteOutline className="text-error" />}
            headline={<span className="text-error">卸载</span>}
            onClick={() => setConfirm(true)}
          />
        </ListGroup>
      </div>

      <ProviderSheet
        open={sheet.open}
        provider={sheet.provider}
        onClose={() => setSheet((s) => ({ ...s, open: false }))}
        agentId={agentId}
      />
      <Dialog
        open={confirm}
        onClose={() => setConfirm(false)}
        icon={<MdDeleteOutline />}
        title={`卸载 ${def.name}？`}
        actions={
          <>
            <Button variant="text" onClick={() => setConfirm(false)}>
              取消
            </Button>
            <Button
              variant="dangerText"
              onClick={() => {
                setConfirm(false);
                void uninstall(agentId);
              }}
            >
              卸载
            </Button>
          </>
        }
      >
        配置文件会被保留，之后可随时重新安装。
      </Dialog>
    </div>
  );
}

/* ───────────── Provider editor ───────────── */
export function ProviderSheet({
  open,
  onClose,
  agentId,
  provider,
}: {
  open: boolean;
  onClose: () => void;
  agentId: AgentId;
  provider?: Provider | null;
}) {
  const def = agentById(agentId);
  const upsert = useApp((s) => s.upsertProvider);
  const remove = useApp((s) => s.deleteProvider);
  const count = useApp((s) => s.configs[agentId].providers.length);
  const showSnack = useApp((s) => s.showSnack);
  const presets = [...def.presets, CUSTOM_PRESET];

  const [presetId, setPresetId] = useState(def.presets[0].id);
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [apiKey, setApiKey] = useState("");
  const [model, setModel] = useState("");
  const [show, setShow] = useState(false);

  const applyPreset = (id: string) => {
    const p = presets.find((x) => x.id === id) ?? CUSTOM_PRESET;
    setPresetId(p.id);
    setName(p.id === "custom" ? "" : p.name);
    setBaseUrl(p.baseUrl);
    setModel(p.models[0] ?? "");
    setApiKey("");
  };

  useEffect(() => {
    if (!open) return;
    setShow(false);
    if (provider) {
      setPresetId(provider.presetId);
      setName(provider.name);
      setBaseUrl(provider.baseUrl);
      setApiKey(provider.apiKey);
      setModel(provider.model);
    } else applyPreset(def.presets[0].id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, provider]);

  const hint = presets.find((p) => p.id === presetId)?.keyHint;
  const valid = name.trim().length > 0 && baseUrl.trim().length > 0;

  const save = () => {
    upsert(agentId, {
      id: provider?.id ?? uid(),
      presetId,
      name: name.trim(),
      baseUrl: baseUrl.trim(),
      apiKey: apiKey.trim(),
      model: model.trim(),
    });
    showSnack(provider ? "已保存" : `已添加并切换至 ${name.trim()}`);
    onClose();
  };

  return (
    <BottomSheet
      open={open}
      onClose={onClose}
      title={provider ? "编辑提供商" : "添加提供商"}
      footer={
        <div className="flex items-center gap-2">
          {provider && count > 1 && (
            <Button
              variant="dangerText"
              onClick={() => {
                remove(agentId, provider.id);
                onClose();
                showSnack(`已删除 ${provider.name}`);
              }}
            >
              删除
            </Button>
          )}
          <span className="flex-1" />
          <Button variant="text" onClick={onClose}>
            取消
          </Button>
          <Button disabled={!valid} onClick={save}>
            保存
          </Button>
        </div>
      }
    >
      {!provider && (
        <div className="-mx-6 mb-5 flex gap-2 overflow-x-auto px-6 no-scrollbar">
          {presets.map((p) => (
            <Chip key={p.id} selected={presetId === p.id} onClick={() => applyPreset(p.id)}>
              {p.name}
            </Chip>
          ))}
        </div>
      )}
      <div className="flex flex-col gap-4 pt-1">
        <TextField label="名称" value={name} onChange={setName} />
        <TextField label="Base URL" value={baseUrl} onChange={setBaseUrl} mono />
        <TextField
          label="API Key"
          value={apiKey}
          onChange={setApiKey}
          type={show ? "text" : "password"}
          mono
          supporting={hint ? `格式 ${hint}` : undefined}
          trailing={
            <IconButton aria-label={show ? "隐藏" : "显示"} onClick={() => setShow((v) => !v)}>
              {show ? <MdOutlineVisibilityOff /> : <MdOutlineVisibility />}
            </IconButton>
          }
        />
        <TextField label="模型" value={model} onChange={setModel} mono />
      </div>
    </BottomSheet>
  );
}

import { useMemo, useState, type ReactNode } from "react";
import {
  MdBrightnessAuto,
  MdCheck,
  MdCheckCircle,
  MdOpenInNew,
  MdOutlineBatteryChargingFull,
  MdOutlineDarkMode,
  MdOutlineFolder,
  MdOutlineHistory,
  MdOutlineLightMode,
  MdOutlineNotifications,
  MdOutlineRocketLaunch,
  MdOutlineSystemUpdate,
  MdRestartAlt,
} from "react-icons/md";
import { SiGithub } from "react-icons/si";
import { APP_NAME, APP_VERSION, GITHUB_URL } from "@/data/agents";
import { useApp, type Perm } from "@/store/useApp";
import { SEEDS, swatch } from "@/theme/theme";
import { Button } from "@/components/md/Button";
import { Segmented, Switch } from "@/components/md/Controls";
import { ListGroup, ListItem, TabPage } from "@/components/md/Layout";
import { Dialog } from "@/components/md/Overlay";
import { CircularProgress } from "@/components/md/Progress";
import { AppLogo, useIsDark } from "@/components/Brand";
import { cn } from "@/utils/cn";
import { isNative } from "@/platform/native";

const PERMS: { key: Perm; title: string; desc: string; icon: ReactNode; ask: string; note?: string }[] = [
  {
    key: "notifications",
    title: "通知",
    desc: "显示运行状态，保持后台服务",
    icon: <MdOutlineNotifications />,
    ask: `允许“${APP_NAME}”向你发送通知吗？`,
  },
  {
    key: "storage",
    title: "文件访问",
    desc: isNative ? "私有工作区，无需额外存储权限" : "在共享存储中读写项目",
    icon: <MdOutlineFolder />,
    ask: `允许“${APP_NAME}”管理所有文件吗？`,
    note: "用于将 /sdcard 挂载到 Ubuntu 子系统。",
  },
  {
    key: "battery",
    title: "后台运行",
    desc: "忽略电池优化，避免被系统回收",
    icon: <MdOutlineBatteryChargingFull />,
    ask: `要允许“${APP_NAME}”始终在后台运行吗？`,
    note: "这可能会增加电池消耗。",
  },
];

export function SettingsScreen() {
  const settings = useApp((s) => s.settings);
  const perms = useApp((s) => s.permissions);
  const setThemeMode = useApp((s) => s.setThemeMode);
  const setSeed = useApp((s) => s.setSeed);
  const setAutoStart = useApp((s) => s.setAutoStart);
  const restartRuntime = useApp((s) => s.restartRuntime);
  const setPermission = useApp((s) => s.setPermission);
  const resetPreview = useApp((s) => s.resetPreview);
  const showSnack = useApp((s) => s.showSnack);
  const dark = useIsDark();

  const [confirmRestart, setConfirmRestart] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const [ask, setAsk] = useState<Perm | null>(null);
  const [updating, setUpdating] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);
  const askDef = PERMS.find((p) => p.key === ask);

  const checkUpdate = () => {
    if (isNative) { showSnack('尚未配置应用更新源'); return; }
    if (updating) return;
    setUpdating(true);
    setTimeout(() => {
      setUpdating(false);
      showSnack(`已是最新版本 ${APP_VERSION}`);
    }, 1500);
  };

  return (
    <TabPage title="设置">
      <ListGroup title="外观">
        <div className="rounded-[4px] bg-surface-container-low p-4">
          <div className="mb-3 type-body-large text-on-surface">主题</div>
          <Segmented
            value={settings.themeMode}
            onChange={setThemeMode}
            options={[
              { value: "system", label: "跟随系统", icon: <MdBrightnessAuto /> },
              { value: "light", label: "浅色", icon: <MdOutlineLightMode /> },
              { value: "dark", label: "深色", icon: <MdOutlineDarkMode /> },
            ]}
          />
        </div>
        <div className="rounded-[4px] bg-surface-container-low p-4">
          <div className="mb-3 type-body-large text-on-surface">主题色</div>
          <div className="grid grid-cols-6 gap-2.5">
            {SEEDS.map((s) => (
              <Swatch
                key={s.id}
                hex={s.hex}
                name={s.name}
                dark={dark}
                selected={settings.seed.toLowerCase() === s.hex.toLowerCase()}
                onClick={() => setSeed(s.hex)}
              />
            ))}
          </div>
        </div>
      </ListGroup>

      <ListGroup title="运行时">
        <ListItem
          icon={<MdOutlineRocketLaunch />}
          headline={isNative ? "自动检查环境" : "自动启动"}
          supporting={isNative ? "打开应用时检查已安装的 Ubuntu" : "打开应用时启动 Linux 运行时"}
          onClick={() => setAutoStart(!settings.autoStartRuntime)}
          trailing={<Switch checked={settings.autoStartRuntime} onChange={setAutoStart} label={isNative ? "自动检查环境" : "自动启动"} />}
        />
        <ListItem
          icon={<MdRestartAlt />}
          headline={isNative ? "检查 Linux 环境" : "重启运行时"}
          supporting={isNative ? "实际运行 Bash 与包管理器自检" : "停止所有 Agent 并重新启动"}
          onClick={() => !restarting && setConfirmRestart(true)}
          trailing={restarting ? <CircularProgress size={22} stroke={2.5} className="mr-2" /> : null}
        />
      </ListGroup>

      <ListGroup title="权限">
        {PERMS.map((p) => (
          <ListItem
            key={p.key}
            icon={p.icon}
            headline={p.title}
            supporting={p.desc}
            onClick={perms[p.key] ? undefined : () => setAsk(p.key)}
            trailing={
              perms[p.key] ? (
                <span className="flex items-center gap-1 pr-1 type-label-large text-success">
                  <MdCheckCircle className="text-[18px]" />
                  已授权
                </span>
              ) : (
                <Button
                  variant="tonal"
                  size="sm"
                  onClick={(e) => {
                    e.stopPropagation();
                    setAsk(p.key);
                  }}
                >
                  授权
                </Button>
              )
            }
          />
        ))}
      </ListGroup>

      <ListGroup title="关于">
        <ListItem
          icon={<MdOutlineSystemUpdate />}
          headline="检查更新"
          supporting={`当前版本 ${APP_VERSION}`}
          onClick={checkUpdate}
          trailing={updating ? <CircularProgress size={22} stroke={2.5} className="mr-2" /> : null}
        />
        <ListItem
          icon={<SiGithub className="text-[22px]" />}
          headline="GitHub"
          supporting="源代码、问题反馈"
          onClick={() => GITHUB_URL ? window.open(GITHUB_URL, "_blank", "noopener,noreferrer") : showSnack('项目仓库地址尚未配置')}
          trailing={<MdOpenInNew className="mr-2 text-[20px] text-on-surface-variant" />}
        />
        <ListItem
          icon={<MdOutlineHistory />}
          headline="重新引导"
          supporting="清除预览数据并重新开始"
          onClick={() => setConfirmReset(true)}
        />
      </ListGroup>

      <div className="mt-10 flex flex-col items-center gap-3 pb-2">
        <AppLogo size={40} />
        <div className="type-label-medium text-on-surface-variant">
          {APP_NAME} {APP_VERSION}
        </div>
      </div>

      {/* Dialogs */}
      <Dialog
        open={confirmRestart}
        onClose={() => setConfirmRestart(false)}
        icon={<MdRestartAlt />}
        title={isNative ? "检查 Linux 环境？" : "重启运行时？"}
        actions={
          <>
            <Button variant="text" onClick={() => setConfirmRestart(false)}>
              取消
            </Button>
            <Button
              variant="text"
              onClick={async () => {
                setConfirmRestart(false);
                setRestarting(true);
                await restartRuntime();
                setRestarting(false);
              }}
            >
              {isNative ? "检查" : "重启"}
            </Button>
          </>
        }
      >
        {isNative ? "运行环境自检，不会终止当前终端会话。" : "正在运行的 Agent 会被停止，未保存的会话将丢失。"}
      </Dialog>

      <Dialog
        open={!!askDef}
        onClose={() => setAsk(null)}
        icon={<span className="text-primary">{askDef?.icon}</span>}
        title={<span className="block text-center type-title-large">{askDef?.ask}</span>}
        actions={
          <div className="flex w-full flex-col gap-2">
            <Button
              variant="tonal"
              className="w-full"
              onClick={() => {
                if (ask) setPermission(ask, true);
                setAsk(null);
              }}
            >
              允许
            </Button>
            <Button
              variant="tonal"
              className="w-full"
              onClick={() => {
                if (ask) setPermission(ask, false);
                setAsk(null);
              }}
            >
              不允许
            </Button>
          </div>
        }
      >
        {askDef?.note && <p className="text-center">{askDef.note}</p>}
      </Dialog>

      <Dialog
        open={confirmReset}
        onClose={() => setConfirmReset(false)}
        icon={<MdOutlineHistory />}
        title="重新引导？"
        actions={
          <>
            <Button variant="text" onClick={() => setConfirmReset(false)}>
              取消
            </Button>
            <Button
              variant="dangerText"
              onClick={() => {
                setConfirmReset(false);
                resetPreview();
              }}
            >
              重新开始
            </Button>
          </>
        }
      >
        将清除已安装的 Agent 与配置（仅限预览数据），并回到初次使用引导。
      </Dialog>
    </TabPage>
  );
}

/** Android "Wallpaper & style"-like three-tone seed swatch. */
function Swatch({
  hex,
  name,
  dark,
  selected,
  onClick,
}: {
  hex: string;
  name: string;
  dark: boolean;
  selected: boolean;
  onClick: () => void;
}) {
  const c = useMemo(() => swatch(hex, dark), [hex, dark]);
  return (
    <button
      aria-label={name}
      title={name}
      onClick={onClick}
      className="relative mx-auto grid aspect-square w-full max-w-12 place-items-center rounded-full outline-none"
    >
      <span
        className={cn(
          "absolute -inset-[3px] rounded-full border-2 transition-colors duration-200",
          selected ? "border-primary" : "border-transparent",
        )}
      />
      <span
        className={cn("relative block size-full overflow-hidden rounded-full transition-transform duration-300", selected && "scale-[0.86]")}
      >
        <span className="absolute inset-x-0 top-0 h-1/2" style={{ background: c.a }} />
        <span className="absolute bottom-0 left-0 h-1/2 w-1/2" style={{ background: c.b }} />
        <span className="absolute bottom-0 right-0 h-1/2 w-1/2" style={{ background: c.c }} />
      </span>
      {selected && (
        <span className="absolute grid size-6 place-items-center rounded-full bg-surface-container-low text-primary shadow-elev-1">
          <MdCheck className="text-[16px]" />
        </span>
      )}
    </button>
  );
}

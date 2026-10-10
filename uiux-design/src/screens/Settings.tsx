import { useMemo, useRef, useState } from "react";
import {
  MdBrightnessAuto,
  MdCheck,
  MdOpenInNew,
  MdOutlineDarkMode,
  MdOutlineHistory,
  MdOutlineLightMode,
  MdOutlineRocketLaunch,
  MdOutlineSystemUpdate,
  MdRestartAlt,
} from "react-icons/md";
import { SiGithub } from "react-icons/si";
import { APP_NAME, APP_VERSION } from "@/data/agents";
import { useApp } from "@/store/useApp";
import { SEEDS, swatch } from "@/theme/theme";
import { Button } from "@/components/md/Button";
import { Segmented, Switch } from "@/components/md/Controls";
import { ListGroup, ListItem, TabPage } from "@/components/md/Layout";
import { Dialog } from "@/components/md/Overlay";
import { CircularProgress } from "@/components/md/Progress";
import { AppLogo, useIsDark } from "@/components/Brand";
import { cn } from "@/utils/cn";
import { Permissions } from '@/components/Permissions';
import { isNative, nativeRequest } from "@/platform/native";
import { openProjectPage, type AppUpdate } from "@/platform/appUpdates";

export function SettingsScreen() {
  const settings = useApp((s) => s.settings);
  const version = useApp(s => s.native?.appVersion) ?? APP_VERSION;
  const setThemeMode = useApp((s) => s.setThemeMode);
  const setSeed = useApp((s) => s.setSeed);
  const setAutoStart = useApp((s) => s.setAutoStart);
  const restartRuntime = useApp((s) => s.restartRuntime);
  const resetPreview = useApp((s) => s.resetPreview);
  const showSnack = useApp((s) => s.showSnack);
  const dark = useIsDark();

  const [confirmRestart, setConfirmRestart] = useState(false);
  const [restarting, setRestarting] = useState(false);
  const [updating, setUpdating] = useState(false);
  const [confirmReset, setConfirmReset] = useState(false);

  const checking = useRef(false);
  const [update, setUpdate] = useState<AppUpdate | null>(null);
  const [updateError, setUpdateError] = useState('');
  const [updateDialog, setUpdateDialog] = useState(false);
  const openPage = (page: 'repository' | 'releases' | 'release', tag = '') => {
    void openProjectPage(page, tag).catch((error: Error) => showSnack(error.message));
  };
  const checkUpdate = async () => {
    if (checking.current) return;
    checking.current = true; setUpdating(true); setUpdate(null); setUpdateError('');
    try { setUpdate(await nativeRequest<AppUpdate>('checkAppUpdate')); }
    catch (error) { setUpdateError(error instanceof Error ? error.message : '更新检查失败'); }
    finally { checking.current = false; setUpdating(false); setUpdateDialog(true); }
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

      <Permissions />

      <ListGroup title="关于">
        <ListItem
          icon={<MdOutlineSystemUpdate />}
          headline="检查更新"
          supporting={`当前版本 ${version} · GitHub Release`}
          onClick={() => void checkUpdate()}
          disabled={updating}
          trailing={updating ? <CircularProgress size={22} stroke={2.5} className="mr-2" /> : null}
        />
        <ListItem
          icon={<SiGithub className="text-[22px]" />}
          headline="GitHub"
          supporting="源代码、问题反馈"
          onClick={() => openPage('repository')}
          trailing={<MdOpenInNew className="mr-2 text-[20px] text-on-surface-variant" />}
        />
        <ListItem
          icon={<MdOutlineHistory />}
          headline="重新引导"
          supporting={isNative ? "重新查看权限和环境准备，保留已有数据" : "清除预览数据并重新开始"}
          onClick={() => setConfirmReset(true)}
        />
      </ListGroup>

      <div className="mt-10 flex flex-col items-center gap-3 pb-2">
        <AppLogo size={40} />
        <div className="type-label-medium text-on-surface-variant">
          {APP_NAME} {version}
        </div>
      </div>

      {/* Dialogs */}
      <Dialog open={updateDialog} onClose={() => setUpdateDialog(false)}
        icon={<MdOutlineSystemUpdate />}
        title={updateError ? '检查更新失败' : !update?.found ? '暂无正式发布' : update.updateAvailable ? `发现新版本 ${update.tag}` : '当前无需更新'}
        actions={<><Button variant="text" onClick={() => setUpdateDialog(false)}>关闭</Button>
          {updateError && <Button variant="text" disabled={updating} onClick={() => { setUpdateDialog(false); void checkUpdate(); }}>重试</Button>}
          <Button onClick={() => openPage(update?.tag ? 'release' : 'releases', update?.tag)}>{update?.updateAvailable ? '前往下载' : '查看发布页'}</Button></>}>
        {updateError ? <p role="alert">{updateError}</p> : !update?.found ? <p>仓库尚未提供公开的正式 Release，可稍后再检查。</p> : <>
          <p>当前版本：{update.currentVersion}</p><p className="mt-2">最新正式版本：{update.tag}</p>
          {!update.updateAvailable && <p className="mt-3">当前安装版本不低于最新正式发布版本。</p>}
          {update.updateAvailable && update.notes && <div className="mt-4 max-h-64 overflow-y-auto whitespace-pre-wrap break-words type-body-small">{update.notes}</div>}
        </>}
      </Dialog>
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
        {isNative ? "返回初次使用引导，已安装的 Ubuntu、Agent、项目和配置会保留。" : "将清除预览数据并回到初次使用引导。"}
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

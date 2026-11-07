# 最近任务键双击修复

[English](README.md) | 简体中文

一个面向 AOSP / LineageOS 系 ROM（Launcher3 + Quickstep）的轻量 Xposed 模块，让三键导航下
**第二次点击多任务键时立即切换到下一个任务**，而不是等待最近任务（概览）转场动画结束。

已在 Redmi Note 8 Pro（begonia）运行 Project Infinity X（Android 16）上验证。

## 问题

Launcher3 的概览转场由 `OverviewCommandHelper` 串行化处理：概览动画播放期间，后续的概览指令会被排队。
因此双击多任务键的实际表现是：

1. 第一次点击：打开最近任务。
2. 第二次点击：需要等开场动画结束后才执行切换，产生约 600–700 ms 的空白等待。

LineageOS 系 ROM 还有一层额外延迟：`PhoneWindowManager` 收到 APP_SWITCH 按键后会等待约 300 ms，
以区分单击与双击，导致**每一次单击**也被拖慢。

## 实现原理

模块共安装三处 Hook：

| 进程 | Hook 目标 | 作用 |
| --- | --- | --- |
| `system_server`（`android`） | `PhoneWindowManager#updateKeyAssignments` | 把 `mAppSwitchDoubleTapAction` 强制设为 `NOTHING`，消除单击时"等待可能的双击"的 300 ms 延迟。等价于 `lineagesettings key_app_switch_double_tap_action = 0`，但无需写 Lineage 设置项的权限。 |
| Launcher | `OverviewCommandHelper#startRecentsTransitionWithEndTarget` | 在概览打开时捕获当前 `RecentsView` 实例。 |
| Launcher | `TaskbarNavButtonController#onButtonClick` | 检测第一次点击后 80–450 ms 内的第二次点击，直接调用 Launcher 自身的 `TaskView#launchWithAnimation` 启动下一个任务，跳过排队的概览转场。 |

双击时间窗口的上下界：

- **≥ 80 ms**：间隔更短的点击会落在概览转场初始化之前，此时切换会让 Launcher 的动画状态半初始化，
  下一次按多任务键会被吞掉。
- **≤ 450 ms**：超过该间隔就按两次独立的单击处理。

模块使用 Launcher 自身的启动路径（而不是 `startActivityFromRecents`），以便构造正确的
`ActivityOptions` 并正常收到启动回调，从而保持 Launcher 最近任务动画状态一致。

## 环境要求

- Android 11 及以上（`minSdkVersion 30`），Launcher3 + Quickstep 的 AOSP / LineageOS 风格 ROM。
- 支持 Xposed API ≥ 93 的框架，例如 **Zygisk Vector** 或 **LSPosed**。
- 作用域必须同时勾选 **系统框架**（`android`）与 **Launcher**（`com.android.launcher3`）。
  两者缺一不可：只勾系统框架仅能消除单击延迟，只勾 Launcher 则快速切换路径不会生效。

MIUI、HyperOS、Pixel Launcher 等非标准桌面不适用：被 Hook 的类在其中不存在。

## 安装

1. 安装 `recentsfix.apk`。
2. 在 Xposed 管理器（Vector / LSPosed）中启用模块。
3. 作用域勾选 **系统框架** 与 **Launcher3**。
4. 重启（或重启 `system_server` 与桌面进程）。

## 构建

`build.sh` 会编译模块并用 `debug.keystore`（首次运行自动生成）签名：

```bash
./build.sh
```

产物为 `out/recentsfix.apk`。脚本依赖：

- JDK（`javac`、`jar`、`keytool`）
- Android SDK 位于 `/home/v/Android/Sdk`，包含 `build-tools/36.0.0` 与 `platforms/android-36`
  （路径不同请修改 [build.sh](build.sh) 顶部的 `SDK`）

构建时不需要真正的 Xposed API：`stubs/` 中提供了最小桩代码，编译时即时生成。

## 验证与排查

```bash
adb logcat -s XposedBridge
```

模块日志均以 `RecentsFix:` 开头，正常应看到：

- `system_server: app switch double tap forced to NOTHING`
- `overview started, view=RecentsView`
- `recents tap: delta=...`，双击成功时还会有 `second tap: switched to next task`

## 停用 / 卸载

在 Xposed 管理器中停用模块后重启，或直接卸载 APK 后重启。模块不持久化任何数据：强制的
`NOTHING` 动作只存在于 `PhoneWindowManager` 内存中，每次开机都会重新设置。

## 备注

如果只想去掉单击延迟，且具备写 Lineage 设置项的权限，可以不用本模块：

```bash
settings put lineage_global key_app_switch_double_tap_action 0
```

这样双击切换仍走原生（较慢）路径；本模块则是把它替换为即时切换。

# 1.4.7 miuix 状态页、作用域与重启（Android 13+）

最低 Android 13 / API 33，完整 ZIP 同样拒绝旧系统安装。当前保持 miuix 0.9.4 界面，
没有加入 Liquid Glass；编译 SDK 37.2、目标 SDK 35 和包名不变。

## miuix 界面

页面使用实际的 miuix 0.9.4 Android 组件，不是原生控件改配色，也不修改上游库源码。
`MainActivity.kt` 只维护页面生命周期、刷新与确认后的操作；`PrivacyScreen.kt` 用
MiuixTheme / Scaffold / TopAppBar / Card / BasicComponent / SwitchPreference /
TextButton / OverlayDialog 组合页面。通过 Maven 依赖编译，构建不依赖旁边源码目录的绝对路径。

- 大标题随滚动收起，主题跟随系统，颜色/排版取自 MiuixTheme，卡片与按钮使用组件默认形状。
- 首屏显示状态和授权/刷新按钮；保护设置分别展示本次、下次启动，并提供确认后重启。
- 作用域检查保留独立卡片，未知状态不会被当作未勾选或已生效。
- 诊断信息可展开及选择复制；正文不限制行数，页面适配滚动，宽屏内容居中限制宽度。
- 状态、开关、复检、重启均保持原有安全语义。确认弹窗恢复后也不会自动执行操作，
  关闭弹窗或重复/过期回调不会提交新的 root 命令。
- 只保留 Android 启动窗口的基础 XML 主题及启动图标；旧页面布局、自制卡片和控件样式已移除。

## 入口与权限

在桌面打开 **IMEI Privacy**，也可通过 LSPosed 模块的设置入口打开同一个页面。
新包名为 `com.richard.imeiprivacy`。旧包 `com.dsh.imeiprivacy` 的授权和 LSPosed 启用/作用域
不会自动继承，必须重新授权新包并在 LSPosed 启用；不要同时启用新旧两个 Hook 模块。
APK 提供 Launcher 和 `de.robv.android.xposed.category.MODULE_SETTINGS` 入口。
没有导出的设置广播、Provider 或服务，Activity 不接受 Intent 参数修改设置。
应用不申请 READ_PHONE_STATE、设备标识或 INTERNET 权限，不读取、记录或显示完整 IMEI。

首次打开不会自动运行 su。点击“授权并读取状态”后请求 root；授权成功才允许修改设置。
拒绝或无法取得 root 后，本次应用进程会话不再执行 su。修改 root 管理器授权后，结束本应用
进程再重新打开。授权后的页面仅在前台约每 4 秒读状态，离开停止刷新；失败停止自动重试。
所有 root 操作在后台线程执行，设置写入和复检期间禁用重复操作。

root 接口为 `/data/adb/modules/imei_privacy/control.sh`，仅允许 `status`、
`set-enabled 0|1`、`recheck` 和明确确认的 `reboot-confirmed`。不接收任意命令或路径，不执行外部传入文本。
只安装 APK 时提示缺少配套接口，不回退到直接修改系统分区或任意 shell。

## 开关语义

“IMEI 伪装”为整套保护的总开关，不是只改变一张 UI 卡片。它同时控制通用 IMEI/MEID Hook、
root 属性覆盖、查找设备严格隐私 Hook 和关联的网络守护。关闭会降低隐私保护并影响原有
故障断网策略，因此需要确认，不会误称自检通过。

请求写入 `/data/adb/imei_privacy/ui.conf`：目录 0700，临时文件 0600 后原子替换。
脚本严格解析 `IMEI_PRIVACY_ENABLED=0|1`，不 source/eval 此文件；缺失或无效默认开启。
这是 root 管理的全局配置，多用户共用；不由应用私有 SharedPreferences 分别控制。

- 当前运行期间只保存下次启动意图，不停止属性 watcher、清除防火墙或切换部分 Hook。
- 完整启动时 `post-fs-data.sh` 先公布非持久 `sys.ipriv.enabled`，再决定执行或跳过保护。
  Hook 在包加载时读取该启动值，默认开启；属性读取失败也不会当作已关闭。
- 当前配置关闭时，service 不启动属性 watcher/守护；待系统启动后重试恢复模块自己记录的
  应用停用状态。不会恢复用户原本就停用的其他应用或无记录状态。
- 开关重新开启后仍需完整重启，随后按原自检策略断网/放行。关闭状态下复检入口不可用。
- 不主动读取/保存/恢复真实硬件标识，不触碰 modem NV；停止覆盖不保证所有固件立即重建
  原始属性或删除应用缓存、云端记录。页面显示的是启动配置，不是对未注入进程的穷尽验证。
- 只关闭 Activity、重启查找设备或保存设置不等于完整重启。
- “重启设备”在用户确认后通过 root 调用 `svc power reboot` 请求正常完整重启，不强制快速
  重启，不在刷新、保存或检查时自动执行；失败明确提示使用系统电源菜单。请先保存其他工作。

## 作用域提示

作用域卡片有新模块未登记、未启用、关键作用域缺失、已配置、未知五种状态。确认缺失时，
每次页面会话最多自动提示一次，卡片保留缺失清单及“前往 LSPosed 开启”按钮，不自行勾选。
按钮尝试打开 `org.lsposed.manager`；隐藏/寄生管理器没有 Launcher 时显示常驻通知/快捷方式指引。

root 控制接口使用模块 APK 中的 `ScopeInspector`，通过 `app_process` 调用 Android SQLite
以 `OPEN_READONLY | NO_LOCALIZED_COLLATORS` 打开 `/data/adb/lspd/config/modules_config.db`。
自定义无操作的损坏处理器，禁止默认错误处理器删除/修复管理器数据库；没有 SQL 写操作。
验证 modules/scope 所需列后，仅查询 `com.richard.imeiprivacy` 的 enabled 与作用域行，
不读取其他模块配置、请求内容、账号或个人标识，不复制/上传数据库。

检查查找设备当前 Android 用户的作用域、主用户电话服务，以及 `system` / 旧名 `android`
的主用户系统框架作用域。这里是已知关键子集，不证明全部推荐应用或所有用户都已注入。
不依据 ROM 或管理器版本号分支；标准结构已对照 LSPosed 上游 ConfigManager / ConfigFileManager。
数据库不存在、schema 不支持、SELinux/读取失败、helper 不可运行时显示 UNKNOWN，不能据此
断言未勾选。无法检测的 fork 请手动确认。接口拒绝无效用户号和错误用户/过期快照。

检测到旧包仍安装时提醒先停用旧 LSPosed 模块；不会自动卸载 APK、导入旧作用域或改写管理器。
root 全局配置目录不变。新包名需要重新授予 root，新的 LSPosed 配置也不会被旧模块行冒充。

## 状态可信度

页面分别显示“本次启动”和“下次启动”。只有当前挑战匹配、5 秒内的守护快照、当前启动保护
开启且进程数大于 0、已验证数等于进程数时，才能显示“已通过自检 / 允许联网”。
未来时间、过期报告、缺字段、重复协议键和未知状态均不会显示通过。

“断网”表示守护报告当前处于拦截状态，不代表全部 Hook 已通过；“未验证”也不能直接推断
LSPosed 未启用。1.4.5 将只读的保存配置检查和运行期自检分别展示，不会因作用域已勾选就声称注入生效。
“重新自检”复用 1.4.3 的挑战协议：先断网再检查，不定位、不绑定、不执行远程命令。

页面结果仍是周期性快照，不会消除用户已接受的多进程检测窗口，不能证明所有信息绝不泄露。
完整安全边界见 [HEALTH_GUARD.md](HEALTH_GUARD.md)。

## 验证范围

本地包含 80 项 Java 回归，19 个守护模拟场景和 26 个控制接口/安装器
模拟场景。控制接口测试在隔离目录使用假命令，覆盖无 root 拒绝、设置持久化、当前/下次状态
分离、无效参数/文本不执行、停用模块、关闭时禁止复检、脱敏输出和关闭启动分支，
以及确认后重启、无权限/失败不报成功、作用域 helper 不可用回退 UNKNOWN、多用户传递。
安装器场景验证 API 28/32 拒绝、API 33/37 接受、未知 API 拒绝，命令均在隔离目录 mock。
15 项新增展示/确认模型测试覆盖初始/未授权/忙碌、待重启与实际状态分离、过期报告、
作用域配置不等于运行健康、旧包提醒和固定确认命令。Java 测试不替代 Compose 界面实测。
Git Bash/NTFS 不具备 Android 的 POSIX 权限语义，0600/0700 设置代码存在，但不以 Windows
mock 通过冒充设备权限验证。未连接手机，尚未实测界面布局、SELinux/真实 LSPosed SQLite/WAL
访问、管理器跳转和重启；所有重启测试均使用假 svc，没有实际重启任何设备。

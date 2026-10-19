# IMEI Privacy Shield

面向 KernelSU + Zygisk Next + LSPosed 的双层 IMEI 隐私模块。

## 1.4.7：最低支持 Android 13

`minSdk` 从 28 提升到 33，即最低 Android 13；`compileSdk 37.2` 和 `targetSdk 35` 不变。
完整模块安装器也会拒绝 Android 12 及更低系统，避免 APK 无法加载时仍单独装入 root 守护。
本次不向 IMEI 模块加入 Liquid Glass，保留 miuix 界面和已有保护逻辑。旧系统请保留旧版本，
已发布的旧 APK / ZIP 不覆盖删除。

## 1.4.6：使用真正的 miuix 0.9.4 组件

- 将原生 XML / Android Widget 页面迁移为 Jetpack Compose，接入 `miuix-ui-android:0.9.4`
  和 `miuix-preference-android:0.9.4`，以本地 miuix-0.9.4 源码、文档和示例为 API 依据。
- 使用 MiuixTheme、Scaffold、可折叠 TopAppBar、Card、BasicComponent、SwitchPreference、
  TextButton 与 OverlayDialog；主题色、文字样式及组件圆角来自 miuix，支持系统明暗主题。
- 首屏可直接授权/刷新；本次与下次启动设置分开展示；诊断详情折叠，窄屏与横屏可滚动。
- 保留 root 拒绝压制、确认后重启、作用域提醒、失败状态与过期处理，不修改隐私 Hook 或守护逻辑。
- 删除不再使用的旧页面 XML、自制卡片背景和 UI 样式，旧版 APK/ZIP 保留。

安装完整 1.4.6 ZIP 后重启，包名仍为 `com.richard.imeiprivacy`。本轮未连接手机，尚未验证
设备上的实际界面布局/动画；本地执行 Java 回归、root 控制 mock、完整 lint 与 Release 构建。

## 1.4.5：新包名、重启与作用域提示

- APK / Java / Xposed 入口统一为 `com.richard.imeiprivacy`，不沿用旧包名的 LSPosed 启用状态。
- 状态页增加“重启设备”，只在用户确认后通过 root 请求正常完整重启；保存/刷新不触发重启。
- 只读检查 LSPosed 中新模块的启用状态，以及查找设备（当前用户）、电话服务（主用户）、
  系统框架三个关键作用域。确认缺失时显示清单并提示前往 LSPosed；未知结构/访问失败显示“无法确认”。
- 提供打开管理器按钮；隐藏/寄生管理器无直接入口时提示从常驻通知或快捷方式进入。
- 检测到旧 APK 仍安装时提醒停用旧模块，不擅自卸载或修改 LSPosed 数据库。

安装完整 1.4.5 ZIP 并重启后，请重新在 LSPosed 启用 **新包名** 的模块、勾选作用域，再完整
重启。root 配置路径和 KernelSU 模块 ID 不变，原伪标识及开关设置保留。手机未连接，本轮
仅完成本地构建、逻辑/模拟回归和静态验证。

## 1.4.4：状态界面与伪装总开关

- 从桌面 IMEI Privacy 图标，或 LSPosed 模块的设置入口打开。
- 状态页区分已通过并放行、断网、停用、异常、未验证及关闭，不把开关开启当作 Hook 生效。
- 首次点击“授权并刷新状态”请求 root；授权后仅在页面前台每约 4 秒刷新。拒绝后本次进程会话不再请求 su。
- “IMEI 伪装”是整个保护的总开关：同时协调 IMEI / MEID Hook、root 属性覆盖及查找设备保护。
  保存到 root 私有目录，下次完整重启生效；界面分别展示本次与下次启动状态。不自动重启。
- 关闭不修改 modem，也不读取/保存真实 IMEI；只停止本模块覆盖，原始属性是否重建由固件决定。
- 支持浅色/深色主题，不申请电话或联网权限，不展示真实或伪造的完整设备标识。

必须更新整个 1.4.4 ZIP 并完整重启，只装新版 APK 没有配套控制接口。安装步骤及开关语义见
[界面与设置说明](UI_GUIDE.md)。本轮没有连接设备，未做实际界面和 root 切换验证。

## 1.4.3：自检通过后联网，失效重新阻断

默认策略按用户选择：查找设备起初禁止联网，**当前 UID 下所有进程**的已知路径自检
通过后才撤销本模块的 IPv4/IPv6 阻断；自检失败、无报告、新进程未验证、报告过期或属性
覆盖失效都会重新阻断。正常检查约每 5 秒完成一次，有效期 15 秒；root 侧约每秒检查，
实际间隔会受负载影响，存在已明确接受的多进程/检测间隔窗口，并非零窗口隔离。

- 不是只看 Hook 数：通过真实 Hook 分发检查伪标识、对象/缓存、FID、Cloud ID、请求和发送参数。
  探针用合成数据，并截断目标原方法；不发测试请求，不触发定位、绑定或凭据更新。
- 应用内网络门禁在未验证/失败/过期时拒绝已覆盖的 Java 网络调用；root UID 防火墙补充覆盖
  同 UID native 套接字。root 监督进程负责守护子进程异常时先收回放行。
- 共享 UID 时不封禁整个 UID，改为尝试仅停用查找设备；防火墙不可用时同样尝试停用/停止。
  若两种方式都不能执行，明确显示 `ERROR`，不能当成已经拦截。
- KernelSU 的“操作”按钮请求复检并显示状态，不输出真实或伪标识。只读状态命令：
  `sh /data/adb/modules/imei_privacy/action.sh status`。
- 默认仅断网；`config.conf` 的 `FINDDEVICE_STOP_ON_FAILURE=1` 可在已确认失败时同时禁用查找设备。
  模块保存原来的启用状态；复检、恢复或卸载只恢复模块自己做出的禁用。

**必须安装整个 1.4.3 ZIP，确认 LSPosed 启用并勾选查找设备，然后完整重启；不能只更新 APK。**
未连接设备，本轮仅做本地测试/构建，尚未验证设备上的 iptables、SELinux 与探针行为。
完整机制、恢复方法和安全边界见 [自检与断网保护](HEALTH_GUARD.md)。

## 1.4.2：按语义自动发现，不维护版本适配表

已移除查找设备内部混淆类名、单字母方法名和固定字段名的匹配分支。模块不读取 ROM/
APK 版本号来决定 Hook，而是在进程加载时读取当前 base APK 及 split APK 的全部 DEX：

- 从诊断标签及格式化参数的数据流发现设备信息字段，声明顺序、寄存器读取顺序不决定字段用途。
- 从 `cloudsp_fid` 参数值追溯凭据 getter，再依据签名接口发现其具体实现；不改账号 getter。
- 从 HTTP I/O、发送签名和委托关系发现发送入口；同签名但无调用关系的无关方法不会命中。
- 根据协议键、类型及调用关系发现缓存、Cloud ID Provider 和请求构造入口。
- 安装前重新校验运行时类型；同一进程/类加载器只扫描一次，不保存按旧版本生成的符号缓存。

本地测试包含两个真实 APK（global 20.0.8 / CN 20.15.62）及完全改名、字段读取乱序、
分支赋值、多 DEX 和无关对象反例；两份 APK 使用同一套发现规则，无版本分支。
尚未安装本次产物到手机进行运行时验证。原有 1.4.0/1.4.1 包均保留。

后续版本只要保留这些语义特征即可自动重新发现；如果改用新协议、移除语义特征、
采用不支持的 DEX 格式或改为 native 实现，模块会报告缺失/发现失败，不能保证任意未来重写
都自动覆盖。详情见 [自动发现设计与验证](ADAPTIVE_DISCOVERY.md)。

## 历史：1.4.1 OS4 兼容修复（已被自动发现替代）

针对 HyperOS `OS4.0.0.17.XOBCNXM` / 查找设备 CN `20.15.62` 的 APK 分析修复，
同时保留 global `20.0.8` 适配。已完成本地单元测试和编译；尚未在新版 OS4 上安装验证。
原 `1.4.0` APK/ZIP 保留不覆盖，`1.4.1` 使用 versionCode 7，可覆盖安装已有 code 6。

- 按真实 DEX 名称及字段结构分别识别 `q1.f` / `dx.i`，不再把 JADX 显示别名当运行时名称。
- IMEI/MEID 只读取模块自有 `persist.sys.ipriv.*` 配置，缺失时使用伪值；不会从可能尚未
  覆盖的 radio 属性导入真实 IMEI。
- 补齐 `Build.SERIAL`、Settings `getStringForUser`、有权限校验的 CloudIdProvider、
  稳定命名的凭据 getter 和 CN FID 缓存读取。
- 在请求构造及 `XHttpClient` 发送入口处理 Map、GET 查询、表单、JSON 中的已知敏感字段，
  覆盖晚追加的 FID、CPU ID、推送 token 等；小米账号 ID、serviceToken、Cookie 和签名字段不改。
- 记录实际注册数量、关键适配缺失警告，以及每条路径首次命中；日志不打印标识值。
- 共享进程中重复包回调不会叠加注册同一组方法。

**安装新 ZIP/APK 并完整重启前，手机仍运行旧版模块。属性应用成功或 Hook 注册成功，
都不等于已经验证全部读取和上传路径。** 见 [OS4 修复审计](OS4_COMPAT_FIX.md)。

## 防护范围

- KernelSU 层持续覆盖 `ro.vendor.oem.imei*`、`persist.vendor.radio.imei*`、
  `ro.ril.oem.imei*`、`persist.radio.imei*`、`ro.ril.miui.imei*`。
- LSPosed 层在电话服务端拦截 `getImei()`、`getDeviceId()`、`getMeid()` 及其
  Binder 入口，避免特权应用绕过普通 Android 权限限制读取真实值。
- 覆盖 HyperOS 的 `MiuiTelephonyManagerStub`、`MiuiPhoneInterfaceManager`、
  `ImeiMeidSource` 及双卡列表接口，设置页和 CIT 也会得到伪值。
- 覆盖已分析的 Qualcomm `QtiImeiInfo/getImeiInfo` 对象与服务端数组；其他版本仍需验证。
- 旧版 API 通过 Manifest `xposedscope`、新版 API 通过
  `META-INF/xposed/scope.list` 声明作用域；当前设备已安装的 440 个包全部列为默认范围。
- 对部分小米组件的 IMEI 缓存、双卡列表和 MD5 封装方法也返回对应伪值或伪值哈希。
- 对 `com.xiaomi.finddevice` 启用严格隐私模式：伪装 Android ID/Cloud ID、
  OAID、GAID、OneTrack instanceId、SN、WLAN MAC、FID、IMSI/ICCID、电话号码、
  推送 token 和设备型号；清空应用列表、Wi-Fi 扫描、基站及定位结果。小米账号身份
  不做修改。查找设备内置的 OneTrack 统计事件和网络上传开关会被关闭。
- 首次启动随机生成两个通过 Luhn 校验的 15 位伪 IMEI，并在模块存续期间保持一致。
- 不直接改写 modem EFS、NV 550 或系统分区文件；KernelSU 通过模块挂载提供 APK。
  停用两个层的模块并完整重启可恢复原始读取行为；不会自动清除已经存在的云端记录。

## 构建

```powershell
pwsh -File .\build.ps1
```

构建脚本使用本机 JBR 17。项目源码仍兼容 Java 17/21；这里不使用 GraalVM 21，
因为其 `jlink` 的 JVMCI 模块依赖与 Android 的 JDK Image Transform 不兼容。

产物：

- `dist/IMEI-Privacy-v1.4.7.zip`：Android 13+ 的 KernelSU 模块，内含 miuix 界面、Hook APK 和 root 联网守护。
- `dist/IMEI-Privacy-v1.4.7.apk`：Android 13+ 的 miuix 界面与 Hook APK；不能单独替代配套 root 模块。

## 安装

1. 在 KernelSU 管理器中刷入 ZIP 并重启。
2. 打开 LSPosed，启用 **IMEI Privacy**。
3. 本次保留当前源码中的推荐作用域清单，不自动修改手机上的 LSPosed 配置。
   请手动确认 `com.xiaomi.finddevice` 在作用域中（所有相关进程均需注入）。
4. 完整重启后，检查 LSPosed 日志中的 `ImeiPrivacy/FindDevice` 注册数、`WARNING`
   和 `first hit`，不要仅凭模块安装状态判断是否生效。

## 严格隐私模式影响

查找设备的 FID、推送 token、离线查找标识和定位结果也会被替换或清空。账号登录仍可
保留，但远程定位、锁定、解锁、离线查找及设备绑定可能失效。这是隐私优先模式的预期
结果；停用 LSPosed 模块并卸载 KernelSU 模块后重启即可恢复原始行为。

## 边界

本模块保护普通及特权 Android 应用常用的属性、Telephony API 和 Binder 路径。
已经获得 root、可直接访问 DIAG/QMI 或读取 modem NV 的进程仍能绕过；不要向不可信
应用授予 root 权限。模块也不会改变运营商网络实际看到的 IMEI。

Java Hook 与自检不能证明覆盖未知 OS/APK、动态代码、native/Binder 私有标识路径、加密或
二进制内容、其他 UID 代理上传，以及账户或云端已有数据。1.4.3 增加了仅针对目标 UID 的
IPv4/IPv6 防火墙，不删除凭据或改变锁定/解锁校验；未触发定位、绑定或远程命令。
自检的通过是已知路径在某个时刻的通过，不代表“所有个人信息绝不外泄”。

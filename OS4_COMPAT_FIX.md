# 1.4.1：查找设备 OS4 兼容修复

> 本文保留为历史审计。1.4.2 已移除下面的混淆符号适配分支，改用
> [DEX 语义自动发现](ADAPTIVE_DISCOVERY.md)，表中类名不再参与运行时目标选择。

## 已核实的问题

分析对象是连接设备上的 HyperOS `OS4.0.0.17.XOBCNXM`，
`com.xiaomi.finddevice` CN `20.15.62`（201562）。设备上的旧模块能注入，
但其“strict privacy hooks installed”日志不检查关键挂钩是否存在。

旧源码使用 `p039q1.f`、`p054x0.b/g` 及 `f5888b` 等 JADX 别名。
这些不是 APK 的运行时 DEX 名称，导致对应 Hook 找不到类/字段；
CN 新版也换了类结构。已经用 `apkanalyzer dex code` 核对真实签名。

| 路径 | global 20.0.8 | CN 20.15.62 |
| --- | --- | --- |
| 核心设备对象 | `q1.f`，a/g 为 int | `dx.i`，a/g 为 enum |
| 设备 ID / 原值 / SN / MAC / 型号字段 | b / c / d / e / f | b / c / d / e / f |
| 核心读取 | `a(Context,boolean)` | `c(Context)`、`d(Context,boolean)` |
| 凭据 FID | `x0.b/g.a()`，校验 b/c/d 接口形状 | `DeviceCredentialTZ.getFId()`、软件凭据同名 getter |
| 请求入口 | `C0.l`，两个 Map/HashMap，返回 JSONObject | `jh.c`，两个 Map，返回 JSONObject |
| 发送入口 | `miui.cloud.net.XHttpClient` | 同名，按签名识别 URL/headers/body |

另一个独立问题是旧模块从 `ro.ril.miui.imei0/1` 读取“假 IMEI”：
如果属性覆盖失败或启动竞态，真实值也会通过格式校验并被缓存。
现在只信任模块命名空间 `persist.sys.ipriv.imei0/1` 和 `.meid0/1`，缺失用内置伪值。

## 实现边界

- `CloudIdProvider.call` 只在原方法成功返回 Bundle 后替换 `result_id`，保留调用方校验。
- 构造器和缓存对象读取返回后替换 b–f，避免只修改新建对象。
- CN 的 `wh.e0.a(Context)` 与 `device_fid` 偏好读取也伪装，既有磁盘凭据不删除。
- 请求采用复制替换，不在原始/不可修改 Map 上 put；空 headers 不影响 body 索引，
  异步调用的 context 不被误当请求体。JSON 解析失败会取消该请求，不带原数据回退发送。
- GET 查询逐字段替换，保留账号和认证参数的原始编码、顺序及重复键。
- 不改账号归属、私钥、sign()、锁定状态、签名验证、定位开关和系统分区。
- 不新增网络防火墙。二进制、native、未知协议/版本和其他进程的读取上传尚不能保证覆盖。
- 不清除账号云端历史记录。界面显示旧记录本身并不能证明新请求仍在泄露。

## 验证方法和目前状态

本地 `testDebugUnitTest`（与 release 共用 Java 源码）覆盖以下回归：
模块属性读取白名单/回退、MEID 一致性、两种对象布局与别名拒绝、不可变/嵌套 Map、
新 CN 字段、晚追加 FID、账号字段不变、查询编码/重复键、幂等、空 headers、
异步 context、JSON 及列表清空。`assembleRelease` 和签名/包内检查另行完成。

本次未安装新模块、未重启设备、未触发绑定/定位/锁定或远程操作。
安装后需要完整重启并确认 LSPosed 对查找设备的作用域，随后检查：

1. `registered` 以及 core/fid/cloud/requests/transport 实际注册数。
2. 任何 `WARNING` 都表示缺少适配或某组安装失败，不能当成完整保护。
3. `first hit=` 只记录该类别确实执行过一次，不打印参数或标识值；没有命中不是保护成功。

即使所有关键数大于零，也只是已知 Java 路径覆盖情况，不能证明“所有个人信息绝不外泄”。
需要更强隔离时应另行选择关闭查找设备或按独立 UID 阻断网络，并评估其功能影响。

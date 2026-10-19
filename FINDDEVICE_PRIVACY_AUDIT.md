# 小米查找设备 20.0.8 隐私标识审计

> 旧文中的 p039q1 等是 JADX 显示别名，不能用作 Hook 的运行时类名。
> 1.4.1 已修正运行时名称及 CN OS4 兼容路径，详见 [OS4_COMPAT_FIX.md](OS4_COMPAT_FIX.md)。

对象：当前设备 `/product/priv-app/MiuiFindDevice/MiuiFindDevice.apk`，包名
`com.xiaomi.finddevice`，版本 `20.0.8`。结论来自当前 APK 的静态分析和 PackageManager
授权状态；未触发绑定、定位、锁定或网络请求。

## 结论

隐藏 IMEI 不能阻止查找设备识别手机。当前版本仍维护多组稳定身份，其中最关键的是
设备凭据 FID、由 Android ID 派生的 Cloud ID、SN、WLAN MAC、SIM 标识和推送令牌。

## 标识与用途

| 标识 | 来源 | 代码中的用途 | 风险与边界 |
| --- | --- | --- | --- |
| Cloud ID | `SHA-1(android_id + 固定盐)`，再附加 Android userId | 作为设备标识原值，并写入名字具有误导性的 `cloudsp_imei` | 稳定、可关联；不是原始 IMEI |
| `cloudsp_devId` | 对上述设备标识再次 SHA-1/Base64 并截断 | 大多数查找设备请求的设备键 | 稳定、可长期关联 |
| FID / TZID | 软件注册响应或 MTService/TEE | 挑战、签名、绑定、状态查询 | 最强设备身份；伪造会破坏绑定和远程命令 |
| SN | `Build.SERIAL` | 注册请求 `cloudsp_sn` | 当前 ADB 序列已表现为真实值，需单独确认 SN 屏蔽模块 |
| WLAN MAC | `NetworkInterface` 中名称含 `wlan` 的硬件地址 | 注册请求 `cloudsp_mac` | 可能是硬件 MAC；系统应用权限比普通应用更高 |
| ICCID / IMSI | MIUI Telephony 的 SIM 序列号或订户 ID | SIM 变化判断；与 phone ticket 组成 `cloudsp_phoneInfo` | 可关联 SIM 和运营商账户；上传路径受电话权限和激活状态约束 |
| 小米账号 ID | Android 账号名、`userId` / `cUserId` | 账号认证和设备归属 | 属于账户身份；伪造会使服务认证失败 |
| FCM token / MiPush regId | Firebase Messaging / 小米推送 | 远程命令送达 | 稳定的应用安装/设备推送标识 |
| OAID / GAID / OneTrack instanceId | 匿名 ID Provider、Google 广告服务、OneTrack 本地实例 | 统计事件公共参数 | 可跨事件关联；与查找设备核心绑定不同 |
| 离线查找标识 | FID、根密钥、轮换 EID/FCSN 信息 | 蓝牙/UWB 离线发现 | 对外广播值会轮换，但服务端可通过密钥体系关联 |

## 当前设备的实际分支

Android 17 满足“限制 IMEI”条件，`haotian` 不在旧机型 OAID 白名单，因此核心设备标识
默认走：

```text
Settings.Secure.android_id
  → SHA-1(android_id + 固定盐) + @AndroidUserId
  → Cloud ID
  → 再次 SHA-1/Base64 截断
  → cloudsp_devId
```

注册接口同时发送：

```text
cloudsp_devId
cloudsp_devType
cloudsp_model
cloudsp_keystoreType
cloudsp_sn
cloudsp_mac
cloudsp_imei   # 当前分支实际装的是 Cloud ID，不是蜂窝 IMEI
```

## SIM、定位和统计

- `cloudsp_phoneInfo` 可包含 phone ticket 与 SIM 标识；当前 PackageManager 显示普通
  `READ_PHONE_STATE` 未授予，因此对应定时上传任务会先退出。不过应用已获授
  `READ_PRIVILEGED_PHONE_STATE`，其他私有电话接口仍需防护。
- 远程定位路径可采集经纬度、精度、基站 MCC/MNC/LAC/CID 以及 Wi-Fi BSSID/SSID。
  当前普通精确/后台定位权限显示未授予，但应用能管理安全设置和 AppOps，远程命令路径
  还会尝试临时启用定位服务，因此不能据此断言定位永远不可用。
- 查找设备用 OneTrack App ID `31000401846` 初始化统计。Builder 默认开启 GAID、IMEI、
  IMSI；事件公共头还包含 OAID、Android ID 和 OneTrack instanceId。现有 IMEI 模块只
  覆盖其中的 IMEI/MEID，不覆盖 OAID、GAID、Android ID 或 IMSI。

## 可安全隐藏与高破坏性项目

相对适合由隐私模块按应用伪造：Android ID/Cloud ID、OAID、GAID、OneTrack instanceId、
SN、WLAN MAC、IMSI/ICCID，以及统计 SDK 的哈希封装。所有同一调用链必须返回一致值，
否则会产生异常设备画像。

不应在没有迁移方案时直接伪造：小米账号 ID、serviceToken、FID、设备签名私钥、
离线查找根密钥和推送 token。它们是认证或命令送达凭据，修改会导致绑定、定位、锁定、
解锁或离线查找失效。

v1.4.0 严格隐私模式按用户要求保留账号 ID/serviceToken，但仍伪装 FID 与推送 token，
并清空定位、Wi-Fi、基站和应用清单结果，同时禁用 OneTrack 事件上传。因此上述查找设备
能力失效属于预期行为。

## 主要证据位置

- `p039q1/f.java`：设备信息对象、SN、WLAN MAC、型号。
- `H/f.java` 与 `CloudIdProvider.java`：Cloud ID / Android ID 派生。
- `p047u1/c.java`：限制 IMEI 分支和 SIM ICCID/IMSI 读取。
- `C0/q.java`：注册、推送 token、定位辅助数据和 SIM 信息请求参数。
- `p049v0/b.java`、`com/xiaomi/onetrack/Configuration.java`：OneTrack 初始化与默认开关。
- `com/xiaomi/onetrack/f/b.java`、`api/h.java`：OAID、GAID、Android ID、IMEI、IMSI 统计字段。

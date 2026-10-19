# HyperOS 4 系统 IMEI 读取面审计

设备环境：Android 17 / HyperOS 4。扫描对象为当前设备安装的 420 个系统包。

## 筛选方法

1. 解出每个系统 APK 的 `classes*.dex`，查找 `getImei`、`getMeid`、
   `MiuiTelephonyManager`、`IPhoneSubInfo` 及已知 IMEI 属性名。
2. 对命中包读取 PackageManager 的实际授权状态。
3. 仅将确实命中读取逻辑且获授 `READ_PRIVILEGED_PHONE_STATE`、
   `READ_PHONE_STATE` 或相关标识权限的包加入推荐作用域。

## 已确认的主要读取链

- 系统设置：`ExtTelephonyManager.getImeiInfo()` → `QtiImeiInfo.getImei()`。
- AOSP 电话服务：`PhoneInterfaceManager`、`PhoneSubInfoController`、
  `GsmCdmaPhone`。
- HyperOS 电话服务：`MiuiPhoneInterfaceManager`、`ImeiMeidSource`、
  `MiuiTelephonyManagerStub`。
- Qualcomm 服务：`ExtTelephonyServiceImpl.getImeiInfo()`、
  `QtiPrimaryImeiHandler.getImeiInfo()`。
- 安全中心：`TelephonyUtil.getImei()`、`DeviceUtil.getImeiMd5()`。
- PowerKeeper：`DeviceUtil.getImei/getImei1/getImei2()`。
- 小米账号：`DeviceInfoProvider.getImei1/getImei2` 及 MD5 变体、
  `PhoneInfo/PhoneUtil.getImei()`。
- CIT：`CitVersionCheckActivity.getIMEI()`，并带有属性读取回退。

## Hook 策略

- 电话服务端先替换字符串结果，阻止任何 Binder 客户端取得真值。
- Qualcomm `QtiImeiInfo[]` 在服务端重建成双卡伪对象。
- 客户端同时 Hook AOSP、HyperOS、Qualcomm 代理，覆盖 OEM 私有路径。
- 双卡列表、字符串数组、IMEI MD5 和缓存封装返回与随机伪值一致的结果。
- 属性层由 KernelSU 模块持续守卫，处理 Java/原生属性读取回退。

## 推荐作用域

权限与代码双重确认的高风险子集共 59 个包。v1.3.0 按用户要求将当前设备全部
440 个已安装包写入 Manifest `xposedscope`，并同时写入现代格式
`META-INF/xposed/scope.list`；下面是高风险子集的主要分组：

- 核心电话栈：Android 系统、电话服务、QTI Phone、HyperPhone、IMS、
  TelephonyProvider、eSIM、VsimCore。
- 设置与诊断：系统设置、小米设置、CIT、Qualcomm DeviceInfo、
  DeviceDiagnostics、设备信息二维码、ModemTestBox。
- 安全与设备管理：查找设备、安全中心、安全核心、ManagedProvisioning、
  DeviceLockController、系统更新。
- 账号与云：小米账号、云备份、Google Play 服务、Play 商店、SIM 激活服务。
- 通信应用：电话、短信、联系人、拨号器、通话界面、Google Messages。
- 其他实际获权组件：PowerKeeper、Analytics、AOD、HyperComm、Mirror、
  Joyose、SystemUI 插件等。

默认范围是针对当前设备生成的；系统更新或安装新应用后应重新生成或手动补勾。

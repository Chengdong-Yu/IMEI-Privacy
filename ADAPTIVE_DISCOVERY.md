# 1.4.2 自动发现设计

本模块不通过包版本号、系统版本号或已知混淆符号表选择 Hook。
`ApplicationInfo.sourceDir/splitSourceDirs` 只用于获取当前安装 APK 的位置；
扫描由纯 Java dexlib2 执行，不加载或执行被扫描应用的方法。

## 如何识别

| 目标 | 证据 | 避免误判 |
| --- | --- | --- |
| 设备信息 | `toString` 中设备 ID、SN、MAC、型号标签与字段读指令；Context 构造和缓存工厂 | 不是“有五个 String 字段就算”；格式参数按数组寄存器绑定，映射缺失/重复拒绝 |
| 核心 ID/MAC 读取 | 构造器写入已识别字段的数据流 | 分支合并追踪来源，不按方法名区分角色 |
| FID getter | 返回值流向 `cloudsp_fid/cloudsp_tzid` 的 Map.put；所在契约有 byte[] 签名接口 | 只匹配被使用的 getter 及实现，不拦截同接口的账号/Host getter，也不改签名函数 |
| FID 缓存 | device_fid 协议键、SharedPreferences 读取、Context→String 签名 | 不硬编码缓存类名，不修改磁盘凭据 |
| Cloud ID Provider | ContentProvider 继承、Bundle 调用签名、协议键 | 原方法执行后替换成功结果，保留权限检查 |
| 请求构造 | cloudsp 协议键、两个 Map 参数、JSONObject 返回 | 精确校验参数，复制替换，保留账号参数 |
| HTTP 发送 | URL.openConnection/HttpURLConnection I/O 与对象关联、发送签名、包装方法委托关系 | 单有相同方法签名而未委托到发送器的对象不拦截 |

发现结果只包含类型、字段、方法签名和用途，不保存个人标识。Hook 安装前还会用当前
ClassLoader 解析和核对成员类型；同一进程/类加载器不会重复叠加 Hook。无需旧版本符号缓存，
应用更新后的新进程会重新扫描当前 APK。OneTrack 只保留公开控制 API 的 Hook，移除猜测
单字母 SDK 内部方法的逻辑；框架 ID 读取与发送端净化继续生效。

扫描失败时仍保留框架层防护，但记录 WARNING；没有找到关键路径时不会报告完整兼容。
成功发现和成功安装也不等于已验证实际命中；后者需要看首次命中日志和运行时验证。

## 回归验证

`DexSemanticDiscoveryTest` 构建内存 DEX，验证完整改名、字段读取乱序、分支合并、
多 DEX、FID 与账号区分、错误契约拒绝、同签名 HTTP 反例、Provider 继承和协议键。
`AdaptiveDiscoveryApkTest` 使用可选外部 APK，调用生产使用的同一入口，不带版本分支。

Windows 本地实际验证命令（路径作为测试输入，不进入模块）：

```powershell
.\build.ps1 -CompatibilityApks @(
    '.\build\device-apps\com.xiaomi.finddevice.apk',
    '.\build\os4-compat-check\finddevice.apk'
)
```

两份样本发现情况：

| APK 样本 | 设备信息对象 | FID getter | FID 缓存 | Cloud Provider | 请求入口 | HTTP 入口 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| global 20.0.8 | 1 | 2 | 0 | 1 | 3 | 14 |
| CN 20.15.62 | 1 | 2 | 1 | 1 | 6 | 14 |

这些版本号只用于说明回归样本，不参与生产代码判断。尚未安装本次构建到手机，未触发定位、
绑定、锁定、解锁或其他远程命令，也没有添加防火墙规则或直接改写系统分区。

## 后续版本的边界

改名、字段重排、类似编译器优化和多 DEX 不需要增加版本表。语义标签被彻底删除、字段用途
变化、新传输栈/新协议、动态加载 DEX、native/Binder 代理和不支持的新 DEX 格式，仍可能使
部分路径无法发现。自动发现不是对未来任意重写或所有个人信息的绝对保证；未知路径必须
显式报告，不能靠“安装成功”推断隐私已验证。账号和云端已有历史数据不会自动清除。

# CarDash — 深蓝车机桥 + iPhone 横屏仪表盘

把车机的真实数据（**档位 / 车速 / 电量 / 续航 / 总里程 / 音乐 / 专辑封面 / 导航**）
送到一台横屏常亮的 iPhone 上当仪表盘用。

实现方式是**把一段桥接代码注入进你车机上已有的第三方桌面 `D.apk`
（`com.deepalhome.launcher`）**，用它的身份、它的权限、它的数据通道，
通过 HTTP 把状态吐给 iPhone。

```
┌──────────────┐   同一个进程   ┌──────────────────┐   HTTP/JSON   ┌──────────────┐
│  D.apk 桌面  │ ◀───────────▶ │  CarDash 注入层   │ ◀───────────▶ │  iPhone 仪表盘 │
│ (system uid) │               │  (classes2.dex)  │  :8765 轮询    │  横屏不息屏    │
└──────┬───────┘               └──────────────────┘               └──────────────┘
       │ 反射调用
       ▼
┌──────────────────────────────────────────────┐
│ 腾讯梧桐车联 虚拟车辆属性体系（D.apk 自带客户端）  │
│  com.openos.virtualcar  虚拟车辆属性             │
│  com.tinnove.polymericservice  聚合服务(按别名)  │
│  com.deepalhome.launcher.util.CarS05InfoUtil   │
└──────────────────────────────────────────────┘
```

---

## 一、关键前提：车机的平台签名私钥是公开的

你车机的设备信息里有这么两行：

```
系统版本: qti/msmnile_gvmq/msmnile_gvmq:11/RQ3A.211001.001/eng.taobao.20260706.000620:user/test-keys
自身签名: md5: 8ddb342f2da5408402d7568af21e29f9
```

对照验证的结果：

| 对象 | 证书 md5 |
|---|---|
| 车机系统签名 | `8ddb342f2da5408402d7568af21e29f9` |
| `D.apk` 的签名证书 | `8ddb342f2da5408402d7568af21e29f9` ✅ |
| AOSP 公开的 `platform.x509.pem` | `8ddb342f2da5408402d7568af21e29f9` ✅ |

也就是说车机用的是 AOSP 公开的 `platform` 密钥签名，**私钥在 AOSP 源码里就能拿到**
（`build/target/product/security/platform.pk8`）。由此得到三件事：

1. 改 `D.apk` 后可以用**同一把密钥**重新签名；
2. 签名没变 ⇒ `android:sharedUserId="android.uid.system"` 继续有效 ⇒ **system 权限一个不丢**；
3. `versionCode` 一加就能**直接覆盖安装，不用卸载，桌面数据也不丢**。

> ⚠️ 别被证书 Subject 骗到：`testkey` 和 `platform` 的 Subject 一模一样，只有公钥不同。
> `D.apk` 用的是 `platform`，不是 `testkey`。

---

## 二、取数通道：走了三段弯路才找对

这一节是这个项目最值得记下来的部分。**同一个数据，前后试了三条路，前两条都不通。**

### ❌ 第一条：解析 logcat（错）

分析 `D.apk` 时发现它声明了 `READ_LOGS`，并且代码里带着这样一条正则：

```text
(?:receive|(?:\d+\s+)?send)\s+(.+?),\s+value\s*:\s*CarPropertyValue\{id=(0x[0-9a-fA-F]+).*?value=(.*?),\s*time=.*?ext=(.*)\}
```

于是照着它去解析车机 logcat。**实测抓到的日志是这样的：**

```
含 CarPropertyValue 的行 : 2
【原始日志样本】
  W/System.err( 2161): at com.wt.tinnovecoreservice.virtualcar.policy.property.
                       VirtualCarPropertyServiceAdapterPolicy.getCarPropertyValueFromCarService(...)
```

两条都是异常堆栈，**这台车压根不往 logcat 打车辆信号**。
那条正则和 `Ez6VehicleLogParser` 是给别的车型（EZ6）用的。此路不通。

### ❌ 第二条：标准 AAOS VHAL（错）

试 `android.car.Car` + `CarPropertyManager`。车机上 `android.car.Car` 存在，
但连 `android.car.hardware.property.CarPropertyValue` 都加载不到 ——
这台车不是标准 AAOS VHAL 实现。

### ✅ 第三条：厂商虚拟车辆 SDK（对）

堆栈里泄露出了真名：**`com.wt.tinnovecoreservice`** = 腾讯梧桐车联。
顺着这条线用自研的 DEX 反查工具（`tools/dex_class.py`，本机没有 jadx）挖下去，
发现 **D.apk 里已经带了完整的厂商客户端**：

```java
// 虚拟车辆属性（和标准 AAOS 的模型很像）
public final class com.openos.virtualcar.VirtualCarPropertyManager {
    public Object  getValue(int propertyId, int areaId);
    public void    register(int[] propertyIds, VirtualCarPropertyCallBack cb);
    public void    setValue(int propertyId, int areaId, Number value);
    public boolean isSupport();
}

// 聚合服务：按字符串别名取值
public interface com.tinnove.polymericservice.IPolymericService {
    WTResultModel callMethod(WTRequestModel req);
    void asynCallMethod(WTRequestModel req, IPolymericListener listener);
}

// D.apk 自己的封装层 —— 最有用的一层
public final class com.deepalhome.launcher.util.CarS05InfoUtil {
    Object  psGetValueSync(String alias);              // 按别名取值
    Object  readVirtualCarValue(VirtualCarPropertyManager mgr, int id, int areaId);
    void    startMonitor();
    void    bindVirtualCarPropertyManager();          // 私有，可反射调用
    void    registerVirtualCarCallbacks();            // 私有
    void    connectPolymericService(boolean);         // 私有
    static CacheCarS05Info cacheCarS05Info;           // 它自己的缓存
}
```

**关键点：这些类就在 `D.apk` 自己的 `classes.dex` 里。**
我们注入的代码和它**同进程、同 ClassLoader**，所以直接反射调用即可：

- 不需要厂商 SDK 的 jar
- 不需要任何 `android.car.permission.*`
- 不需要 `READ_LOGS`

### 别名的真相

一开始以为信号名是 `EnergyInfo/SocPercent`、`DrivingInfo/Gear` 这种 ——
**那些是厂商属性表里的名字，不是取值用的 key**，`psGetValueSync` 对全部 1276 个
都返回 `null`。

真正的 key 是从 `CarS05InfoUtil` 的静态字段里挖出来的（`/scan` 能看到）：

```
psOnlyAliases            = [..., vc_alias_vehicle_gear, ...]
virtualCarProtectedAliases = [vc_alias_vehicle_speed, vc_alias_journey_all_distance,
                              vc_alias_e_dte, vc_alias_left_ev_dte, vc_alias_disp_dte, ...]
virtualCarSensorIds      = [826279334, 826279375, ... 共 21 个]
virtualCarHvacIds        = [945815826, 895484165, 893387025]
virtualCarCabinIds       = [826279306, 826279169, 910164753, 826278676]
```

一共 **37 个 `vc_alias_*`**，全表见 `inject/src/com/cardash/inject/Aliases.java`（1276 条）。

### 最终实际使用的两条路

| 通道 | 实现 | 用途 |
|---|---|---|
| **`cacheCarS05Info` 静态字段** | 纯字段读取，**绝不阻塞** | 档位 / 车速 / 总里程 / 续航 |
| **`psGetValueSync(alias)`** | 阻塞式 IPC | 补缓存里没有的字段 |

轮询顺序**必须是缓存优先**：`psGetValueSync` 万一卡住，也只是这一轮补不到，
上一轮缓存读到的值仍然有效。

缓存字段 → 面板的映射：

| `CacheCarS05Info` 字段 | 含义 | 单位 |
|---|---|---|
| `gearMode` | 档位 | `P` / `R` / `N` / `D` |
| `speed` | 车速 | **km/h**（形如 `"0 km/h"`，代码里抽数字） |
| `totalDistance` | 总里程 | km |
| `socRemainRange` | 电续航 | km |
| `totalRemainRange` / `oilRemainRange` | 总续航 / 油续航 | km |
| `tirePressure*` | 胎压 | bar |
| `doorStatus*` | 车门 | 关闭 / 开启 |
| `driveStyle` | 驾驶模式 | 自动 / 自定义 … |

### 电量百分比：车机不给，用续航折算

37 个 `vc_alias_*` 里**没有 SOC 百分比**，只有一堆续航（DTE = Distance To Empty）。
所以：

```
电量% = 剩余续航 ÷ 满电续航 × 100
```

满电续航默认 **500 km**，运行时可以改（持久化到 SharedPreferences）：

```
http://<车机IP>:8765/setfull?km=515
```

> D.apk 内部有两个常量 `ELECTRIC_AMOUNT_SCALE = 0.1`、
> `VIRTUAL_CAR_ELECTRIC_AMOUNT_OFFSET = 31.5`，说明它拿到的是原始值需要换算。
> 如果 `/scan` 的「属性 ID 逐个取值」里能看到原始电量，可以改成直接读，比折算准。

---

## 三、其余数据的来源

| 数据 | 取法 |
|---|---|
| **档位 / 车速 / 电量 / 续航 / 总里程** | 见上一节 |
| **音乐 + 专辑封面** | `MediaSessionManager.getActiveSessions()` 读 `MediaMetadata`；封面取 `METADATA_KEY_ALBUM_ART`，缩到 240px 压成 JPEG base64 下发 |
| **导航** | 通知解析（`NotificationListenerService`）+ 无障碍读屏兜底 |
| **海拔** | iPhone 本机 GPS（车机不发这个信号） |

**导航**这块比较特殊：深蓝的导航是**腾讯梧桐导航**（`com.tinnove.wecarnavi`）。
车机上导航可能画在系统层而不是普通 Android 应用里，所以同时保留两条路：

1. 通知栏（结构化，优先）
2. 无障碍读屏 —— 现在会**统计所有来源包名**（`/logcat` 的「无障碍事件来源统计」），
   并对白名单外的应用按 1.5 秒限流也扫一遍，避免因为包名猜错而完全失效

知道包名又不想重新打包时，直接加：

```
http://<车机IP>:8765/setnav?pkg=com.xxx
```

---

## 四、实现方式：直接改写 APK 里的二进制清单

**不重打包、不重编资源。** `tools/axml.py` 是一个自研的 AXML 读写器：

1. 解析 `AndroidManifest.xml` 的字符串池、资源映射表、元素树；
2. 往字符串池**尾部追加**（已有索引全部不变，所以所有既有引用都保持有效）；
3. 在 `</application>` 前插入组件、补 `uses-permission`、改 `versionCode` / `versionName`；
4. 整体重新编码，并把编译好的 `classes2.dex` 一起写进新的 zip。

自检机制（每次本地构建都会先跑）：把原始清单解析后**原样重编**，要求逐字节一致，
否则直接中止。实测结果 —— 写入器输出与 aapt2 编译结果**完全相同**。

最终产物的改动范围：

```
新增条目: ['META-INF/PLATFORM.RSA', 'META-INF/PLATFORM.SF', 'classes2.dex']
删除条目: ['META-INF/DEV.RSA', 'META-INF/DEV.SF']
内容变化: ['AndroidManifest.xml', 'META-INF/MANIFEST.MF']
```

原包 1655 个文件里，**1653 个逐字节未改动**（`classes.dex`、`resources.arsc` 全部原封不动）。

注入的 5 个组件：

| 组件 | 类型 | 作用 |
|---|---|---|
| `BootProvider` | ContentProvider | **进程创建时自动拉起桥接**（最早时机，比 Application 还早） |
| `BootReceiver` | BroadcastReceiver | 开机 / 装完新包立刻拉起 |
| `BridgeService` | 前台服务 | 跑 HTTP 服务器（`:8765`）与各采集器 |
| `NavListenerService` | NotificationListenerService | 读音乐会话 + 解析导航通知 |
| `NaviAccessibilityService` | AccessibilityService | 读屏，抓导航界面上的转向 / 路名 / 距离 |

无障碍服务**没有新增 XML 资源**：新增资源要重编 `resources.arsc`（apktool 那条路已
验证会挂），而 `D.apk` 自己就声明了一份无障碍配置，条件正好够用
（`typeAllMask`、`canRetrieveWindowContent=true`、`flagRetrieveInteractiveWindows`，
且**没有 `packageNames` 限制**），所以直接复用了它的资源 ID，注入时从原清单里现读现用。

### 刻意不做的事

- **不给清单补声明 `D.apk` 原本没有的权限。** 它启动时会自检权限，缺权限就弹
  「一键授权」向导。而 `POST_NOTIFICATIONS` 在 Android 11 上不存在，
  `checkSelfPermission` 必然返回 DENIED —— 补上去会导致**每次开机都弹授权页**。
  实测过：原包 40 条权限，产物也是 40 条，一字不差。

---

## 五、构建

APK **只在本地构建**（车机端改一行就能立刻重出，走 CI 反而慢）。

### 1. 装工具链（只需一次，约 260MB）

```bash
python tools/setup_env.py
```

下载 JDK 17、Android build-tools 34、platform 34，以及 AOSP 公开的 `platform`
签名密钥，并校验密钥 md5 是否等于车机系统签名。默认落到仓库同级的 `_env`。

### 2. 构建

```bash
python tools/build_local.py <原始 D.apk 路径> <_env 目录> [versionCode] [versionName]
```

例如：

```bash
python tools/build_local.py ..\D.apk ..\_env 204 v26.0521-cd9
```

产物在 `dist/Deepal-CarDash.apk`。

### 3. 脚本都检查了什么

- **AXML 写入器自检** —— 原始清单解析后原样重编必须逐字节一致，否则直接中止
- **aapt2 解析** —— 用和车机同款的解析器验证产物清单合法
- **签名证书校验** —— 产物证书 SHA-256 必须等于车机系统签名，不等就中止
- **改动范围校验** —— 打印新增 / 删除 / 内容变化的条目，确认只动了清单

### 4. 模拟器测试版

Android 14+ 禁止非预装应用加入 `android.uid.system`，正式包在模拟器上装不上。
加 `--no-shared-uid` 出测试版（**只用于模拟器，别装车机**）：

```bash
python tools/build_local.py ..\D.apk ..\_env 204 v26.0521-emu --no-shared-uid
```

---

## 六、HTTP 接口一览

车机端是个极简 HTTP 服务器（短连接、线程每连接），默认端口 **8765**。

| 路径 | 说明 |
|---|---|
| `/` | 给人看的自检页面（实时数据表） |
| `/state` | **iPhone 轮询的就是它**，JSON 快照 |
| `/diag` | 完整诊断文本：网络接口、实时数据、每个字段的来源 |
| `/scan` | 厂商属性扫描：静态字段 + 21 个属性 ID 逐个取值 + 别名全量扫描 |
| `/logcat` | logcat 抽样 + 导航诊断 + 无障碍事件来源统计 |
| `/log` | 桥接运行日志（不用再去文件管理器翻 `bridge.log`） |
| `/setfull?km=500` | 设满电续航（折算电量% 用），持久化 |
| `/setnav?pkg=com.xxx` | 追加导航包名白名单，持久化 |
| `/health` `/hi` | 极短探测，给 iPhone 的自动扫描用 |

`/scan` 是**异步**的：第一次请求启动后台扫描并立刻返回，之后再刷新看累积结果。
（同步扫 1276 个别名会把 HTTP 请求卡死 —— 实测过。）

**车机上没有浏览器也完全没关系** —— 这些页面在 iPhone Safari 上打开就行，
或者用 App 里的「车机诊断」（见下）。

---

## 七、车机端使用

### 装

在车机上安装 `Deepal-CarDash-<N>.apk`。签名与系统一致、`versionCode` 递增，
**正常情况下直接覆盖安装，无需卸载**。

装完通知栏会出现：

```
CarDash 桥接运行中
http://192.168.x.x:8765 · 车辆信号 128 条 · 读屏 enabled
```

**记下这个地址。**

### 授权

通常**都不用手动做**，桥接启动时会自己注册（因为以 system 身份运行，
天然持有 `WRITE_SECURE_SETTINGS`）：

| 权限 | 自动处理 | 失败时的表现 |
|---|---|---|
| 无障碍 | 写 `enabled_accessibility_services` | `/state` 里 `src.a11y = need-manual` |
| 通知使用权 | 写 `enabled_notification_listeners` | `/state` 里 `src.notify = need-manual` |
| 前台服务 | `startForeground` | 失败也不影响 HTTP，只是进程可能被系统冻结 |

日志里能看到每一步的结果，例如 `无障碍自注册完成: enabled`、`通知使用权已自动写入`。

### 自检

浏览器打开 `http://<车机IP>:8765/` 能看到实时数据表就通了，
原始 JSON 在 `/state`，出问题先看 `/diag`。

---

## 八、iPhone 端使用

1. 去 GitHub **Releases** 下载 `CarDash-unsigned.ipa`，自行签名安装
2. 首次启动请求 **本地网络权限**（连车机）和 **定位权限**（只用于海拔），都点允许
   > 本地网络弹窗只弹一次，点错了去 `设置 → 隐私与安全性 → 本地网络` 打开
3. **点屏幕任意位置** → 设置页 → 「车机地址」填 `192.168.x.x`
   （端口默认 8765 不用带；也可以留空让它自动扫网段）
4. 屏幕底部出现绿点「车机已连接」即成功

之后每次上车都会自动接上。**横屏锁定 + 永不息屏 + 亮度拉满。**

### 界面

```
 09/22 16:29                                    ⛰ 515m      ← 日期时间 / 海拔(GPS)
┌──────────┐                                                 
│ 封面 ♪   │              0                   暂无导航信息    ← 音乐 / 车速 / 导航
│ 歌名      │           km/h                  请在车机开启导航
│ 歌手      │                                                 
└──────────┘                                                 
 🔋94% 471 km                            P R N D   2,453 km   ← 电量·续航 / 档位·总里程
```

**车速只取车机**，不再退回 iPhone 的 GPS —— 两者在高架、隧道、地库里会互相打架。
车机断连时显示 `--` 而不是另一个来源的数字，避免误读。

### 设置页里的「车机诊断」

车机上没有浏览器，所以把这几个接口做进了 App：

| 按钮 | 拉取的接口 |
|---|---|
| logcat 取数诊断 | `/logcat` |
| 厂商属性扫描 | `/scan` |
| 车机运行状态 | `/diag` |
| 桥接运行日志 | `/log` |

打开后点右上角「复制」就能整段发出来。

---

## 九、`/state` 字段说明

```json
{
  "v": 1,
  "ts": 1790062036027,
  "speed": 0,              // km/h，只来自车机
  "gear": "P",             // P / R / N / D
  "soc": 94.2,             // 电量%，由「续航 ÷ 满电续航」折算
  "range": 471,            // 剩余续航 km
  "odometer": 2453.2,      // 总里程 km
  "altitude": null,        // 车机不发，iPhone 用本机 GPS
  "music": {
    "title": "全部都是你",
    "artist": "开心宝贝",
    "cover": "<base64 JPEG，240px>",
    "playing": true,
    "position": 12.3,
    "duration": 210.0
  },
  "nav": { "active": false },
  "src": {                 // ← 排查全靠它
    "vendor": "cache-only",
    "cache": "present",
    "gear": "cache:gearMode",
    "speed": "cache:speed",
    "odometer": "cache:totalDistance",
    "range": "cache:socRemainRange",
    "soc": "derived:471/500km",
    "cover": "4412B",
    "a11y": "enabled",
    "notify": "auto-enabled"
  }
}
```

`src` 里每一项都写明**这个值是谁给的**：
`cache:xxx` = D.apk 缓存，`vendor:xxx` = 按别名取的，`derived:` = 折算的，
`vhal` = 标准 VHAL（本车走不通）。任何一个字段显示 `--` 时，先看这里。

---

## 十、风险与回滚

1. **先留好原始 `D.apk`**，放手机或 U 盘备用。
2. 桥接代码全部兜底：`BootProvider`、`BridgeRuntime`、`BridgeService` 的启动
   都包在 `try/catch` 里，任何一步失败只是桥接不工作，**不会把桌面搞崩**。
3. 反射调用全部包 `try/catch`，厂商 SDK 不在（比如模拟器上）也只是取不到数。
4. 除清单外原包一个字节没动，`classes.dex` 逐字节一致 ⇒ 行为与原来完全一致。
5. **万一桌面起不来**：车机原有桌面会被系统接管，进去后用文件管理器把原始
   `D.apk` 装回去即可 —— 同包名同签名，直接覆盖。
6. **想彻底移除桥接**：装回原始 `D.apk` 就干净了。

---

## 十一、排查表

**前提：先确认车机装的是最新版 APK。** 下面是按「先看什么」排序的。

| 现象 | 先看 | 原因 / 处理 |
|---|---|---|
| 页面打不开 | 通知栏地址 | 地址不对或不在同一网段。车机开热点、手机连它 |
| 全部字段 `--` | `/diag` 的 `carError` | 厂商通道没连上。`vendor` 应该是 `connected` |
| 只有档位不更新 | `/scan` 的 `ps` / `vcarMgr` | 档位只走聚合服务，`ps=off` 就永远是旧值。桥接会每 2 秒自动补绑，最多 8 次 |
| 电量 `--` | `/state` 的 `src.soc` | 应为 `derived:471/500km`。数字不对就用 `/setfull?km=N` 校准 |
| 音乐 `--` | `/state` 的 `src.music` | 应为 `mediasession:xxx`。`need-manual` 说明通知使用权没开 |
| 封面不显示 | `/state` 的 `src.cover` | 有 `xxxx B` 说明车机端取到了，不显示就是 IPA 太旧 |
| 导航「暂无导航信息」 | `/logcat` 的「无障碍事件来源统计」 | 找车机导航的真实包名，用 `/setnav?pkg=` 加上 |
| 车速一直是 `--` | `/state` 的 `src.speed` | 只取车机，不再兜底 GPS。`--` 说明车机断连了 |
| `/scan` 进度不动 | —— | `psGetValueSync` 阻塞了。它会一直卡在某个别名上，换 `/diag` 看已有的值 |
| 无障碍没自动开启 | `/state` 的 `src.a11y` | `need-manual` 时到车机 `设置 → 无障碍` 手动勾选 |
| 通知栏没有通知 | —— | Android 13+ 需要 `POST_NOTIFICATIONS`（车机是 Android 11，不受影响） |
| 模拟器装不上 | —— | Android 14+ 禁止非预装应用加入 `android.uid.system`。用 `--no-shared-uid` 出测试版 |
| 车机放着不动就断连 | —— | Android 14+ 的缓存应用冻结。车机是 Android 11，不受影响；模拟器上用 `settings put global cached_apps_freezer disabled` |
| IPA 装不上 | —— | 未签名包需要先自签；免费 Apple ID 签的有效期 7 天 |

---

## 十二、改动速查

| 想改什么 | 改哪 |
|---|---|
| 端口（默认 8765） | `BridgeRuntime.java` 的 `PORT` |
| 车辆信号映射 | `VendorSignals.java` 的 `vc_alias_*` 常量 + `apply()` + `StateHub.toJson()` + iOS `CarSnapshot` |
| 满电续航默认值 | `VendorSignals.java` 的 `DEFAULT_FULL` |
| 导航包名白名单 | `NaviSignals.java` 的 `NAV_PACKAGES`（或运行时 `/setnav`） |
| 厂商属性别名全表 | `Aliases.java`（由 `_tools/gen_aliases.py` 从 D.apk 字符串池生成） |
| 音乐来源优先级 | `MediaSignals.java` 的 `candidates` 数组 |
| 封面尺寸 / 质量 | `MediaSignals.java` 的 `max`（240）与 `compress(..., 70, ...)` |
| 注入的组件 | `tools/inject_manifest.py` 的 `build_components()` |
| 读取频率 | 车机：`VendorSignals.startPolling()`；iPhone：`DashboardModel.start()` 的 `0.2` |
| iPhone 界面排版 | `ios/CarDash/UI/Panels.swift`、`DashboardView.swift` |
| 背景配色 | `ios/CarDash/UI/BackgroundScene.swift` |

---

## 十三、协作 / 迭代流程

```
改代码 ──┬─ Android（APK）→ 本地 python tools/build_local.py → 直接把 apk 装车机
         └─ iOS（IPA）    → git push → GitHub Actions(macos) → Releases 下载
```

- **APK 不进 Git**：`D.apk` 是第三方应用，签名密钥更不能入库。仓库建议设成 **Private**。
- 工作流只在 `ios/**` 或它自己被改动时触发，也可以到
  **Actions → Build iOS IPA → Run workflow** 手动重跑。
- 产物位置：**Releases → `CarDash IPA build N` → `CarDash-unsigned.ipa`**。

### 本地辅助脚本（`_tools/`，不在仓库里）

Windows 上没有 jadx / 没有 Swift 编译器，所以写了几个替代品：

| 脚本 | 用途 |
|---|---|
| `dex_class.py` | 自研 DEX 反查：类的字段 / 方法签名 / 静态常量。厂商 SDK 的 API 就是这么挖出来的 |
| `dex_strings.py` | 导出 DEX 字符串池（做关键字搜索用） |
| `check_swift.py` | Swift 文件括号配平 + 顶层声明检查，弥补本机无法编译 Swift |
| `gen_aliases.py` | 从别名表生成 `Aliases.java` |
| `verify_injected_manifest.py` | 校验产物清单里的注入组件是否完整合法 |

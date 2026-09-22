# CarDash — Deepal 车机桥 + iPhone 横屏仪表盘

把车机的真实数据（档位 / 车速 / 电量 / 续航 / 总里程 / 音乐 / 导航）送到一台横屏常亮的
iPhone 上当仪表盘用。

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

> 注意别被证书 Subject 骗到：`testkey` 和 `platform` 的 Subject 一模一样，只有公钥不同。
> `D.apk` 用的是 `platform`，不是 `testkey`。

---

## 二、D.apk 是怎么拿到这些数据的

分析 `com.deepalhome.launcher`（内部版本 v26.0516 191）后，三条链路：

| 数据 | 取法 |
|---|---|
| **档位 / 车速 / 电量 / 续航 / 总里程** | 解析**系统 logcat** 里的 VHAL 日志。它一条 `android.car.permission.*` 都没申请，靠 `sharedUserId=android.uid.system` 拿到 `READ_LOGS`，再正则匹配车机打印的 `CarPropertyValue` |
| **音乐** | `MusicService` 本身就是 `NotificationListenerService`（读通知栏媒体通知）+ `MediaSessionManager` 读 `MediaMetadata`，歌词再向酷狗/网易云/lrclib 拉 |
| **导航** | `AutoNaviAccessibilityService` 无障碍读屏 + 通知解析 |

它自己的日志解析正则是：

```text
(?:receive|(?:\d+\s+)?send)\s+(.+?),\s+value\s*:\s*CarPropertyValue\{id=(0x[0-9a-fA-F]+).*?value=(.*?),\s*time=.*?ext=(.*)\}
```

注入的桥接复用同一条链路 —— 同一个进程、同一套权限、同一份日志。

---

## 三、实现方式：直接改写 APK 里的二进制清单

**不重打包、不重编资源。** `tools/axml.py` 是一个自研的 AXML 读写器：

1. 解析 `AndroidManifest.xml` 的字符串池、资源映射表、元素树；
2. 往字符串池**尾部追加**（已有索引全部不变，所以所有既有引用都保持有效）；
3. 在 `</application>` 前插入 3 个组件、改掉 `versionCode` / `versionName`；
4. 整体重新编码，并把编译好的 `classes2.dex` 一起写进新的 zip。

自检机制（CI 和本地构建都会先跑）：把原始清单解析后**原样重编**，要求逐字节一致，
否则直接中止。实测结果 —— 写入器输出与 aapt2 编译结果**完全相同**。

最终产物的改动范围：

```
新增条目: ['META-INF/PLATFORM.RSA', 'META-INF/PLATFORM.SF', 'classes2.dex']
删除条目: ['META-INF/DEV.RSA', 'META-INF/DEV.SF']
内容变化: ['AndroidManifest.xml', 'META-INF/MANIFEST.MF']
```

原包 1655 个文件里，**1653 个逐字节未改动**。

注入的组件：

| 组件 | 作用 |
|---|---|
| `com.cardash.inject.BootProvider` | ContentProvider，进程创建时自动拉起桥接 |
| `com.cardash.inject.BridgeService` | 前台服务，跑 HTTP 服务器（`:8765`）与采集器 |
| `com.cardash.inject.NavListenerService` | 通知监听，读音乐会话 + 解析导航通知 |

---

## 四、本地构建（不依赖 GitHub）

先装工具链（JDK + Android build-tools + platform，约 260MB）：

```bash
python _tools/setup_env.py        # 见仓库外的工具目录，或参考 CI 里的下载地址
```

然后：

```bash
python tools/build_local.py D.apk <_env 目录>
```

产物在 `dist/Deepal-CarDash.apk`。脚本流程与 GitHub Actions 完全一致，
包含 AXML 自检、签名证书校验、改动范围校验。

---

## 五、上传到 GitHub

CI 只做两件事：**出 IPA**（APK 已经能在本地构建）。

需要上传的（`.github` 必须在仓库根目录）：

```
仓库根目录/
├── .github/workflows/
│   ├── ios-ipa.yml            # macOS 构建 IPA
│   └── patch-deepal-apk.yml   # 云端重新打 APK（可选）
├── ios/                       # iPhone 工程
├── inject/src/                # 桥接 Java 源码
├── tools/                     # 构建脚本
├── D.apk                      # 原始桌面包（打 APK 需要）
└── README.md
```

步骤：GitHub → **New repository** → 建库 →
**Add file → Upload files** → 把上面这些东西拖进去 → Commit。
（`.github` 是隐藏目录，Windows 资源管理器里先「查看 → 显示隐藏的项目」）

Actions 会自动跑：

| 工作流 | 产物 | 位置 |
|---|---|---|
| **Build iOS IPA** | `CarDash-unsigned.ipa` | Releases → `CarDash IPA build N` |
| **Patch Deepal APK** | `Deepal-CarDash.apk` | Releases → `Deepal 仪表盘桥接 APK build N` |

也可以到 **Actions → 选工作流 → Run workflow** 手动重跑。

---

## 六、车机端

### 装

下载 `Deepal-CarDash.apk`，在车机上安装。签名与系统一致、versionCode 从 191 升到 192，
**正常情况下直接覆盖安装，无需卸载**。

装完通知栏会出现：

```
CarDash 桥接运行中
http://192.168.x.x:8765 · 车辆信号 128 条
```

**记下这个地址。**

### 授权（读音乐和导航需要）

`设置 → 应用 → 特殊权限 → 通知使用权`，勾选 **CarDash 桥接**。

> 如果以前给 D.apk 的媒体功能开过通知使用权，音乐可能不用再勾 —— 桥接会优先复用
> 那个已启用的监听服务。

### 自检

浏览器打开 `http://<车机IP>:8765/` 能看到实时数据表就通了，原始 JSON 在 `/state`。

---

## 七、iPhone 端

1. 下载 `CarDash-unsigned.ipa`，自行签名安装
2. 首次启动请求 **定位权限**（车速/海拔/里程）和 **本地网络权限**（连车机），都点允许
   > 本地网络弹窗只弹一次，点错了去 `设置 → 隐私与安全性 → 本地网络` 打开
3. **点屏幕任意位置** → 设置页 → 「车机地址」填 `192.168.x.x`（端口默认 8765，不用带）
4. 屏幕底部出现绿点「车机已连接」即成功

之后每次上车都会自动接上。**横屏锁定 + 永不息屏。**

---

## 八、数据对应关系

| logcat 信号名 | 含义 | 换算 |
|---|---|---|
| `DrivingInfo/Speed` | 车速 | VHAL 原生 m/s → ×3.6 |
| `DrivingInfo/Gear` | 档位 | 位标志：1=N 2=R 4=P 8=D |
| `DrivingInfo/Odometer/Total` | 总里程 | 原生米 → ÷1000 |
| `EnergyInfo/SocPercent` | 电量 | ≤1 视为比例，自动 ×100 |
| `EnergyInfo/RemainingMileage` | 剩余续航 | km，实时值优先于标准值 |
| `EnergyInfo/RemainingMileageStandard` | 标准续航 | km |
| `DrivingInfo/IgnitionState` | 点火状态 | |

信号名认不出来时退回用 VHAL 属性 ID 匹配（`0x11600207` 车速、`0x11400400` 档位、
`0x11600305` 电量、`0x11600309` 续航 …）。另外还有一条 `CarPropertyManager` 直读通道作为补充。

读不到的字段，iPhone 端自动降级：车速/海拔/累计里程用手机 GPS 顶替，其余显示 `--`。
设置页能看到每个字段的来源（`logcat:` / `vhal` / `gps`）。

---

## 九、风险与回滚

1. **先留好原始 `D.apk`**，放手机或 U 盘备用。
2. 桥接代码全部兜底：`BootProvider`、`BridgeRuntime`、`BridgeService` 的启动都包在
   `try/catch` 里，任何一步失败只是桥接不工作，**不会把桌面搞崩**。
3. 除清单外原包一个字节没动，行为与原来完全一致。
4. 万一桌面起不来：车机原有桌面会被系统接管，进去后用文件管理器把原始 `D.apk` 装回去
   即可 —— 同包名同签名，直接覆盖。
5. 想彻底移除桥接：装回原始 `D.apk` 就干净了。

---

## 十、排查表

| 现象 | 原因 / 处理 |
|---|---|
| `versionCode` 冲突装不上 | 车机上装的是更高版本，用 workflow_dispatch 的 `version_code` 输入指定更大的值 |
| 通知栏没有「CarDash 桥接」 | 重启一次车机；或检查车机是否限制了 D.apk 的进程 |
| `http://IP:8765/` 打不开 | 地址不对，或不在同一网段。车机开热点、手机连它 |
| 页面打开但字段全是 `--` | 看 `/state` 里的 `logcatError`。`logcat` 不是 `running` 就是读日志被拦了 |
| 车速有、电量没有 | 该车型信号名不同，把 `/state` 原始 JSON 发我加进映射表 |
| 音乐一直 `--` | 通知使用权没勾；或音源（收音机/USB）没注册 MediaSession |
| 导航一直「暂无导航信息」 | 导航通知被折叠，或包名不在 `NaviSignals.java` 的 `NAV_PACKAGES` 里 |
| IPA 装不上 | 未签名包需要先自签；免费 Apple ID 签的有效期 7 天 |

---

## 十一、改动速查

| 想改什么 | 改哪 |
|---|---|
| 端口（默认 8765） | `inject/src/com/cardash/inject/BridgeRuntime.java` 的 `PORT` |
| 新增车辆信号 | `LogcatSignals.java` 加信号名常量 + `apply()` 映射 + `StateHub.toJson()` 输出 + iOS `CarSnapshot` 加字段 |
| 导航包名白名单 | `NaviSignals.java` 的 `NAV_PACKAGES` |
| 音乐来源优先级 | `MediaSignals.java` 的 `candidates` 数组 |
| 注入的组件 | `tools/inject_manifest.py` 的 `build_components()` |
| iPhone 界面排版 | `ios/CarDash/UI/Panels.swift`、`DashboardView.swift` |
| 背景配色 | `ios/CarDash/UI/BackgroundScene.swift` |
| 刷新频率 | iOS：`DashboardModel.start()` 的 `0.25`；车机：`CarSignals` 的轮询间隔 |

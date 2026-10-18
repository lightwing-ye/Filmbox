# 胶片测光表 Android（暂名 FilmMeter）— 总规划 v0.1

> 目标：复刻 iOS FilmBox Toolkit 的**测光内核**，并补上它的短板——可校准的绝对精度、完整胶片库（互易律 / 宽容度 / 推拉冲）、区域系统（Zone System）读数。
> 运行环境：OnePlus PJZ110（一加 13）/ Android 16 SDK 36。离线、无账号、无广告。

---

## 0. 决策记录

- **D1（已定）目标胶片类型 = 彩色负片 + 黑白负片**，反转片降为次要提示项。
  推论：① Zone System 必须做全套（含阴影/高光锚点、反差跨度、N± 显影建议、推拉冲 EI 换算），因为这是黑白负片的核心工作流；② 互易律闭环的重点在长曝光彩负（Portra/Ektar 类），黑白卷的互易律数据更完整、可用于交叉验证；③ 反转片只保留"反差超 5 档"的轻提示，不做专门流程。

- **D7（已定）发布形态 = 只给自己用（原 A）**：debug APK 直装，不做上架合规；包名与签名结构按可升级的形式先留好。
- **D6（已定）摄影记录 = 简单记录、不做导出（原 A+B 取交集）**：只存时间、场景 EV、所用胶片、光圈/快门，App 内回看即可；CSV/JSON 导出与备注/镜头元数据全部砍掉。
- **D5（已定）胶片库 = 主流 30–40 支（原 B），首发不做 App 内自建/编辑**。
  数据只收官方 datasheet；无官方互易律表的卷给幂律近似并打 `approx` 标记，UI 上区分显示，不拿估算冒充实测。
- **D4（已定）校准策略 = 机身内测光对照式校准 + 开箱即用默认值（原 B+C）**：
  ① 出厂默认走 AE 反推引擎，`evOffset=0`，UI 常显 ±0.5 EV 不确定度，未校准也能直接拍；
  ② 校准向导提供「对照模式」：用你的 35mm 机身对同一场景（或 18% 灰卡）测光，输入机身读数 → 反算偏移量 → 每镜头存一份；
  ③ RAW 线性引擎作为「高精度模式」，同样用这套偏移锚定，不额外要求专业器材。
- **D3（已定）测光方式 = 可调点测 + 多点平均（原 B+C）**：点测 1°/3°/5°/10° 四档、默认 3°，用长焦 + 数字变焦裁切实现，UI 标注当前档位噪声水平；同时保留中心加权、全区平均、手动多点取平均。
- **D2（已定）Zone System = 可视化 + 反差判定（原 A+B），不做显影时间库（C 不做）**。
  交付形态：多点/网格区域读数 → 0–X 区域条 → 阴影自动落 III 区、高光溢出 VIII 区时给「N−1 / N+1」档数建议；显影时间表留可插拔接口但不灌数据、不进 MVP。

## 1. 对标拆解（已核实，App Store 1.7.1 / 2026-09-15）

FilmBox Toolkit 的测光相关能力，以及我们做不做：

| 能力 | iOS 原版 | 我们 | 备注 |
|---|---|---|---|
| Zone System 实时测光（多区域、区域条、标签连线、人脸辅助） | Pro | **做，核心** | 差异化重点 |
| 基础测光（环境光 → 曝光组合） | 免费 | **做** | |
| 1° 点测光 | Pro | **做** | 用长焦裁切模拟，需标定 |
| 互易律失效补偿（按胶卷查表） | 免费 | **做，且联动快门迭代** | 原版只在读数里显示 |
| 闪光 GN 计算 / 景深 / 快门测试 / 底片翻看 / 器材库 | 免费 | V2 选做 | 不阻塞主线 |
| LiDAR 测距 | Pro | **不做** | Android 无等效可信硬件 |
| Apple Log 10-bit 测光 | Pro | **不做，用 RAW 替代** | 见 §2 |

开源情报（已查证）：
- `Wjerry07/FilmBoxMeter`：安卓端仿制 App，**仓库只有 README + 网盘 APK、无源码**，v1.5.3，已做实时测光 / 镜头式转盘 / 多点测光，但**主动去掉了胶片库**。可作为交互参考，不可作为代码基线。
- 其它相关仓库（`film-reciprocity-app`、`rec-fail`、`Reciprocity`）提供互易律数据与回归方法，可参考数据结构，不直接用。

## 2. 精度方案（本项目成败点）

手机相机当测光表，本质是「已知曝光 → 反解景物亮度」。三条引擎并行，按机型能力自动降级：

**A. AE 反推（全机型可用，快）**
```
EV100 = log2(N² / t) − log2(ISO / 100)
L [cd/m²] = 2^EV100 · K / 100      (反射式 K = 12.5)
```
用 Camera2 的 `SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` 读 AE 收敛值。误差源：各厂 AE 目标灰不同、报告 ISO 非标准 → 单机一个偏移量（`evOffset`）即可拉回 ±1/3 档。

**B. RAW 线性（旗舰机，准）**
固定 ISO 100 + 固定快门拍 `RAW_SENSOR`，取拜耳阵列均值（去黑电平后是线性响应），跳过 ISP 未知色调曲线：
```
Y_linear = mean( raw − blackLevel ) / whiteLevel
L ∝ Y_linear · (N² / (t · g))        g = 每机标定增益
```
真机需要 `CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES` 含 `RAW`；一加 13 大概率支持，**运行时探测，探测不到自动降级到 A**。

**C. 多重曝光融合（给人看区域分布）**
按 −2 / 0 / +2 / +4 EV 拍 4 帧（每帧 1/125 s @ ISO100 起），逐像素取未饱和值 → 单帧覆盖 16+ 档，避免高光饱和、暗部噪声。区域测光必须有它，否则一个固定曝光测不出「阴影到高光」。

**校准（一次，30 秒）**
1. 对准灰卡（18%）或在已知 EV 场景下，与参考测光表/照度计比对；
2. 存 `{cameraId, evOffset, gain, vignettingMap, blackLevel}` 到 DataStore；
3. UI 常显不确定度徽标（RAW ±0.2 EV / AE ±0.5 EV），不假装绝对准确。

**物理上限（必须写进 UI）**：手机最长曝光通常 1/2–1 s，短于 1 s 的积分无法直接测 EV < 0 的场景 → 用**时间维堆栈**（连续 N 帧平均，+log2(N) 档）扩展到 EV −6 左右，代价是响应变慢。

## 3. 胶片模型（"最准确的曝光参数"实际在算什么）

```
曝光组合:  EV = log2(N² / t)   →  t = N² / 2^EV
ISO 换档:  EV_S = EV100 + log2(S/100)
区域换算:  z = 5 + (EV_region − EV_anchor)        # 锚点放 V 区（18% 灰）
互易律:    t_corr = t^p  (p≈1.3–1.6)  或  查表 + 分段插值
```
**补偿只作用一次**：查表本身就是「指示时间 → 实际时间」的点对点映射，把结果再喂回第二遍会把 60 s 算成几小时（自检里已固化这条边界）。正确做法是：先给指示时间，补偿后得到实际时间；若用户锁了光圈，就重算快门/提示加 ND。这是原版只做"显示"没做"联动"的地方，我们做闭环。

胶片条目 schema（数值全部来自官方 datasheet，带 `source` 字段，不臆造）：

```json
{
  "id": "ilford_hp5_plus",
  "brand": "Ilford", "name": "HP5 Plus", "iso": 400,
  "type": "bw_neg",
  "latitude": {"over": 4, "under": 1.5},
  "reciprocity": {"mode": "table", "thresholdSec": 1.0,
                  "table": [[1,1],[2,2.5],[4,6],[8,20],[15,60]], "source": "Ilford datasheet"},
  "push": [{"ei": 800, "dev": "+1", "shadowLift": 1}],
  "zones": {"shadow": 3, "texture": 4, "midGray": 5, "highlight": 8},
  "notes": ""
}
```
首发目标 30–40 支主流卷（Kodak / Ilford / Fuji / Foma / 国产），后续可在 App 内自建条目。

## 4. 功能优先级

**MVP（能用）**
1. 实时取景 + 单点/平均测光 → EV → 光圈快门组合（1/3 档连续转盘）
2. ISO / 光圈 / 快门三级联动（转一个，其余跟随）
3. 胶片库选择 → 按卷修正（ISO、互易律、宽容度提示）
4. 校准向导 + 不确定度显示
5. 锁定曝光（AE-L）/ 读数冻结

**V1（差异化）**
6. Zone System：3×3 网格或多点、区域条 0–X、阴影锚点（III 区）/高光锚点（VIII 区）、反差跨度警告（反转片 >5 档标红）
7. 手动多点测光取平均 + 点间 EV 差
8. 1° 点测（长焦裁切 + 视场角标定）
9. 长曝光模式：互易律闭环 + 堆栈测光
10. 摄影记录（Photo Log，含场景 EV、胶片、参数）

**V2（补全原版杂项）**
11. 闪光 GN 计算器、景深/超焦距、快门速度校验
12. 器材库、CSV/JSON 导出

## 5. 技术架构

```
app/            Compose UI（取景层、区域条、转盘、库、记录）
camera/         Camera2：能力探测 / 手动曝光 / RAW 抓帧 / 包围曝光 / 逐区统计
core-math/      纯 Kotlin 无 Android 依赖：EV、区域、互易律、组合求解、标定模型
data/           Room（胶片库、记录）+ DataStore（校准、设置）
```
- 语言：Kotlin + Jetpack Compose；相机用 **Camera2**（CameraX 拿不到手动 ISO/RAW）；无 Hilt，手写构造避免过度设计。
- 关键 Camera2 细节：`REQUEST_AVAILABLE_CAPABILITIES ⊃ MANUAL_SENSOR/RAW`、`CONTROL_AE_MODE=OFF`、`SENSOR_INFO_SENSITIVITY_RANGE`、`SENSOR_INFO_EXPOSURE_TIME_RANGE`、`SensorRect` 裁切区（点测用 `CONTROL_AE_REGIONS` 或直接裁 YUV 区域求均值）、方向用 `Surface` 变换矩阵而非硬旋转（避免 `FilmBoxMeter` 那种 90° 修正/拉伸坑）。

## 6. 交付与验证路径（本机现状）

- 已确认：JDK 21 可用；**无 Android SDK / Gradle / adb 二进制**；aarch64 环境（官方 `aapt2` 仅 x86_64）。
- 因此：`core-math` 用 golden vector 驱动（Python 生成期望值 → JSON → Kotlin 单测断言），数学部分**在本机可跑可验**；
  APK 走「标准 Gradle/AGP 在 PC 或 CI 构建」为主路径；本机 aarch64 直编（Termux 的 aapt2 + d8 + apksigner）作为 M6 的实验路径，**不承诺一定成功**。
- 真机验证靠 `/app/*` 接口（截图、读屏、装包启动）。

## 7. 里程碑

| 阶段 | 产出 | 验收 |
|---|---|---|
| M0 | 本规划 + 需求定稿 | 你确认 §4 优先级 |
| M1 ✅ | core-math + 自检（EV/区域/互易律/组合求解） | 已全绿，21 项 PASS（`sh core-math/run.sh`） |
| M2 | Camera2 测光引擎 + 校准向导 | 真机读数与参考表差 ≤ 1/3 档 |
| M3 | Zone System UI | 区域条读数与灰度卡一致 |
| M4 🚧 | 胶片库 34 支（含 Kodak 电影卷 7 支）+ 互易律闭环 | 已入库并通过自检 |
| M5 | 记录/导出 | 可回溯 |
| M6 | 打包 | 可安装 APK |

## 7.5 构建自动化

- `.github/workflows/android.yml`：push 到 main 或手动触发 → `gradle assembleDebug` → 产出 APK artifact（30 天保留）。
- 不用 gradle wrapper（仓库内无二进制 jar），由 `gradle/actions/setup-gradle` 装指定版本。
- 前置：需要一个 GitHub 仓库；本机无 `gh`、无 token，推送需用户在 GitHub 建空库后给出地址或自行 push。

## 8. 风险

1. 厂商 ISO/曝光值不标准 → 单机标定可解，但**换机型就要重标**。
2. 部分机型 `RAW` 不可用 / 抓帧慢（≥ 300 ms）→ 降级 AE 引擎，精度降一档。
3. 手机最长曝光 1 s（部分机型）→ 堆栈补，但动态场景会糊。
4. 多摄镜头 f 值、视场角各不同 → 每颗镜头单独一套标定。
5. 本机构建能力探底（2026-10-18）：无 qemu-user/binfmt，Termux 主仓 `aapt2` 只有 i686/x86_64 包 → **aarch64 本机直编 APK 基本不可行**，构建回落到 PC/CI；M6 只做尝试，不作为承诺。
6. 精度承诺：校准后可复现 **±1/3 档** 相对一致性；不承诺绝对照度计量（那是照度计的事）。

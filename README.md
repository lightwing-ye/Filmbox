# FilmMeter

安卓胶片测光表。复刻 iOS FilmBox Toolkit 的测光内核，并补上可校准精度、胶片库互易律闭环、区域系统读数。

- **测光**：Camera2 读 AE 收敛后的 (ISO, 曝光时间) 反推 EV100；点测走 `CONTROL_AE_REGIONS`；区域分布取 3×3 亮度相对差（相对量，不需标定即可用）。
- **胶片库**：34 支（Ilford / Kodak / Kodak 电影卷 / CineStill / Fuji / Foma / Kentmere），每条带可信度分级：`MANUFACTURER` / `COMMUNITY` / `APPROX`。
- **互易律**：查表（log-log 插值、表尾外推）或幂律，补偿只作用一次，不做迭代。

## 构建

GitHub Actions 自动出包：push 到 `main` 或手动触发 `build-apk`，产物在 Actions 的 Artifacts 里（`filmmeter-apk-<sha>`）。

本地构建（可选）：

```
gradle assembleDebug
```

## 本机可跑的验证

数学内核与胶片库不依赖 Android，可直接跑：

```
sh core-math/run.sh
```

覆盖 EV↔亮度、ISO 换算、区域换算、互易律插值/外推、1/3 档组合求解、胶片库完整性，共 25 项断言。

## 结构

```
core-math/        纯 Kotlin：Metering / Reciprocity / ExposureSolver / FilmCatalog
app/              Compose UI + Camera2（camera/CameraMeter.kt）
docs/PLAN.md      规划与决策记录（D1–D7）
```

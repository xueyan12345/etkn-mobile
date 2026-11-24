# Fork 改动记录

本仓库是 `Alumos/etkn-mobile` 的 fork，用于个人使用的定制。上游的 `AGENTS.md`、
`docs/architecture.md`、`docs/api-reference.md`、`docs/device-qa.md` 继续有效，
本文档只记录**本 fork 相对上游的改动**以及改动过程中确认的坑。

上游的每个版本另有 `release_notes_vX.Y.Z.md`（面向用户的简体中文更新说明），
打 tag 时作为 Release 正文。本文档面向开发者，记录「为什么这么做」和「别再犯什么」。

---

## 阅读顺序

1. 改代码前先读 `AGENTS.md` 的「开发边界」和本文档的对应章节。
2. 改动涉及真机行为时，按 `docs/device-qa.md` 勾验收。
3. 每个版本发版前写 `release_notes_vX.Y.Z.md`。

---

## 平板画布旋转（OPD2515 专项）

### 背景

OPPO Pad Mini（`OPD2515`，Android 16 / API 36，竖屏 1680×2520、横屏 2520×1680、3:2）
在 Android 16 上**大屏会忽略 `requestedOrientation`**（`smallestWidthDp ≥ 600`）。
SenPlayer、AfuseKit 在同一台机器上同样无法旋转 → 属系统级限制，不是本应用的问题。

### 走过的弯路（按时间顺序）

| 尝试 | 结果 | 原因 |
|---|---|---|
| 只调 `requestedOrientation` | 无效 | 系统忽略 |
| 复核 `setShouldDismissRequestedOrientation` / `setIgnoreOrientationRequest` 兼容开关 | 全部 false，无补救 | 系统层面不认 |
| 给 `SurfaceView` 套 `graphicsLayer{rotationZ}` | 画面变「竖排文字」 | `SurfaceView` 是独立硬件合成层，不在 View 树绘制，父层变换对它无效 |
| 换 `TextureView` 后旋转 | 方向对了，但整体偏左、上方被截 | 画布左上角摆放 + 手动 translation，平移量按「已居中」推算，实际没居中 |
| 画布改 `contentAlignment = Center` 绕中心转 | 方向与居中正确，但左右各 490px 黑边 | 画布用 `.size()` 被父容器 constraints 钳制：要求 2520×1680 被压成 1680×1680，2.4:1 视频 fit 进去只有宽 700 |
| 画布改 `requiredSize` + 原始屏幕宽高 | 黑边消失 | `requiredSize` 绕开父约束 |

### 最终方案

- `player/CanvasRotationSupport.kt`：机型白名单（`OPD2515` / "OPPO Pad Mini"），`needsCanvasRotation` 门控。
- `res/layout/view_texture_player.xml`：`PlayerView` 声明 `surface_type="texture_view"`（Media3 无 `setUseTextureView` API）。
- `PlayerScreen.kt`：`rotated = forceRotate && needsCanvasRotation` 时，整层用
  `requiredSize(屏幕高, 屏幕宽)` + `graphicsLayer{rotationZ=90f}` + `contentAlignment=Center`。
- 方向判定读 `activity.resources.configuration.orientation`（**不是** `LocalConfiguration`，后者在 `postDelayed` 回调里是组合期旧值）。

### 画布坐标 ↔ 屏幕坐标（最容易错的一步，改错三版）

`rotationZ = 90f` 是顺时针。画布内点 `(dx, dy)` 转到屏幕是 `(-dy, dx)`：

| 画布内的位置 | 用户看到的位置 |
|---|---|
| 右侧 `(dx>0, dy=0)` | 屏幕**下**方 |
| 底部 `(dx=0, dy>0)` | 屏幕**左**侧 |
| 顶部 `(dx=0, dy<0)` | 屏幕**右**侧 |
| 左侧 `(dx<0, dy=0)` | 屏幕**上**方 |

所以「面板要出现在屏幕右侧」→ 在画布上写 `Alignment.TopCenter`。

历史错误：v1.2.24 用 `CenterEnd`（跑到屏幕下方）、v1.2.25 用 `BottomCenter`（跑到屏幕左侧）。
**不要靠直觉选边，先按上表推。**

### 滑动面板（音轨 / 字幕 / 选集 / 弹幕 / 倍速 / 版本）

`player/PlayerPanel.kt` 的 `PlayerSidePanel` 是**独立的 Android Dialog**（自己的 Window），
不在播放器的 Compose 图形树里 —— 播放器整层 `rotationZ` 对它无效，所以必须单独处理：

1. **窗口全屏 `MATCH_PARENT` + `Gravity.FILL`**：否则旋转后的画布会被 Window 边界裁切。
2. **内部再套一块旋转画布**（和播放器同一套 `requiredSize` + `rotationZ` + 居中）。
3. **面板按内容包裹 + 垂直居中**，不要 `fillMaxHeight()` —— 会被拉成整屏高、内容上下被裁。
4. **`rotated` 参数必须用 `rememberUpdatedState`**：`dialog` 是 `remember(host)` 创建的，
   `setContent` 的 lambda 只跑一次，直接闭包捕获外部 `rotated` 会固化首次组合时的值（那时还是 `false`）。
5. **不要写 `decorView.layoutParams = decorView.layoutParams?.apply{...}`**：Dialog 刚显示时它是 `null`，
   右侧 `?.` 返回 `null`，再赋回去就是 `setLayoutParams(null)` → `NullPointerException: Layout parameters cannot be null`（实测崩过）。
6. **全屏窗口下 `setCanceledOnTouchOutside` 失效**：窗口自己铺满整屏就没有「外面」，
   系统永远判不到「点在外部」。要在 Compose 层铺透明点击层关闭，面板本体再加空 `clickable` 消费点击防冒泡。

### 起播方向（用户明确要求）

- 打开播放器**第一帧就是横屏**（绝大多数片源是横屏）。
- 拿到真实视频尺寸后**如果确实是竖屏视频，再转回竖屏**。
- 不读重力传感器，只按视频尺寸判断。
- 每次切集都重新判断（`onVideoSizeChanged` 每次载入都会触发）。

实现要点：
- `LaunchedEffect(Unit)` 里对所有机型 `requestedOrientation = USER_LANDSCAPE`，
  兜底机型同时 `forceRotate = true`；**这里绝不写 `portrait`**。
- 真实方向由「服务端 `MediaStreams` 宽高」与「`onVideoSizeChanged`」**无条件** `applyOrientation`。
  曾经写成「仅在 `portrait` 变化时才应用」，结果 `portrait` 被别处错写成横屏后，
  竖屏视频一次都不会触发纠正 → 用户看到「不自动判断方向了」。

---

## 播放缓存与预加载

### 用户定义的正确语义

1. **滑动窗口**：两个阈值（最大缓存时间、最大缓存大小）任一达成就「这一轮」不再往后扩；
   但窗口跟着播放位置前进，所以**单集内缓冲条长度恒定**。
   效果是边播边缓冲、边缓冲边暂停、边暂停再缓冲，全程动态。
2. **当前集完成**只看往后：从**当前播放位置**到片尾已无可缓冲。
   从中间开始播时前面没缓存，不影响判定 —— 不能要求「整集 0→结尾全部缓存完」。
3. **触发下一集预加载的时机**：当前集到片尾后**立刻**，不等播完。
4. **下一集预加载量（片头优先）**：
   - 有 `IntroEnd` 片头数据 → 目标「片头结束 + 1 分钟」
   - 配额能覆盖到那里 → 按配额继续往下缓冲
   - 配额覆盖不到 → **突破配额**硬缓冲到片头后 1 分钟（这一条不受配额限制）
   - 无片头数据 → 按配额
5. **配额全局共用**：上一集剩余缓冲 + 下一集预加载 ≤ 设定的时间 / 大小，不是每集各给一份。
6. **气泡（下一集就绪）条件**：下一集**已开始缓冲、点下去能秒播**就亮，不要求整集缓存完。

### 修过的坑

| 现象 | 根因 | 修法 |
|---|---|---|
| 磁盘上限设 5GB 实际占 12.7GB | `trim()` / `preload()` / `afterEpisodeFinished()` / `ensureWindow()` / `localFile()` 全是死代码（0 调用），`trim()` 只在死代码 `preload()` 里被调 | 接上真实调用链：每次写盘后 `trim()`、`onEnded()` 调播完清缓存、首次 tick 收敛一次 |
| 关掉「预加载下一集」后，缓存大小/时长设置全部失效 | `tickCache` 首行 `if (!settings.preloadNext) return`，把两件独立的事绑在一起 | 缓存窗口与预加载解耦 |
| 缓冲条不动 / 逻辑很乱 | `ui.cachedPosition` 只声明、从未计算，恒为 0，缓冲段只能退回 `player.bufferedPosition`（内核几秒前向缓冲） | `tickCache` 按落盘文件大小换算写入 |
| 起播后一大段时间完全不缓冲 | `tickCache` 有「播满 15 秒」「内核前向缓冲满 30 秒」两个早退门槛 | 去掉门槛，开播就推进窗口 |
| 网速很高但缓冲条半天不动、然后蹦一大截 | 驱动间隔 5 秒 + 分片 32MB（69MB/s 时 0.5 秒就下完，界面等 5 秒才更新） | 间隔 1 秒、分片 12MB |
| 缓冲铺到片尾了，下一集气泡还是不亮 | `currentComplete` 只信 `pullWindow` 的返回值，而它在「窗口已够、本轮没下载」时返回 false | 由 `tickCache` 按真实缓冲位置（2 秒容差）直接 `markCurrentComplete(true)` |
| **播放闪退（403）** | 缓存下载器用裸 `DefaultHttpDataSource`，不带认证信息 → 服务端 403；异常在协程里没被捕获 → `FATAL EXCEPTION: DefaultDispatcher-worker-7 / yt1: Response code: 403` 直接杀进程 | 复用播放器的 OkHttp client（`PlaybackCache.init(graph.http.media)`）+ `downloadInto` / `pipeline` 全包 `runCatching` / `catch (Throwable)` |

---

## 详情页

### 不做画面动态取色

原实现在 `DetailScreen` / `EpisodesScreen` 外层包 `ArtworkTheme(rememberArtworkSeed(backdrop))`：
切一集 → backdrop 变 → seed 先变 `null`（新图没算完）→ 主题退化成「无主题」→ 算完再重建，
每次都要重跑 `seedColorScheme` + `tintedSurfaces`（6 次 `Hct.from`）+ `etknTokens`，
而 `ArtworkTheme` 包着整个页面 → 所有用 `colorScheme` 的 Composable 全部重组重绘。
用户感受就是「动画生硬、不丝滑」。

**方案：详情页固定用 App 主题色**，切集时主题完全不变，只有真正变化的内容（主图/标题/简介/选中态）重组。
`ArtworkColor.kt` / `ArtworkTheme` 本身保留（别处可能用），但详情页不再调用。

### 章节缩略图只用系列级封面

章节无图时应显示**整部剧的封面**，不是当前这一集的图。

错误做法：调 `ImageUrls.landscape(item)` 并传 episode —— 它内部第 2 条是
`if (item.isEpisode) item.imageTags["Primary"]`，会把本集剧照当封面，
同一排章节卡就变成「一张本集特写 + 其余别的场景」。

正确做法：`ImageUrls.seriesCover(item)` = 父级 `Thumb` → 父级 `Backdrop` → 系列 `Primary`。

### 布局与滚动

- **滚动状态必须页面级**：`rememberLazyListState()` 建在内容内部时，切集重组即重建 → 跳回顶部。
  要提升到 `AnimatedContent` 之外，`contentKey` 用「是否 Ready」而不是 `it::class`。
- **探索页返回保位不能用 `rememberSaveable`**：导航返回是「重新进入」不是配置变更，
  `SavedStateRegistry` 不保证恢复（实测改过一版无效）。要把 `firstVisibleItemIndex/Offset` 存进 ViewModel。
- **单集页「集号 + 集名」**在播放按钮下方、简介上方（用户指定），不在剧名下方。
- **元数据行**：日期取 `PremiereDate`（电影 100%、剧集 93%、单集仅 32%），没有退回 `ProductionYear`；
  时长取 `MediaSource.runTimeTicks`（各库都有，含 LTX 破解）。**取不到就不显示，不得编造或显示 0。**

### 季卡片与季详情页

- 详情页「季」区：横排竖版卡，尺寸与演职人员卡一致（104dp / 0.78），
  海报右上角集数、右下角评分、下方季名，选中季有主题色描边。
- 点季卡进入季详情页（全屏新页面）：顶部头图 + 剧名 + 季名 + 播放按钮 + 竖向剧集列表
  （左 16:9 缩略图，右为「集号集名 / 时长 / 简介」）。

---

## 播放器 UI 尺寸（用户逐项指定，三档）

| 档位 | 元素 |
|---|---|
| **原始大小（1×）** | 顶部时间 13sp、网速 / 电量 12sp、电池图标 24×12dp、加载圈 72dp、拖动预览 176dp、手势反馈文字（含长按倍速 2.0× 大字） |
| **1.5×** | 剧名艺术字 240×48dp、进度条整体高度 39dp |
| **1.25×** | 返回箭头 30dp、中央播放 75dp、快退/快进 45dp、底栏功能图标 30dp、进度条轨道 3.75dp、播放头 17.5dp、进度条时间 15sp、底部集标题 20sp、倍速 +/− 圆钮 40dp、倍速文字 16.25sp、弹幕自绘按钮 30dp、下一集预加载小圆点 17.5dp |
| **保持不动** | 跳过片头胶囊（内距 16/9dp、图标 18dp、文字 14sp）、「取消」14sp |

触控区统一 60dp（图标放大了但点击区域不必继续放大）。
**平板必须一并核算**：底栏 8 个按钮 × 60dp = 480dp，画布宽度 916~1260dp 够用；
纵向 1.25× 下总高约 350dp，最矮画布 560dp 够用。

---

## 首页

- **轮播图按账号独立**：`HeroSnapshot(lastShownAt + 8 个 id)`，`HERO_TTL_MS = 12h`。
  未过期沿用（剔除已看完/已删除后不足 8 个则当场补），过期重抽。
  拒绝「固定 12 小时时间桶算种子」——那样半天内就换、找不回来。
- **切账号必须立刻换图**：两个入口都要按账号算。
  `load()` 里不能无条件沿用上次的 hero（只有同账号才沿用）；
  `showCached()` 不能用不分账号的 `fallbackHero`（它取最新入库头 6 部）。
  否则用户看到「切过去还是老的，必须手动刷新」。
- **主界面禁止左右滑动切标签，但不要用「吃掉横滑事件」实现**：
  - `PointerEventPass.Initial` 吃 → 轮播图完全滑不动
  - `PointerEventPass.Final` 吃 → `LazyRow` 时灵时不灵
    （拖拽要靠连续几帧位移才能认定，中间被吃几帧就判定失败）
  - 正解：只用外层 Pager 的 `userScrollEnabled = false`，内层横向组件不要再拦。

---

## 构建与发版

沿用上游策略：**禁止在用户本机跑 Gradle**，全部在 GitHub Actions 完成。

- 推 `main` → 编译校验收到的 APK（Actions 构件）
- 推 `v*` tag → 签名并发布 Release（分 ABI）
- 每个版本写 `release_notes_vX.Y.Z.md`（简体中文，用户可见），打 tag 用
  `git tag -a v1.2.X -F release_notes_v1.2.X.md`
- 版本号每档 1-10 递增（1.1.1~1.1.10 → 1.2.1~1.2.10），
  `baseVersionCode` / `baseVersionName` 在 `app/build.gradle.kts`
- **一次改动做完再发版**：不要为单个小问题连续发多个版本。

### 发版前自查

- [ ] 所有改动的 Kotlin 文件括号平衡（`{`/`}`、`(`/`)`）
- [ ] 新增的 `import` 真的需要、没有重复（`Corner` / `px` 这类同名不同包最容易冲突）
- [ ] 改了 `@Composable` 签名时，所有调用点都同步（尤其 `PlayerSheet` 这种多处调用的）
- [ ] 改了 UI 结构后，`docs/device-qa.md` 对应条目已补
- [ ] `AGENTS.md` / 本文档记录新确认的坑

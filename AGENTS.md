# ETKN Mobile 开发边界

- 原生 Android 手机/平板客户端（Kotlin + Jetpack Compose + Material 3 Expressive），最低 Android 8 / API 26，目标 API 36。包名 `xyz.etkn.mobile`，GitHub 仓库 `Alumos/etkn-mobile`。
- 服务端接口一律走 ETKN 专用 `/api/tv` 路由（接口来源见 ETKN TV 客户端源码与其 `docs/etkn-mobile-api.md`）；不回退到通用 Emby/Jellyfin 路由，不从 ID 拼接播放地址。客户端标识 `ETKN Mobile`。
- 连接时严格识别 ETKN：`/api/health` → `/api/tv/System/Info/Public`（`ProductName=ETKN`, `TvApiVersion=1`）→ `/api/system/tv-discovery`，跨地址候选须校验同一实例 `Id`。
- 令牌：私密账号只在内存；管理员仅在服务端 `remember_admin_account` 开启时持久化；其他账号用 Android Keystore AES-GCM 加密保存。不得打印或记录令牌、密码、带凭证的 URL。
- 图片请求只向当前 ETKN 同源地址附加 `X-Emby-Token`，跨源跳转必须 HTTPS；视频直链使用服务端 `DirectStreamUrl`，普通直链必须同源；外挂字幕同源、去掉 `UserId`、设置 `X-Emby-Token`。
- 播放上报：`Sessions/Playing` → 每 10 秒 `Progress` → `Stopped`（片尾/播完附 `PlayedToCompletion`），停止上报在应用级协程发出，不能随页面销毁丢失。
- UI 统一使用 Material 3 Expressive 组件（`MaterialExpressiveTheme`、`LoadingIndicator` 等）；一切可选的胶囊（单选行、筛选、分段范围、多选、版本、已选标签）一律用 `PillToggle`，不用 FilterChip/InputChip（各状态都是全圆角胶囊，未选为 secondaryContainer 色块而非透明，选中为 primary；行内间距 `PillGap`）；圆角只用 `Tokens.kt` 的 `Corner` 四档：small 8（64dp 以下缩略图、角标）、card 16（图片、行、输入框）、panel 28（装东西的面：设置组、菜单、Dock、迷你播放器、弹窗）、pill（按钮、开关、条），贴着 panel 边缘的内层用 `Corner.inPanel(gap)` 保持同心，不得自写半径，Material 形状角色也折算到这四档；页面左右边距统一 `Etkn.space.screen`（顶部悬浮条、底部 Dock、内容同一条边），设置行标签与胶囊行都从 `ROW_INSET` 起；毛玻璃模糊半径统一 `GlassBlur`（应用内 Haze 与弹窗窗口模糊同值）；加载中页面先留白，超过 700ms 才淡入指示器，数据到达后内容淡入（`Motion` 时长），不整页闪转圈；单集详情页里点同剧其他集是原地切换（`DetailViewModel.show`），只淡入淡出头图、操作与简介，不新开页面；顶层导航是自绘悬浮毛玻璃 `Dock`（手机底部横条，平板侧边窄竖条叠在内容上、首页海报从其下延伸，其他标签页按 `LocalStartInset` 让位），可点按也可在 Dock 上滑动切换，选中块随 pager 位置拉伸；侧边 Dock 时标签页为 `VerticalPager` 且只由 Dock 驱动（页内竖滑只滚动页面）。搜索页可切「影视 / 音乐 / MV」（默认影视；音乐按专辑与歌手、MV 按 `MusicVideo` 标记，TMDb 回退只在影视下）；凡按类型找媒体库（音乐库、首页最新）都用 `EtknApi.libraries()`，它把 `Views` 里的媒体库分组展开成成员库，只有媒体库页按 `Views` 原样显示分组；页面切换全局统一为「翻书页」：上层页是以右边缘为轴的书页，打开时翻下盖住当前页，返回时翻起露出下层页（下层页随之由暗变亮），只改图层属性（rotationY、alpha、下层页之上一个独立黑色图层的 alpha；不得在页面自身图层里按动画值绘制，否则整页连同采样它的毛玻璃每帧重录），书页淡出用 `CompositingStrategy.ModulateAlpha` 免离屏缓冲，毛玻璃 `inputScale = Auto` 先缩小再模糊；主窗口启动时选当前分辨率下刷新率最高的显示模式（部分 ROM 默认把应用限在 60 Hz），播放器窗口不受影响；由 `NavGraph.PageFrame` 统一实现，NavHost 只记录方向并保留两页（六个转场参数含 `predictivePopEnter/ExitTransition` 必须全部显式设置，否则手势返回会走库默认的缩小 70%）；书页角度取 arccos(1−t)，使自由边的位移正好是 t 倍页宽（线性角度会让前段几乎不动、末段猛冲）；预测性返回手势时 t 就是手势进度，用线性时间轴，从右边缘起手时改以左边缘为轴，自由边始终在手指下；点按返回与打开用缓动曲线；配色由服务器 `theme_color` 经 HCT（material-color-utilities）生成，或 Android 12+ 壁纸取色。语义色/间距只在 `ui/theme/Tokens.kt` 定义。页面避免不必要的辅助标签与说明。
- 性能：单一 OkHttp 连接池（HTTP/2）、Coil 按显示尺寸分桶请求图片、列表全部带稳定 key/contentType、播放器双内核（`player/VideoSession` 按片源选：原盘与 HDR/杜比视界走 ExoPlayer 硬解直出 HDR，其余走 mpv `player/mpv`，MediaCodec 硬解 + gpu-next；一个内核失败自动换另一个；ExoPlayer 缓冲按时长和字节双重上限，`WAKE_MODE_NETWORK`，mpv 由 `PlaybackLocks` 只在播放时持有唤醒锁与 Wi-Fi 锁），ExoPlayer 缺解码器时 NextLib FFmpeg 兜底；起播时 PlaybackInfo 在点击播放时即预取、不等详情，302 目标按播放器缓存；音乐曲目地址在加载线程按需解析；顶层标签页在同一个 `HorizontalPager`（`TabsRoute`）里，切换是翻页不是导航，相邻页会预先组合，未显示的页（`LocalTabActive` 为假）不得请求焦点或做“打开时”的动作；首页与媒体库列表先显示 `ResponseCache` 中上次的响应再静默刷新（私密账号不落盘，退出登录即清空）。

- 应用更新只读公开的 GitHub Release（API 或 `SHA256SUMS.txt`），下载默认经 `gh-proxy.com`、可换 GitHub 直连，必须校验 SHA-256；外部请求不得携带任何 ETKN 令牌或账号信息。
- 全部服务端接口的说明见 `docs/api-reference.md`（供其他平台移植）；新增或改动接口时同步更新。
- 媒体管理（刷新/删除/编辑元数据/编辑图片/提取缩略图）只走 `/api/tv` 与 `/api/tv/Manage`，按 Web 端权限规则显示入口；删除必须二次确认并默认取消，不得批量或自动执行。
- 影视探索仅在 `can_access_discovery` 为真时出现；订阅与云资源获取都必须先经用户确认，不得自动重试或预取获取接口。

## 构建与容器策略

- **禁止在用户本机 Mac 运行 Gradle 或构建 APK。** 编译、单元测试、Lint 与签名全部在 GitHub Actions 官方 Ubuntu runner 上完成（`.github/workflows/android.yml`）。
- 构建 job 不接触签名凭据，产出一次性未签名 APK；签名 job 为全新 runner，不 checkout、不运行 Gradle，仅用 `apksigner` 以固定 PKCS#12 发行密钥签名并校验证书 SHA-256。
- 签名 Secrets：`ETKN_KEYSTORE_BASE64`、`ETKN_KEYSTORE_PASSWORD`、`ETKN_KEY_ALIAS`、`ETKN_KEY_PASSWORD`、`ETKN_SIGNER_SHA256`。私钥离线备份在仓库外私有目录 `~/.etkn-mobile-signing`（0700/0600），不得提交、公开或打印。
- 推送 `v*` 标签会把签名后的分 ABI APK（arm64-v8a / armeabi-v7a / x86_64 / universal）发布到 GitHub Release；versionCode = (1000 + run_number) × 10 + ABI 位。
- 蓝光 ISO 原生库 `libetkn_bluray.so` 只由 CI 用 `scripts/build_bluray_native.py`（NDK r27c、上游源码 SHA-256 校验）构建到被忽略的 `app/src/main/jniLibs/`；JNI 名称与 `third_party/bluray/bluray_jni.cpp` 保持一致，并保留 `proguard-rules.pro` 中的 keep 规则。其中链接 `third_party/dovi`（Rust 静态库，包装 dovi_tool 的 `dolby_vision` crate，`Cargo.lock` 固定依赖），CI 需 `rustup target add` 三个 Android 目标；杜比视界 profile 7 在设备有 profile 8 解码器且屏幕支持杜比视界时转 8.1 播放。
- mpv-android 与 NextLib 共用一套 FFmpeg 9 共享库（`pickFirsts` 取 mpv 的完整版，CI 校验 APK 内 libavcodec 与 mpv AAR 一致）；升级任一方都要保持 FFmpeg 主版本一致。mpv 是进程级单例，同时只能有一个 `MpvPlayer`。
- 研究用的参考仓库（CineIsle、md3e-skill 等）只放在被 `.gitignore` 忽略的 `.ref/`，不参与构建。
- 诊断日志（`data/diagnostics/Diagnostics.kt`）：只记录本应用 UID 的错误级日志（含崩溃堆栈）和 `ETKN*` 标签的关键行（来源、轨道、解码器、格式），带年份时间戳，256 KB × 8 轮转，启动时删除 7 天前的文件；「我的 → 导出日志」按日期与小时段导出，导出前去掉 URL 查询串与令牌类键值。自己写的日志只记主机、状态码、格式与 PID，不记完整播放地址、令牌或账号信息；不要再挂 Media3 `EventLogger` 这类逐事件日志。进程自身记不下的死亡由下次启动时读取 `ApplicationExitInfo`（`ExitRecords`）写入 crash.log：崩溃与 ANR 一律记录，自行退出只记非零状态（libmpv 致命错误是 `exit(1)`），其他原因只记在前台时发生的；每条附上该 PID 留在系统日志缓冲区里的最后几行（记录器随进程一起被杀，常漏掉这几行），原生崩溃另解析墓碑 protobuf 的信号、abort 信息与崩溃线程回溯。
- `MpvPlayer` 继承 `SimpleBasePlayer`：未列入 `COMMANDS` 的命令会被静默忽略（缺 `COMMAND_RELEASE` 时 libmpv 从不销毁，下一次 `MPVLib.create` 直接结束进程）；新增 `handle…` 覆写时必须同步加命令。
- 区分“源码完成 / CI 构建通过 / 真机验证”，未经真机验证的行为不得写成已验证。

## 平板画布旋转（OPPO Pad Mini 专项）

- **触发机型白名单**：`player/CanvasRotationSupport.kt`，只含 `OPD2515` / "OPPO Pad Mini"。其他机型（手机、普通平板）系统方向本来就正常，一旦误套画布旋转会把画面压成中央小方块。动旋转相关代码必须先看这个门控。
- **为什么要画布旋转**：Android 16 大屏（smallestWidthDp ≥ 600）默认忽略 `requestedOrientation`，SenPlayer / AfuseKit 在这台平板上同样转不了，属系统级限制。`SurfaceView` 是独立硬件合成层、不在 View 树绘制，`graphicsLayer` 转不动（转完是「竖排文字」）；必须用 `TextureView`（`res/layout/view_texture_player.xml` 里 `surface_type="texture_view"`，Media3 没有 `setUseTextureView` API）。
- **画布尺寸必须用原始屏幕宽高 + `requiredSize`**：写 `.size(canvasW, canvasH)` 会被父容器 constraints 钳制（2520×1680 被压成 1680×1680），2.4:1 的视频 fit 进缩小画布只取到宽 700，旋转后左右各 490px 黑边。`requiredSize` 才能绕开父约束。
- **画布必须 `contentAlignment = Center`**：绕自身中心旋转。用左上角摆放再手动 translation 补，平移量是按「已居中」推的，实际会整体偏左、上方被截。
- **旋转态的滑动面板（音轨/字幕/选集/弹幕/倍速）**：`player/PlayerPanel.kt` 的 `PlayerSidePanel(rotated = ...)`。窗口必须全屏 `MATCH_PARENT` + `Gravity.FILL`（否则被 Window 边界裁切），面板位置靠内部 Compose 画布算。
- **画布坐标系换算是易错点**（连续改错三版）：`rotationZ = 90f` 顺时针转，画布内点 `(dx,dy)` 到屏幕是 `(-dy, dx)` —— 画布右侧→屏幕下、画布底部→屏幕左、**画布顶部→屏幕右**、画布左侧→屏幕上。所以「用户看到的屏幕右侧」要写 `Alignment.TopCenter`。不要靠直觉选边，先按这条公式推。
- **全屏窗口下 `setCanceledOnTouchOutside` 会失效**：窗口自己铺满整屏就没有「外面」，系统永远判不到点击外部。必须在 Compose 层铺透明点击层（`clickable(indication = null)`）关闭，面板本体再加一个空的 `clickable` 消费点击防冒泡。
- **`rotated` 参数不能直接引用**：`dialog` 是 `remember(host)` 创建的，`setContent` 的 lambda 只跑一次，直接闭包捕获外部 `rotated` 会被固化成首次组合时的值（那时还是 false）。必须 `rememberUpdatedState`。
- **不要给 `decorView.layoutParams` 赋 `?.apply{}` 的结果**：Dialog 刚显示时它是 null，右侧 `?.` 对 null 返回 null，再赋回去就是 `setLayoutParams(null)` → `NullPointerException: Layout parameters cannot be null`。要显式 `new ViewGroup.LayoutParams(...)`。
- **起播方向**：`LaunchedEffect(Unit)` 里对所有机型先 `requestedOrientation = USER_LANDSCAPE`（手机系统立刻转，第一帧就是横的），兜底机型同时置 `forceRotate = true`；但**不要**在这里写 `portrait` 状态 —— 会和服务端尺寸预判/`onVideoSizeChanged` 抢写，竖屏视频永远翻不回来。真实方向一律由后两者无条件 `applyOrientation` 决定。

## 播放缓存

- `PlaybackCache` 是**滑动窗口**语义：目标 = 播放位置 + 窗口（时间与大小取小者），窗口跟着播放位置走，所以单集内缓冲条**恒长**。不是「一次性配额拉满就停」。
- **「当前集缓存完成」的判据只看往后**：从当前播放位置到片尾已无可缓冲。从中间开始播时前面没缓存，不影响判定；不能要求「整集 0→结尾全缓存」。
- **气泡（下一集就绪）条件**：当前集到片尾 + 下一集**已开始缓冲、点下去能秒播**（不是全缓存完）。配额全局共用：上一集剩余 + 下一集预加载 ≤ 设定的时间/大小。
- **下一集预加载量片头优先**：有 `IntroEnd` 片头数据 → 目标「片头结束 + 1 分钟」；配额能覆盖就按配额继续，覆盖不到就**突破配额**硬缓冲到片头后 1 分钟（这一条不受限）。无片头数据 → 按配额。
- **`ui.cachedPosition` 必须真的被计算**：这个字段曾经只声明、从未赋值，永远是 0，缓冲段只能退回 `player.bufferedPosition`（内核几秒前向缓冲），用户看到的就是「缓冲条逻辑很乱」。现在由 `tickCache` 按落盘文件大小换算写入。
- **`currentComplete` 不能只信 `pullWindow` 的返回值**：`pullWindow` 在「窗口已够、本轮没下载」时返回 false，于是缓冲条铺到片尾了 `currentComplete` 还是 false、气泡永不亮。要由 `tickCache` 按真实缓冲位置（含 2 秒容差）直接写 `PlaybackCache.markCurrentComplete()`。
- **下载器必须复用播放器的 OkHttp client**（`PlaybackCache.init(graph.http.media)`）：裸 `DefaultHttpDataSource` 不带认证信息，下载直链返 403；且 403 抛在协程里没被捕获会直接 `FATAL EXCEPTION` 杀进程（`yt1: Response code: 403`）。`downloadInto` 与 `pipeline` 都必须包 `runCatching` / `catch (Throwable)`。
- **`tickCache` 不要加「播满 N 秒 / 内核缓冲满 N 秒才启动」这类门槛**：会让起播后一大段时间完全不缓冲、缓冲条不动。驱动间隔 1 秒、分片 12MB，网速快时缓冲条才能连续往前走。

## 详情页

- **不做画面动态取色**（原 `ArtworkTheme` / `rememberArtworkSeed` 已从详情页移除）：切一集会重新取色 → 重建整套 Material3 配色（`seedColorScheme` + `tintedSurfaces` + `etknTokens`）→ 整棵子树重组重绘，又慢又生硬。固定用 App 主题色，切集时主题不变，只有真正变化的内容重组。
- **章节缩略图只用系列级封面**：`ImageUrls.seriesCover()` = 父级 `Thumb` → 父级 `Backdrop` → 系列 `Primary`。**不能**用 `landscape(item)` 传 episode —— 它内部第 2 条是 `if (item.isEpisode) item.imageTags["Primary"]`，会把本集剧照当封面，同一排章节卡就变成「一张本集特写 + 其余别的场景」。
- **滚动状态必须页面级**：`rememberLazyListState()` 建在内容内部时，切集重组即重建 → 跳回顶部。要提升到 `AnimatedContent` 之外，`contentKey` 用「是否 Ready」而不是 `it::class`。
- **探索页返回保位不能用 `rememberSaveable`**：导航返回是「重新进入」不是配置变更，`SavedStateRegistry` 不保证恢复。要把 `firstVisibleItemIndex/Offset` 存进 ViewModel。
- **元数据行**：日期取 `PremiereDate`（电影 100%、剧集 93%、单集仅 32%），没有就退回 `ProductionYear`；时长取 `MediaSource.runTimeTicks`（各库都有）。取不到就不显示，**不得编造或显示 0**。格式：≥1 小时「1小时59分」，否则「45分25秒」。

## 首页

- **轮播图按账号独立**：`HeroSnapshot(lastShownAt + 8 个 id)`，`HERO_TTL_MS = 12h`。未过期沿用（剔除已看完/已删除后不足 8 个则当场补），过期重抽。
- **切账号必须立刻换图**：两个入口都要按账号算 —— `load()` 里不能无条件沿用上次的 hero（只有同账号才沿用），`showCached()` 不能用不分账号的 `fallbackHero`。否则用户看到「切过去还是老的，必须手动刷新」。
- **主界面禁止左右滑动切标签，但不要用「吃掉横滑事件」实现**：`PointerEventPass.Initial` 吃会让轮播图完全滑不动，`Final` 吃会让 `LazyRow` 时灵时不灵（拖拽要靠连续几帧位移才能认定，中间被吃几帧就判定失败）。只用外层 Pager 的 `userScrollEnabled = false` 即可，内层横向组件不要再拦。

## 版本管理

- 每个版本写 `release_notes_vX.Y.Z.md`（简体中文，用户可见），打 tag 时用 `git tag -a v1.2.X -F release_notes_v1.2.X.md`。
- **版本号每档从 .0 开始、末位 0-10 递增，满 10 进下一位**：`1.2.10 → 1.3.0 → 1.3.1 → … → 1.3.10 → 1.4.0`。当前在 `1.2.26`，**下一个版本从 `1.3.0` 开始**。`baseVersionCode` 与 `baseVersionName` 在 `app/build.gradle.kts`。
- 发布只告知版本号，不发 APK 文件（用户在 App 内自行更新）。
- **一次改动做完再发版**：不要为单个小问题连续发多个版本，用户明确要求「下次更新直接都做完了再发版」。

## 详情页「影片信息」三条（视频 / 音频 / 字幕）

- **常驻显示，不做隐藏入口**：早期藏在「影片信息」胶囊后点开才浮出，用户明确要求删掉入口、三条直接铺在播放按钮行**上方**（「从头播放」那一行的上面）。
- **层级规则**：`series`（剧集详情页）/ `movie`（电影详情页）/ `episode`（单集页）显示；`season`（季详情页）**不显示**。
- **数据源**：统一走 `techSource(data)`（已处理「series 层取下一集的版本」）。多版本时点视频行展开 `item.mediaSources`，切换走 `vm.selectSource(i)`。
- **`(默认)` 不能重复拼**：服务端 `DisplayTitle` 本身就带 `(默认)`（实测 `国语 DDP stereo (默认)`），客户端再拼一次会显示成 `(默认) (默认)`。统一用 `MediaStream.cleanTitle()`，已含则不再补。
- **只有多于 1 条才给展开箭头**：单音轨 / 无字幕时不画箭头（`现在就出发 S4E13` 实测 1 条 eac3 音轨、0 条字幕，无箭头是正确行为，不是 bug）。

## 详情页「标记已播放」

- 右上角对号 = 标记已播放，走 `vm.togglePlayed()` → `EtknApi.setPlayed(id, played)`（`POST` / `DELETE {u}/PlayedItems/{id}`）。
- **必须「以点击后的目标值回写 UI」**，不能等服务端返回再判断：旧写法先 `withUserData` 读回、服务端没跟着变时图标就不动，表现为「点了没反应」。
- **`mutate` 不能静默失败**：旧实现 `runCatching{}.onSuccess{}` 失败分支什么都不做，用户既看不到变化也看不到提示。现在失败写进 notice 并打日志。

## 选集菜单与分页

- **打开选集要定位到当前集，且上下都要露出**：用 `EpisodePage` 的 `AnchorId = current.id` 拉取，不能用 `start = 0` 固定拉前 N 条 —— 1200+ 集的剧播到 1265 时，前 200 条里根本没有当前集，`indexOfFirst` 返回 -1 就定位到第一条。定位时 `currentIndex - 3` 让当前集不顶在最上面。
- **分页区间的计算基准是「集号」不是「列表下标」**：`firstVisibleItemIndex / RANGE` 在按需加载的长列表里会算错区间（实测 417 集分页 401-420，点 419 后选中项跳到 61-80）。要用 `Item.indexNumber` 算，找不到时再退回滚动位置。
- **`Modifier.weight` 只能用在 `ColumnScope` / `RowScope` 内**：`EpisodeSheet` 不在 scope 里，写 `Modifier.weight(1f)` 会编译失败（`Unresolved reference: weight`）。

## 播放器面板的滚动惯性

- 面板给 `heightIn(max = 画布高 88%)`，内部列表用 `fillMaxSize()`。若面板写成 `wrapContentHeight()`，列表会被量成「刚好装下全部内容」，**LazyColumn 认为自己不可滚动 → 没有 fling/惯性**，长列表只能一格格扒（OPPO 平板实测，手机上因内容少不明显）。

## 播放上报

- `report()` 里所有关键值（`positionMs` / `durationMs` / `completedBySkip`）必须**先快照成局部变量**再构造 payload。切集瞬间 `player` 可能已绑到新集，异步读会读到新集的 0 秒，把上一集记成「播到很早的位置」。
- 切集顺序：先写 `completedBySkip`，再 `stopReporting()`（它会同步发 `Stopped`），最后才 `load()`。`playSource` 内部会把 `completedBySkip` 重置为 false，靠快照隔离。

## 选集定位（EpisodeShelf / EpisodeList）

- **一律按「集号」定位，不按列表下标**。列表是按需分页拉的，下标 ≠ 集号。统一入口：
  - `EpisodeList.ensureNumber(n)` —— 保证第 n 集（1-based）在列表里
  - `EpisodeList.positionOfNumber(n)` —— 第 n 集在已加载列表里的位置（优先 `indexNumber` 精确匹配）
  区间按钮、首尾跳转、切集跟随、打开定位全部走这两个，禁各自算下标。
- **`loadThrough` 不能丢请求**：旧写法 `if (job?.isActive) return` 会把补页请求直接丢弃且不重试，
  导致目标集永远进不了列表、定位退回 0（表现：点 462 集跳到最开头 E80）。
  正确写法：目标写进 `wanted`，正在跑的循环每轮重读 `wanted` 继续跑到新目标。
- **定位用 `scrollToItem` 不用 `animateScrollToItem`**：跨几百集的长距离动画会经过中间所有集，
  视觉上就是「先跳到最前面再跳到目标集」。
- **区间 chip 高亮跟随横条第一格**（`visibleRange`），不绑「当前播放的集」。
  绑当前集会导致「点区间没反应 + 横条回到当前集附近又跳回来」。
- **`EpisodePage` 的 `AnchorId` 与 `StartIndex` 不能混用**：两者同时传时服务端以 `StartIndex` 为准，
  锚点被忽略，续页起点错位。

## 详情页章节

- 只显示 `markerType ∈ {IntroStart, IntroEnd, CreditsStart}` 三类，正片章节全部过滤。
- 三类都没有 → 整块「章节」不渲染（标题也不出现），电影通常如此。
- 有其中一部分就显示那一部分（`SKIP_MARKERS` 常量）。

## 首页「继续观看」

- `Shelf` / `shelf()` 支持 `resetKey`：值变化时横条滚回位置 0（无动画）。
  续播传 `firstOrNull()?.id to size`，保证刚播完同步过来的那部直接出现在最左边。
- **不要**给其它 shelf 传 `resetKey`，否则用户手动滚到的位置会被弹回去。

## 探索页返回保位（踩过三次）

- 真凶是 `LaunchedEffect(kind) { grid.scrollToItem(0) }` —— `LaunchedEffect` 在**每次组合进入**时都会跑，
  从详情返回 → 页面重新组合 → 强制归零，把前面存的位置全覆盖。
- 正确做法：`remember` 记住上一次的 kind，只有**分类真的变了**才回顶；同一个分类重新进入时读 ViewModel 里的位置。
- 教训：修「位置保存」之前，先 grep 有没有别的地方**无条件归零**，否则白改。

## 分页容器（TabPager）

- `beyondViewportPageCount = 0`：只构建当前页。设 1 时会保留相邻页，
  一旦 `currentPageOffsetFraction` 停在 0.5（手势被打断 / 转屏重测 / 动画取消），
  两页各占一半同时画出来 —— 就是「两个页面并排、中间一条线能左右拖」。
- 加归位保护：`snapshotFlow` 监听 `(offsetFraction, isScrollInProgress)`，
  非滚动中且 `abs(fraction) > 0.02` 就 `animateScrollToPage` 到最近整数页。
  `userScrollEnabled = false`，任何非零偏移都是异常，归位安全。

## 宽屏判定必须加设备门槛

- **只看窗口宽度会让手机横屏被当平板**（≥600dp 就进宽屏分支）→ 侧栏 Dock 出现、内容压窄、
  更容易触发上面那种半页错乱。
- 正确：`val wide = layout.wide && isTabletDevice()`，
  `isTabletDevice()` 用 `LocalConfiguration.current.smallestScreenWidthDp >= 600`。
- **必须用 `LocalConfiguration`**，不能用 `LocalContext.resources.configuration`：
  后者不是配置感知的（旋屏拿旧值），Lint 直接报 `LocalContextConfigurationRead` 让构建失败。

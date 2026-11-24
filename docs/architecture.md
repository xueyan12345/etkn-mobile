# ETKN Mobile 架构

单模块 `:app`，无 DI 框架（`AppGraph` 手工装配少量长生命周期单例，冷启动无反射开销）。

```
xyz.etkn.mobile
├── AppGraph / EtknApp / MainActivity     装配、Coil ImageLoader、Compose 根
│                  res/layout/view_texture_player.xml（PlayerView 走 TextureView，画布旋转必需）
├── data
│   ├── net        HttpClients（共享连接池：api/probe/images/media/events）、ApiClient（JSON 规则）、Json 容错读取
│   ├── api        EtknApi（/api/tv 全部路由）、ServerProbe（ETKN 身份识别）、ImageUrls（图片 URL 规则与尺寸分桶）
│   ├── model      Item / MediaSource / MediaStream / Chapter / UserData …（JsonObject 手工映射，类型异常只降级字段）
│   ├── session    SessionManager（登录/恢复/切换/过期）、SessionStore（DataStore）、SecureBox（Keystore AES-GCM）
│   ├── discover   影视探索模型（DiscoverItem/Season/Filter、DiscoverStore 跨页传递）、CloudResources（共享池/re0/频道资源解析，移植自 TV）
│   ├── update     Releases（GitHub 发行解析、版本比较、ABI 选包）、UpdateManager（检查、镜像下载、SHA-256 校验、安装）
│   └── settings   AppSettings（主题、配色来源、首页推荐、解码、片头片尾、倍速、自动下一集；缓存服务器主题色）；UiScale（页面大小，同步 SharedPreferences）
├── player         danmaku/（DanmakuTimeline/Layout 解析与分道、DanmakuFeed 120 s 窗口拉取、DanmakuView 位图缓存绘制）
│                  PlayerActivity（横屏、沉浸、画中画）、VideoSession（双内核选择与切换 + 上报 + 下一集 + 跳过）、mpv/MpvPlayer（libmpv 作为 Media3 Player）、PlaybackPrefetch、ResolvedRedirects、DolbyVision、PlayerScreen（画面与浮层）、PlayerControls、PlayerGestures、PlayerSheets + PlayerPanel（右侧独立窗口面板）、PlayerFactory、PlaybackAddress、CanvasRotationSupport（平板画布旋转机型门控）、PlaybackCache（滑动窗口缓存与下一集预加载）
├── music          MusicConnection（共享 ExoPlayer、ResolvingDataSource 按需解析、上报）、MusicService（MediaSession 后台播放）、Lyrics（LRC）
└── ui
    ├── theme      MaterialExpressiveTheme + HCT 种子配色 + 8 档形状 + 语义 Tokens
    ├── components Artwork / Glass(Haze) / Cards / Sections / Hero / States
    ├── nav        Routes（类型安全）、AppNav、MainShell（外壳与迷你播放器）、NavGraph（目的地 + 转场 + PageFrame）、Dock（手机悬浮玻璃底栏，色块包住图标与文字）；平板 WideNavigationRail
    └── screens    connect · home · library · search · detail · person · music · me
```

## 关键数据流

- **会话**：`SessionManager.state`（Restoring / SignedOut / SignedIn）驱动根界面；切换账号时 `MainShell` 以账号 key 重建，旧页面与请求一起销毁；任何 401 经 `EtknApi` 回调 `expire()`，仅当 generation 与当前会话一致才生效。
- **列表分页**：`PagedViewModel` 以服务端实际返回条数推进 `StartIndex`，按 `TotalRecordCount` 或空页结束，失败停住等待重试（不自动循环）。
- **首页 Hero**：`PersonalRecommendations?Stream=true&Limit=1` 逐条拉取并实时追加（首条到达即渲染），跳过无背景图项；推荐不可用时回退各库最新。按账号存 `HeroSnapshot(lastShownAt + 8 个 id)`，`HERO_TTL_MS = 12h`：未过期沿用（剔除已看完/已删除后不足 8 个则当场补），过期重抽。切账号时 `load()` 只允许同账号沿用上一次的 hero、`showCached()` 按当前账号的快照出图，否则会显示上一个账号的推荐。
- **播放方向**：`applyOrientation(wantPortrait)` 是唯一写方向的地方。入口 `LaunchedEffect(Unit)` 只负责「立刻请求横屏」（所有机型）+「兜底机型置 `forceRotate`」，**不写 `portrait`**；真实方向由服务端 `MediaStreams` 宽高与播放器 `onVideoSizeChanged` **无条件** `applyOrientation` 决定（两者都可能后到，所以不能写成「仅在变化时应用」）。
- **平板画布旋转**：仅 `CanvasRotationSupport.enabled`（OPD2515）走这条路径。系统忽略 `requestedOrientation` 时，用 `TextureView` + 「宽高互换画布 + `requiredSize` + `graphicsLayer{rotationZ=90f}` + `contentAlignment=Center`」把整层（画面/控制条/浮层）一起转。侧边面板 `PlayerSidePanel(rotated)` 的窗口是全屏、内部再套同一块旋转画布；画布坐标与屏幕坐标的对应关系见 `AGENTS.md`。
- **缓存与预加载**：`PlaybackCache` 维护「播放位置 + 窗口」的滑动窗口（时间与大小取小者），窗口随播放前进所以单集内缓冲条恒长。`tickCache`（1 s 驱动）在窗口到达片尾时把 `currentComplete` 置真并触发下一集预加载；下一集开始缓冲即 `nextComplete = true`（气泡亮），不要求整集缓存完。预加载量片头优先：有 `IntroEnd` 时目标「片头结束 + 1 分钟」，配额不够可突破。配额全局共用（当前集剩余 + 下一集 ≤ 设定值）。
- **视频播放**：点击播放即预取 PlaybackInfo（`PlaybackPrefetch`，同时在后台加载两个内核的原生库）→ 服务端 `DirectStreamUrl`（同源校验）→ 内核。不等完整详情：点击的条目先显示标题，详情回来再补章节。内核按片源选（设置「播放内核」可固定）：原盘与 HDR/杜比视界（`PlaybackAddress.isHdr`，看 VideoRange/ColorTransfer/ExtendedVideoType）走 ExoPlayer，其余走 mpv（`MpvPlayer` 是 `SimpleBasePlayer`，控制、手势、弹幕、画中画、上报两边共用；`hwdec=mediacodec-copy,no`、`vo=gpu-next`、ASS/PGS 由 mpv 渲染、CJK 字幕字体链接系统 Noto CJK）；一个内核报错就在同一位置换另一个。ExoPlayer 的数据源经 `ResolvedRedirects` 记住 302 目标（ETKN → 115/CDN），之后的 Range 请求不再回服务端解析，目标失效时回原地址。日志记 PlaybackInfo 与首帧耗时。外挂字幕作为 SubtitleConfiguration 侧载；音轨/字幕名称优先使用服务端 MediaStreams 标题。上报 Playing / 10 s Progress / Stopped，Stopped 走应用级协程。
- **音乐**：队列项 URI 为 `etkn-music://track/<id>`，在 ExoPlayer 加载线程通过 PlaybackInfo 解析为带 `X-Emby-Token/UserId/PlaySessionId/MediaSourceId` 的直链并缓存；`MediaSessionService` 提供通知、锁屏与蓝牙控制；歌词 GET 为空才 POST 在线补取。

- **弹幕**：PlaybackInfo / 上报回复里的 `Danmaku.Available` 决定是否显示入口；本机开关开启且服务可用时，`DanmakuFeed` 按播放位置拉 `I/Danmaku?local=true&session=&start=`（120 s 窗口、提前 40 s 预取、暂停不拉、busy/unavailable 15 s 重试、stale 1 s 重试），上报附 `DanmakuActive`。`DanmakuView` 跟随媒体时钟逐帧绘制，同一文本只排版一次。样式（行数/密度/字号/不透明度/速度/跟随主题色）存本机，「我的 → 弹幕」与播放器内面板共用。
- **玻璃层**：Haze 不会采样自身所在的祖先源，所以页面内悬浮栏（首页顶栏、详情顶栏、列表顶栏、搜索框）各用页面级 `HazeState` 包住自己的滚动内容；Dock/迷你播放器继续采样整个 NavHost。

- **影视探索**：`auth/check_status` 的 `user.can_access_discovery` 决定是否出现「探索」（手机底栏取代「搜索」位，搜索仍在首页顶栏；平板侧栏全部显示）。浏览 `D/query/{movie|series}` 按页（≤500）无限滚动，筛选（排序/类型/年代/评分/隐藏已入库）与 TMDb 搜索走同一请求体；详情补全 `D/series`、`D/media/{kind}/credits`，可订阅影片 / 单季 / 全部缺失季（逐季提交、首错停止）；人物页读 `D/person/{id}/credits`。云资源三源并行搜索、分别显示失败，获取前必须确认（可能消耗积分），只提交不重试；获取进度跟踪（TV 的 AcquireTracker 通知）未移植。
- **应用更新**：启动时（可关）请求 `api.github.com/repos/Alumos/etkn-mobile/releases/latest`，失败则经镜像读该发行的 `SHA256SUMS.txt`。按本机 ABI 选包，默认经 `gh-proxy.com` 加速、失败自动换 GitHub 直连（可手动换源），Android 10+ 写入 MediaStore 下载目录（8–9 申请存储权限写公共 Download），边下边算 SHA-256 并与 GitHub 摘要比对；失败/取消即删除未完成文件。安装走系统安装器（首次需允许“安装未知应用”），签名由系统校验与旧版一致。外部请求用不带任何 ETKN 凭据的独立 OkHttp 客户端。
- **详情页**：整页按头图取色（`rememberArtworkSeed` 取 32 px 缩略图按色相分桶、按彩度加权得种子色，`ArtworkTheme` 生成配色并把表面色调成该色相），手机居中、平板左对齐。头图下三行：评分/年份/分级徽章/状态（`Item.statusLabel` 翻译 TMDb 与 Emby 状态）；时长与文件信息（剧集取下一集的片源：分辨率、HDR、编码、帧率、音频、大小）；类型。播放键的底色就是观看进度，标签后附续播时间；「更多」与搜索在顶栏。剧集区由 `EpisodeList`（与「全部剧集」页共用）分页读取 EpisodePage：季下拉、跳到首/末集、每 20 集一个分段标签（跟随滚动，点哪段就加载到哪段）、横向 16:9 卡片（点剧照播放、长按菜单、点文字进单集），打开时定位到下一集。
- **服务端事件**：`ServerEvents` 读 `Home/Events`（SSE，`HttpClients.events`），首页与详情在前台（`WhileStarted`）订阅，收到变化才重载，不轮询。`IngestNotices` 在前台每分钟读 `IngestUpdates` 游标与已提交获取的 `acquire-status`，按账号分开保存游标、已通知事件与任务（7 天），发「入库通知」频道的系统通知，点按经 `MainActivity.EXTRA_ITEM` → `AppGraph.openRequests` 打开条目。设置「播放」可关闭；Android 13+ 首次开启时请求通知权限。
- **服务端播放偏好**：设置「播放」里的默认音轨 / 默认字幕 / 最新隐藏已观看读写 `ClientPreferences.playback_preferences` 与 `PlaybackPreferences`；服务端据此下发默认轨道，`VideoSession.applyServerTracks` 在每次载入片源后按它选轨（原盘按 PID，外挂字幕按 id，其余按同类流的顺序），之后以用户手选为准。
- **媒体库视图**：库页顶部按库类型给出「全部 / 合集 / 类型 / 电视网 / 出品公司」（`LibraryView`）；后几项是服务端分类卡片（`U/Facets`），点卡片进 `FacetRoute` 按 `{Kind}={Value}` 浏览，可排序；可生成封面的账号长按卡片生成或重取封面。管理员长按 path/list 来源的媒体库可「扫描媒体库」。电影详情显示「所属合集」。目录封面（FolderCover）只用于按目录浏览，本应用没有目录浏览，未接入。
- **播放增强**：拖动进度条时按服务端 `PreviewThumbnails` 清单显示最近一帧（`SeekPreviews`，切片源时重取）。字幕偏移：选中字幕的 `SubtitleOffsetKey` 决定校准值（本次会话调整优先，否则服务端已存值），mpv 用 `sub-delay` 生效，「保存」写回服务端；ExoPlayer 没有字幕延迟接口，此时不显示偏移控件。AI 字幕翻译（`SubtitleTranslation`）与协助转码（`I/Transcode`）尚未接入。
- **通用播放功能**：详情页「预告片」打开 `RemoteTrailers` 链接（交给 YouTube/浏览器）。播放器顶栏「定时关闭」（15–90 分钟或本集播完；本集播完时不连播下一集）与「用其他播放器打开」（`PlaybackAddress.portable` 把令牌、用户与版本放进查询串，只交给用户选中的应用）。设置「后台播放」开启时，`VideoPlaybackService`（MediaSessionService）包住当前内核，离开应用继续出声，媒体通知可控制；划掉应用即停止。
- **离线下载**：`data/downloads/Downloads` 用系统 DownloadManager 下载到应用私有的 Movies/ETKN 目录（无需存储权限，卸载即删），地址为 `PlaybackAddress.portable`（令牌在查询串：请求头会被转发到 302 目标，查询参数不会）；`downloads.json` 按账号记录条目、精简条目与服务端 MediaSource，离线播放走 `VideoSession.startOffline`（不连服务端、不上报）。私密账号不能下载，原盘不支持。入口：详情页下载键、「我的 → 下载」。
- **投屏**：`data/cast/Dlna` 在 Wi-Fi 上发 SSDP 搜索 AVTransport 渲染器，读设备描述取控制地址，`SetAVTransportURI` + `Play`（+ `Seek` 到当前位置）/ `Stop`；本机暂停。需要 `CHANGE_WIFI_MULTICAST_STATE`。播放器顶栏「投屏」，「⋮」里是定时关闭与用其他播放器打开。
- **启动器入口**：长按图标有「搜索」「下载」（静态）和最近两部继续观看（动态），桌面小组件「继续观看」显示最近一部与进度；都由首页的继续观看刷新，私密账号与退出登录时清空。外部入口统一经 `AppGraph.openRequests`（`OpenRequest`）交给外壳。
- **自适应布局**：`WindowLayout`（Compact <600 / Medium / Expanded ≥840 dp）。平板：侧栏（≥840 展开带标签）、首页 Hero 改为横幅并左对齐文案、海报与卡片加宽、网格列宽加大、详情头图改横幅、「我的」与设置居中限宽 720 dp、迷你播放器靠右限宽。
- **返回手势**：清单启用 `enableOnBackInvokedCallback`；NavHost 的 pop 转场即预测性返回的预览动画。每个目的地包在 `PageFrame` 里：不透明背景 + 随转场进度变化的圆角裁剪，避免透明页面缩小时与下层页面叠在一起。
- **页面大小**：`UiScale` 在 `attachBaseContext` 用 `applyOverrideConfiguration` 只覆盖 `densityDpi`，对话框、底部面板、菜单等独立窗口同样生效；改设置后 `recreate()`。窗口尺寸一律用 `windowSizeDp()`（像素 / 当前密度），不用 `Configuration.screenWidthDp`。
- **播放器面板**：音轨/字幕/倍速/版本/选集/弹幕都在右侧独立窗口（`ComponentDialog`）中显示；与长按菜单同一套玻璃配方（玻璃色调 + 细描边 + 浅色文字，轻微压暗）。Android 12+ 由系统合成器做窗口背景模糊（可模糊 SurfaceView 视频），不支持时退化为高不透明度面板。
- **玻璃弹出菜单**：`ui/actions/ItemMenu` 的宿主同时绘制长按菜单和通用 `GlassPopup`（`rememberPopupAnchor()` + `PopupChoice`），首页账号、媒体库排序、歌曲「更多」都用它，与长按菜单同为窗口内 Haze 模糊；独立窗口（对话框、底部面板）仍用 `GlassDialog`/`GlassSheet` 的系统模糊。
- **动效**：页面切换使用主题 `MotionScheme.expressive()` 的弹簧（`defaultSpatialSpec`/`defaultEffectsSpec`），预测性返回拖动可中断、带速度；网格项用 `animateItem()`。列表行用 Expressive `ListItem(onClick)`，滑块用 `SliderState`/`RangeSliderState`。
- **诊断日志**：`data/diagnostics/Diagnostics`。启动时 `logcat -v threadtime,year -f` 只收本 UID 的 `*:E` 与 `ETKN*:I`（256 KB × 8 轮转，7 天前的文件在启动时删除），Java 崩溃同步追加到同格式的 `crash.log`。播放器挂精简的 `PlaybackLog`（解码器、格式、丢帧/欠载、编解码错误），播放错误带堆栈。「我的 → 导出日志」（`LogExportSheet`）选日期与小时段，导出附设备、HDR、相关硬件解码器与异常退出原因，并脱敏。
- **蓝光原盘 ISO**：`player/bluray`（移植自 ETKN TV）。`BlurayMediaSourceFactory` 按 `BlurayTag` 走原盘（`PlaybackAddress.isDisc`：`Container`/`Path` 的 iso 标记或服务端原盘探测的 `BlurayPlaylist`/`TransportStreamId`；普通解析报容器不支持时再按原盘重试一次），`BlurayInput` 以 HTTP Range 读原文件，CI 构建的 `libetkn_bluray.so`（libbluray + libudfread + 仅解封装的 FFmpeg）选正片并拆出 MPEG-TS 流，`DiscExtractor` 用 FFmpeg `extract_extradata` 取回的 VPS/SPS/PPS 作为 csd，并按 SPS 填画面尺寸与色彩（服务端探测值兜底），只暴露主视频（PID 0x1011，丢弃杜比视界增强层与副视频）交给 Media3 硬件解码，轨道名称与默认音轨按 `TransportStreamId` 对应服务端；音频解码器无法启动时自动换音轨，其他解码失败在错误里写明格式与解码器；原盘关闭后播放线程不再调用原生句柄；杜比视界 profile 7：设备有 profile 8 解码器且屏幕支持杜比视界时转 8.1 播放（`DolbyVision`：原盘把增强层 0x1015 中同一时间戳的 RPU 用 `third_party/dovi` 转换后附到基础层帧，最多等 8 帧；MKV/MP4 单轨 P7 由 `DolbyVisionTrackOutput` 丢弃 NAL 63、转换 NAL 62），否则映射为 HEVC HDR10 基础层。
- **条目菜单**：`ui/actions`。`ItemMenu` 在外壳最上层：海报卡片 / 继续观看卡片长按、详情页顶栏“更多”打开；毛玻璃菜单从卡片位置弹出（同窗口 Haze 模糊），刷新/删除/编辑元数据/编辑图片/提取缩略图按 Web 端规则与权限显示。变化经 `AppGraph.itemChanges` 广播，列表就地修补、首页静默重载、详情页重载或退出。
- **毛玻璃**：同窗口浮层（顶栏、底栏、菜单、提示）用 Haze；对话框与底部面板是独立窗口，Android 12+ 用 `GlassDialog` / `GlassSheet`（`FLAG_BLUR_BEHIND` 模糊背后整屏 + 半透明容器）。人物、音乐列表、设置、账号页改为悬浮玻璃标题栏。
- **导航**：`visibleTabs()` 决定底栏/侧栏项目（用户可选，首页固定；手机底栏最多 5 项；探索需权限）；宽屏可选侧边或底部。这些标签页放在 `TabsRoute` 的一个 `HorizontalPager` 里，相邻页会预先组合；`LocalTabActive` 只对正在显示的页为真，搜索页靠它在打开时才聚焦（预先组合的页一旦取得焦点，会把翻页拉到自己那一页）。
- **搜索**：库内无结果且有探索权限时，并发查询 TMDb 电影与剧集（`SearchViewModel`）。

## 性能要点

- 所有 Lazy 列表/网格使用稳定 key 与 contentType；Hero 视差只用 `graphicsLayer`，滚动不触发重新布局。
- 图片宽度按显示尺寸换算物理像素并对齐 10 个桶（160–1920），Coil 内存 22% + 磁盘 384 MB；共享 OkHttp 连接池（HTTP/2）。
- 播放器缓冲 20–90 s、起播 1.2 s；UI 时钟 4 Hz 轮询，控件 4 s 自动隐藏；Compose 关闭源码信息、release 开启 R8 与资源裁剪，按 ABI 拆包。

## 未纳入首版（后续）

获取进度跟踪与入库通知、转码协助、字幕翻译与偏移、寻址缩略图、蓝光 ISO 专用播放、Home/Events SSE 实时失效、入库通知、共享偏好写回、局域网自动发现、应用内更新。

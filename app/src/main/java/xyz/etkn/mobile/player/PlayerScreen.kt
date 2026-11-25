package xyz.etkn.mobile.player

import android.view.ViewGroup
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlinx.coroutines.delay
import xyz.etkn.mobile.data.settings.SkipMode
import xyz.etkn.mobile.player.danmaku.DanmakuView
import xyz.etkn.mobile.player.mpv.MpvPlayer
import xyz.etkn.mobile.ui.theme.Corner
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures

internal val Speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)
private val ResizeModes = listOf(
    AspectRatioFrameLayout.RESIZE_MODE_FIT to "适应",
    AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "裁切填充",
    AspectRatioFrameLayout.RESIZE_MODE_FILL to "拉伸",
)

internal enum class Sheet { None, Audio, Subtitle, Speed, Episodes, Versions, Danmaku, Sleep, Cast }

@Composable
fun PlayerScreen(session: VideoSession, inPip: Boolean, onBack: () -> Unit, onPip: () -> Unit) {
    val ui by session.ui.collectAsStateWithLifecycle()
    val danmakuStyle by session.danmakuStyle.collectAsStateWithLifecycle()
    val danmakuCues by session.danmakuCues.collectAsStateWithLifecycle()
    val themeArgb = MaterialTheme.colorScheme.primary.toArgb()
    val player by session.players.collectAsStateWithLifecycle()
    /**
     * 用户口径（2026-11-26）**设置里换内核后立刻生效**。
     *
     * 监听「播放内核」设置项，值一变就让 session 按新设置重选内核、
     * 从当前位置续播（不退出播放器、不重新进片）。
     *
     * 之前改设置完全没反应，因为 engineFor 只在起播时读设置。
     */
    val settingsFlow = remember { session.settingsEngineFlow() }
    /**
     * 普通 Flow（不是 StateFlow）必须传初值 + 生命周期，
     * 否则 collectAsStateWithLifecycle 编译不过。
     */
    val enginePref by settingsFlow.collectAsStateWithLifecycle(
        initialValue = xyz.etkn.mobile.data.settings.PlayerEngine.Auto,
    )
    var lastEnginePref by remember { mutableStateOf(enginePref) }
    androidx.compose.runtime.LaunchedEffect(enginePref) {
        if (enginePref != lastEnginePref) {
            lastEnginePref = enginePref
            session.reloadEngineFromSettings()
        }
    }
    var controls by remember { mutableStateOf(true) }
    var locked by remember { mutableStateOf(false) }
    /**
     * 用户口径（2026-11-24）**锁定后的解锁按钮要自动消失**。
     *
     * 之前是 `if (locked)` 无条件常驻 —— 锁上之后那颗锁头一直挂在屏幕上，
     * 用户原话「播放界面的小锁头锁定以后不会自动消失」。
     *
     * 现在：锁定后先显示 3 秒，然后淡出；点屏幕任意位置再短暂出现 3 秒。
     */
    var lockShown by remember { mutableStateOf(false) }
    /** 用户口径（2026-11-23）：剧集简介浮层是否打开；打开时控制条不自动隐藏。 */
    var infoOpen by remember { mutableStateOf(false) }
    var resize by remember { mutableIntStateOf(0) }
    var sheet by remember { mutableStateOf(Sheet.None) }
    // 用户口径（2026-11-23）：去掉重力感应横竖屏，改成「按视频宽高比自动判断」。
    // 宽 > 高 → 横屏；高 > 宽 → 竖屏。切下一集时视频尺寸会变，这里监听 videoSize 重判，
    // 不用退出播放器。手动旋转按钮仍然可以临时覆盖，换集后会重新跟随视频比例。
    val context = androidx.compose.ui.platform.LocalContext.current
    /**
     * 用户口径（2026-11-23 修「所有视频都默认竖屏」）：
     * 原来写 `context as? android.app.Activity` —— Compose 里 LocalContext 常常是
     * ContextWrapper（主题包装 / 缩放包装），强转直接得 null，于是 applyOrientation()
     * 静默什么都不做，方向永远是 Manifest 的默认值 = 竖屏。
     * 改成沿 ContextWrapper 链一路往上找真正的 Activity。
     */
    val activity = remember(context) { context.findActivity() }
    /**
     * 用户口径（2026-11-23）：Compose 里读设备方向必须用 LocalConfiguration，
     * 直接读 context.resources.configuration 会拿到过期值（lint 也报了这条）。
     * 旋转判据依赖「当前真实方向」，读到旧值就永远判错。
     */
    val localConfig = androidx.compose.ui.platform.LocalConfiguration.current
    // true = 竖屏（portrait），false = 横屏（landscape）
    // 用户口径（2026-11-23 修「横屏视频点进去还是竖屏」）：
    // 这里原来是 false。而下面所有调用点都写成 `if (wantPortrait != portrait) applyOrientation(...)`，
    // 于是横屏视频 wantPortrait=false == portrait=false → 条件不成立 → 一次都没应用，
    // Activity 就一直停在 Manifest 的 unspecified（= 设备当前方向 = 竖屏）。
    // 改用 nullable 表示「还没应用过」，第一次一定应用。
    var portrait by remember { mutableStateOf<Boolean?>(null) }
    /**
     * 用户口径（2026-11-23）OPPO Pad Mini 专项：**进播放器立刻就是横屏**。
     *
     * 之前初值是 false，要先发系统方向请求、等 120ms 读回、确认系统不认才开兜底，
     * 结果用户看到的是「先竖着、一闪、再转」甚至根本不转。
     *
     * 现在初值直接给 true：一进播放器就按横屏画布绘制（不依赖系统方向）。
     * 如果系统其实认了横屏请求（手机上就是这样），读回校验后把兜底关掉，
     * 交还给系统方向 —— 两边都不冲突。
     */
    // 用户手动覆盖过方向就不再自动跟随，直到换集重置
    var manualOrientation by remember { mutableStateOf(false) }
    /**
     * 用户口径（2026-11-23）：系统不认方向 -> 整层 90° 旋转（配合 TextureView 才有效）。
     * 初值 false：先让系统试；确认系统没转过去才打开。
     */
    /**
     * 用户口径（2026-11-23）：只有确认需要兜底的机型才允许画布旋转。
     * 手机 / 普通平板系统方向正常，误开就会把画面压成中央小方块。
     */
    /**
     * 用户口径（2026-11-26）：当前源是否 Dolby Vision / HDR。
     *
     * ⚠️ 这两个状态**只用于打日志和选渲染器**，绝对不能用来关画布旋转。
     * 原因见下面 needsCanvasRotation 的注释（v1.4.5 踩过的坑）。
     */
    val isDv by session.isDolbyVision.collectAsStateWithLifecycle()
    val isHdr by session.isHdr.collectAsStateWithLifecycle()

    /**
     * 用户口径（2026-11-26）**画布旋转绝不能被 DV/HDR 关掉（v1.4.5 的教训）**。
     *
     * v1.4.5 我为了让 DV 片源不出黑屏、也为了让「画面和 UI 方向一致」，
     * 把 `needsCanvasRotation` 改成 `canvasCapable && !isDv`。结果平板上三个片子全坏：
     *   · 走马上任：横屏了但画面方向不对
     *   · 尚公主：  横屏了但黑屏（进度条还在走）
     *   · 谷雨：    整体竖屏，怎么都转不过来
     *
     * 为什么会这样（日志实证，每一份都有）：
     *   ETKN-Rotate: round=1/2/3 want=L real=P ok=false
     *   ETKN-Rotate: compat-switches ... setIgnoreOrientationRequest=false
     *   → 请求横屏三次全部失败，系统就是不转
     *   → 这台平板（Android 16 大屏 + ColorOS）**唯一**的横屏手段就是画布 rotationZ
     *
     * 所以：画布旋转是这台机器横屏的**生命线**，只能无条件保留。
     * DV/HDR 的问题必须在「渲染器 / 解码路径」层面解决，
     * 不能靠关掉画布旋转来绕。
     */
    val canvasCapable = remember { CanvasRotationSupport.enabled }
    /**
     * 用户口径（2026-11-26）**mpv 内核不用画布旋转**。
     *
     * 实测日志：
     *   21:54:04 HDR → 切 mpv
     *   21:54:01 round=1 want=P real=P ok=true gate=false canvasRotate=false  ← 此时没旋转
     *   21:54:05 ready on mpv after 1569 ms                                    ← 正常出画
     *
     * mpv 的画面走它自己的 SurfaceView（独立硬件图层，不参与 View 树变换），
     * 外层再 rotationZ=90 只会让 UI 转、画面不转 → 更乱。
     * 所以 mpv 激活时把画布旋转让出来，由 mpv 自己把画面铺满屏幕。
     */
    /**
     * 用户口径（2026-11-26）**mpv 也走画布旋转，但 video-rotate 必须为 0**。
     *
     * 日志铁证（1.4.8，两个错误叠在一起）：
     *   ETKN-Mpv: video-rotate=90           ← 我让 mpv 自己转了 90°
     *   round=1 want=L real=P ok=false gate=false canvasRotate=true
     *                                        ← 画布也在转（rotated=true）
     *   → **两层叠加 = 界面横排 + 画面又转 90° = 画面躺着的（用户截图）**
     *
     * 现在的做法：
     *  · 画布旋转照常开（rotated=true）→ 界面按横屏排布、SurfaceView 的**布局框**也被转
     *  · mpv 的 video-rotate 设 **0**（不再让它自己转）
     *  两层只转一次 → 界面和画面方向一致。
     *
     * （SurfaceView 的内容不参与 View 树旋转，但它的布局框会跟着转，
     *   所以框已经是横屏的，内容按框绘制即可，不需要再转。）
     */
    val needsCanvasRotation = canvasCapable
    /**
     * 用户口径（2026-11-23）**起播方向必须一开始就是横屏**。
     *
     * 之前初值恒为 false：起播先按竖屏渲染 → 300ms 后校验失败才置 true
     * → 用户明显看到「先竖屏、再跳成横屏」。
     *
     * 正确逻辑（用户明确）：
     *  ① 起播瞬间直接横屏（绝大多数片源本来就是横屏，这一步就对了）
     *  ② 等播放器给出视频真实尺寸后判断方向
     *  ③ 如果确实是竖屏视频 → 再跳回竖屏
     *
     * 所以需要在兜底的机型上初值直接给 true，不等校验。
     */
    /**
     * 用户口径（2026-11-26）：DV 源不做画布旋转，初值也跟着走。
     * （needsCanvasRotation 现在已排除 DV，这里保持一致，避免起播先闪一下旋转态。）
     */
    var forceRotate by remember { mutableStateOf(CanvasRotationSupport.enabled) }
    /**
     * 用户口径（2026-11-23）OPPO Pad Mini 专项：这台平板上「旋转屏幕」按钮完全无效，
     * 只能跟随系统方向。SenPlayer / AfuseKit 同样如此 → 不是我们独有的 bug，
     * 是这台设备（Android 16 大屏 + ColorOS）的取向限制。
     *
     * 根因（Android 16 官方行为变更）：宽 ≥600dp 的大屏设备上，系统**默认忽略**
     * 应用通过 requestedOrientation 发的方向请求 —— 所以手机有效、平板无效。
     * 破解必须先关掉这个「忽略」开关，再发方向请求。
     *
     * 逐级兜底（每一步失败都继续往下试，绝不静默放弃）：
     *  ① setShouldDismissRequestedOrientation 相关 API（Android 16 / API 36）
     *  ② Activity.setRequestedOrientation（传统路径，配合 ① 才在大屏生效）
     *  ③ 都无效时，退化为「旋转画面」：给 PlayerScreen 一个 90° 旋转标记，
     *     不改系统方向、只把视频画面转过来（任何设备都能强制横看）
     */
    /**
     * 用户口径（2026-11-23）旋转按钮/自动横屏 —— **第三轮，这次找到真错了**。
     *
     * 前几轮一直不生效，因为判据用了 `activity.requestedOrientation` 读回值来判断
     * 「系统是否接受了我的方向请求」。但那个 getter **永远返回我自己刚设置的值**，
     * 跟系统到底有没有真的转过去完全无关 → `accepted` 恒为 true →
     * 画面旋转兜底永远关着 → 平板上系统不认方向时就彻底没反应。
     *
     * 正确判据：读 `resources.configuration.orientation`，那是**屏幕当前真实方向**。
     *   发请求 → 等 350ms（系统转屏是异步的）→ 看真实方向对不对
     *   对上了 → 系统认了，交还给系统方向
     *   没对上 → 系统忽略（大屏设备）→ 打开画面级 90° 旋转兜底
     */
    /**
     * 用户口径（2026-11-23）旋转 —— **最终方案：只走系统方向，不做画面旋转**。
     *
     * 为什么放弃画面旋转：实测给视频层套 graphicsLayer rotationZ=90° 之后，
     * SurfaceView 是独立硬件图层，Compose 转不动它，画面变成「竖排文字」（用户截图证实）。
     *
     * 系统方向在大屏设备上会被忽略（Android 16 行为变更），所以要做的是
     * **把请求发得更强硬**，而不是自己转画面：
     *  ① 用 USER_LANDSCAPE / USER_PORTRAIT（比 LANDSCAPE 更强制，部分 ROM 认这个）
     *  ② 一次性把 activity 的所有方向相关锁定都清掉再设
     *  ③ 300ms 后检查真实方向，没转过去就重发一次（有些设备首次会被丢弃）
     */
    val applyOrientation: (Boolean) -> Unit = { wantPortrait ->
        portrait = wantPortrait
        val act = activity
        val target = if (wantPortrait) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
        }
        val apply = {
            runCatching { act?.requestedOrientation = target }
            runCatching {
                act?.window?.attributes = act.window.attributes.apply {
                    screenOrientation = target
                }
            }
        }
        apply()
        val want = if (wantPortrait) android.content.res.Configuration.ORIENTATION_PORTRAIT
        else android.content.res.Configuration.ORIENTATION_LANDSCAPE
        fun verify(round: Int) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                /**
                 * 用户口径（2026-11-23）**这里之前用 localConfig 读方向，是错的**。
                 *
                 * localConfig（LocalConfiguration.current）是 Compose 组合期的快照，
                 * 在 postDelayed 回调里不会刷新 —— 日志里出现
                 * `real=L ok=false`（真实是横屏却判失败）就是它读到了旧值，
                 * 于是错误地打开了画布旋转，把本该正常的手机画面压成小方块。
                 *
                 * 改成读 Activity 的实时配置：那是系统当前真实方向。
                 */
                val real = act?.resources?.configuration?.orientation
                val ok = real == want
                /**
                 * 只有【需要兜底的机型】才允许开画布旋转。
                 * 手机 / 其他平板系统方向本来就正常，一旦误开就会把画面压成小方块
                 * （用户实测：手机播放变成中央小窗）。所以这里必须机型门控。
                 */
                /**
                 * 用户口径（2026-11-23）：这里的职责变了。
                 *
                 * 起播时 forceRotate 已经是 true（需要兜底的机型直接横屏），
                 * 所以这里只在「视频确实是竖屏」时才需要把它关掉（切回竖屏）。
                 * 横屏视频一律保持 true，不再因为系统方向校验失败而反复切。
                 */
                if (wantPortrait) forceRotate = false
                else if (!ok && needsCanvasRotation) forceRotate = true
                // 注意：real 必须打真实读到的方向，不能用 ok 反推（之前这里打错了，日志自相矛盾）
                val realText = when (real) {
                    android.content.res.Configuration.ORIENTATION_PORTRAIT -> "P"
                    android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "L"
                    else -> "?"
                }
                android.util.Log.i(
                    "ETKN-Rotate",
                    "round=$round want=${if (wantPortrait) "P" else "L"} " +
                        "real=$realText ok=$ok gate=$needsCanvasRotation canvasRotate=$forceRotate",
                )
                if (!ok && round < 3) {
                    apply()
                    verify(round + 1)
                }
            }, 300L)
        }
        verify(1)
    }

    /**
     * 用户口径（2026-11-23）：打开播放器瞬间默认就是横屏。
     * 绝大多数片源是横屏，先按横屏起，等服务端/播放器给出真实尺寸后
     * 如果判断是竖屏视频，再切成竖屏。这样避免「先竖屏站着，过一会儿才转过去」的闪动。
     * 这个 LaunchedEffect 必须声明在「服务端尺寸判断」之前，保证先执行。
     */
    LaunchedEffect(Unit) {
        /**
         * 用户口径（2026-11-23）诊断：v1.2.5 实机日志里 ETKN-Rotate 出现 0 次，
         * 说明这条自动方向逻辑要么没跑、要么跑到了却没进 verify() 打日志的分支。
         * 这里在入口无条件打一行，把「有没有被调用 / activity 是否为 null / 当前真实方向」
         * 一次性暴露出来，下一次日志就能定位到底断在哪一层。
         */
        val act = activity
        // 用 Compose 的 LocalConfiguration（配置感知），不读 context.resources（可能过期）
        val realNow = localConfig.orientation
        val realText = when (realNow) {
            android.content.res.Configuration.ORIENTATION_PORTRAIT -> "P"
            android.content.res.Configuration.ORIENTATION_LANDSCAPE -> "L"
            else -> "?"
        }
        android.util.Log.i(
            "ETKN-Rotate",
            "enter auto: manual=$manualOrientation activity=${act != null} real=$realText " +
                "smallestDp=${localConfig.smallestScreenWidthDp}",
        )
        /**
         * 用户口径（2026-11-24）**修「OPPO 平板不再自动分辨视频方向」**。
         *
         * 这里原来无条件调 applyOrientation(false)，即「永远先横屏」。
         * 它的副作用不只是发方向请求 —— 还会把 portrait 状态写成 false。
         *
         * 而「服务端尺寸预判」那个 LaunchedEffect 也在写 portrait。
         * 两者存在时序竞争：
         *  · Unit 这个后跑 → portrait 被覆盖成 false（横屏）
         *  · 竖屏视频永远翻不回来 → 用户看到的就是「不自动判断方向了」
         *
         * 现在拆开职责：
         *  · 这里只负责「画布先按横屏布局」这个视觉兜底（只动 forceRotate）
         *  · 真实方向一律由「服务端尺寸预判」和 onVideoSizeChanged 决定
         *    （它们会正确设置 portrait 并调 applyOrientation）
         *  · 不再在没有真实尺寸的时候覆盖 portrait
         */
        /**
         * 用户口径（2026-11-24 第二次修正）**手机起播也要立刻横屏**。
         *
         * 上一版我只给兜底机型（平板）设了 forceRotate，手机什么都没做：
         *  · 平板上「画布旋转」立刻生效 → 一进就是横屏 ✓
         *  · 手机上系统方向还没转 → 第一眼看到的是竖屏，之后才慢慢转过去 ✗
         *    用户原话：「打开看到的第一眼是竖的，然后才恢复横屏」
         *
         * 所以这里必须对【所有机型】都立刻请求横屏：
         *  · 现在就用 applyOrientation(false) 发方向请求
         *    → 手机系统立刻转到横屏，第一帧就是横的
         *  · 但绝不能让它覆盖 portrait 状态（上一版的错）：
         *    所以调用前把 portrait 记成「未定」，让服务端尺寸/播放器回调
         *    拿到真实尺寸后可以无条件重新应用
         */
        if (!manualOrientation) {
            // 先请求横屏（手机系统立刻转；平板系统不认，由画布旋转兜底）
            act?.let {
                runCatching {
                    it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
                }
            }
            // 兜底机型：画布也立刻按横屏布局，避免「先竖后横」的一帧
            if (needsCanvasRotation) forceRotate = true
        }
    }

    /**
     * 用户口径（2026-11-23，修「记忆上次亮度不生效」）：
     * 原来只在拖动手势里写 SharedPreferences（screen_brightness），
     * 进播放器时从来没人读回来应用 → 每次都是系统默认亮度。
     * 这里进播放器就恢复上次的亮度。
     */
    LaunchedEffect(Unit) {
        val act = activity ?: return@LaunchedEffect
        val saved = context
            .getSharedPreferences("etkn_player", android.content.Context.MODE_PRIVATE)
            .getFloat("screen_brightness", -1f)
        if (saved in 0.01f..1f) {
            act.window?.attributes = act.window.attributes.apply { screenBrightness = saved }
        }
    }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var gesture by remember { mutableStateOf<GestureFeedback?>(null) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(player) {
        val current = player
        playing = current?.isPlaying == true
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }

            /**
             * 用户口径（2026-11-23）：按视频宽高比自动决定横竖屏。
             * 这个回调在每次载入新视频时都会触发（包括不退出播放器直接切下一集），
             * 所以切集后方向会重新跟随新视频，不用手动干预。
             */
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                if (manualOrientation) return
                val w = videoSize.width
                val h = videoSize.height
                if (w <= 0 || h <= 0) return
                val wantPortrait = h > w
                android.util.Log.i(
                    "ETKN-Rotate",
                    "video-size: ${w}x$h portrait=$wantPortrait currentPortrait=$portrait",
                )
                // 用户口径（2026-11-24）：播放器给的是最权威的尺寸，无条件应用
                applyOrientation(wantPortrait)
            }
        }
        current?.addListener(listener)
        onDispose { current?.removeListener(listener) }
    }

    /**
     * 用户口径（2026-11-23，修「横屏视频点进去默认竖屏」）：
     * 不靠播放器回调猜方向 —— ETKN 的 MediaStreams 里就有 Width/Height
     * （实测全库 47855 条，横屏 33158 / 竖屏 14322 / 正方 375，覆盖 100%）。
     * 进播放器就用服务端已知尺寸直接定方向，首帧前就已经是对的，
     * 不再出现「先竖屏闪一下再转」。
     *
     * 优先级：服务端尺寸 > 播放器 onVideoSizeChanged（服务端没给时才回落）。
     */
    LaunchedEffect(ui.item?.id, ui.sourceIndex) {
        if (manualOrientation) return@LaunchedEffect
        // MediaSource.video 是现成的扩展（streams 里第一个 Type=Video）
        val v = ui.sources.getOrNull(ui.sourceIndex)?.video
        val w = v?.width ?: 0
        val h = v?.height ?: 0
        android.util.Log.i(
            "ETKN-Rotate",
            "server-size: ${w}x$h portrait=${h > w} currentPortrait=$portrait",
        )
        if (w > 0 && h > 0) {
            val wantPortrait = h > w
            /**
             * 用户口径（2026-11-24）：这里必须**无条件应用**，不能只在「变了」的时候应用。
             *
             * 之前写的是 `if (portrait == null || wantPortrait != portrait)`。
             * 如果 portrait 已被别处错误写成 false（比如那个无条件横屏），
             * 而真实也是横屏，这个条件就不成立 → 一次都不调用 applyOrientation，
             * 于是 forceRotate 也不会被正确更新（竖屏视频翻不回来）。
             *
             * 现在改成：只要服务端给了真实尺寸，就应用一次，让它成为最终依据。
             */
            applyOrientation(wantPortrait)
        }
    }
    // 切集时重置手动覆盖，让新视频自己决定方向
    LaunchedEffect(ui.item?.id) { manualOrientation = false }
    LaunchedEffect(ui.finished) { if (ui.finished) onBack() }
    // Poll the clock only as fast as the UI needs it: 4 Hz while the controls show it, else 1 Hz
    // (enough for the skip-intro / credits segments).
    LaunchedEffect(player) {
        val current = player ?: return@LaunchedEffect
        var lastCacheTick = 0L
        while (true) {
            position = current.currentPosition.coerceAtLeast(0)
            duration = current.duration.takeIf { it > 0 } ?: 0
            buffered = current.bufferedPosition
            /**
             * 用户口径（2026-11-24）**缓冲条更新滞后的根因在这里**。
             *
             * 之前每 5 秒才驱动一次缓存窗口。网速 69 MB/s 时，
             * 一个 32MB 分片 0.5 秒就下完了，但界面要等 5 秒才更新一次 ——
             * 用户看到的就是「网速很高、缓冲条半天不动，然后突然蹦一大截」。
             *
             * 现在：
             *  · 驱动间隔 5s → 1s（更跟手）
             *  · 而且每次都顺便把 cachedPosition 刷到 UI（下面 tickCache 内部会算）
             */
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastCacheTick > 1_000L) {
                lastCacheTick = now
                session.tickCache(position, duration)
            }
            delay(if (controls || gesture != null) 250 else 1_000)
        }
    }
    // Auto-hide controls after 4 s of inactivity while playing.
    /**
     * 用户口径（2026-11-23）：自动隐藏要分清「进度条界面」和「弹层」。
     *
     * 之前这里只判断了 `sheet == Sheet.None`，但那个剧集简介浮层是
     * PlayerControls 内部的局部状态，不在 Sheet 枚举里 ——
     * 所以简介弹出来时，这条逻辑照旧 4 秒把整个控制条（连同简介）收掉，
     * 用户还没看完就消失了。
     *
     * 现在：控制条自动隐藏只在「没有打开任何弹层」时执行。
     * 简介浮层通过 [infoOpen] 上报自己的打开状态，打开期间不自动隐藏。
     */
    LaunchedEffect(controls, lastInteraction, playing, sheet, infoOpen) {
        if (controls && playing && sheet == Sheet.None && !infoOpen && !locked) {
            delay(4_000)
            controls = false
        }
    }
    val segment = remember(position, ui.segments, ui.skipMode) { session.segmentAt(position) }
    /**
     * 用户口径（2026-11-23）：自动跳过要加 5 秒倒计时。
     * · 选「自动跳过」时，跳过按钮照常显示，按钮内部用色块从左往右填充当进度
     * · 5 秒填满 → 才真正触发跳过
     * · 用户中途点了按钮 → 立即跳过（取消倒计时）
     * · 离开这个片段（倒计时没走完）→ 取消，不跳
     */
    var skipCountdown by remember { mutableFloatStateOf(0f) }   // 0f..1f
    /**
     * 用户口径（2026-11-23）修「起播时闪一下跳过片头」：
     * 之前只要 segment 有值就立刻起倒计时 —— 起播位置是 0 秒，正好落在片头区间，
     * 于是按钮和色块先闪一下才开始跳。
     *
     * 现在加前置条件：**必须已经在播且不在起播缓冲中**，才显示按钮和起倒计时。
     * 起播阶段（ui.loading / 首帧未出）完全不显示，避免闪。
     */
    /**
     * 用户口径（2026-11-23）**「取消」要真的取消掉整组按钮**。
     *
     * 之前的问题：点「取消」只是调 session.cancelAutoSkip(s) 设了个标志位，
     * 而 skipCountdown 这个 UI 状态没人清、segment 也没变 →
     *  ① 胶囊 + 「取消」两个字一直留在屏幕上不走
     *  ② 倒计时照样跑到 100%，数字停在「1s」不动
     * 用户看到的就是「固定在 1 秒，取消也不消失」。
     *
     * 现在用一个独立的 dismissed 状态：点取消 → 立刻把倒计时清零 + 标记已忽略这一段，
     * 整组按钮马上消失，并且这一段不会再弹出来（换段/换集才复位）。
     */
    var skipDismissed by remember { mutableStateOf(false) }
    // 换到别的片段 / 换集时复位「已忽略」
    LaunchedEffect(segment?.kind, ui.item?.id) { skipDismissed = false }

    val skipVisible = segment != null && !ui.loading && ui.error == null &&
        position > 500 && !skipDismissed
    LaunchedEffect(segment, skipVisible) {
        if (!skipVisible) {
            skipCountdown = 0f
            return@LaunchedEffect
        }
        if (ui.skipMode != SkipMode.Auto) {
            skipCountdown = 0f
            return@LaunchedEffect
        }
        skipCountdown = 0.01f
        // 5 秒色块填充：每 50ms 走 1%，5 秒正好 100%
        repeat(100) { step ->
            delay(50)
            // 中途点了取消 → 立刻停止填充，保持 0（按钮已由 skipDismissed 隐藏）
            if (skipDismissed) {
                skipCountdown = 0f
                return@LaunchedEffect
            }
            skipCountdown = (step + 1) / 100f
        }
        // 填满才真正跳
        if (!skipDismissed) session.autoSkip(segment)
    }
    // Double-tap seek feedback clears itself; drag feedback is cleared on release.
    LaunchedEffect(gesture) {
        val shown = gesture
        if (shown is GestureFeedback.Seek) {
            delay(800)
            if (gesture === shown) gesture = null
        }
    }
    val touch = { lastInteraction = System.currentTimeMillis() }

    /**
     * 用户口径（2026-11-23）OPPO Pad Mini 兜底（重做）：
     *
     * 上一版用 graphicsLayer rotationZ=90° 只转单个图层 —— 转完画面跑出可视区、
     * 也没交换宽高，所以用户看到的还是「竖窗口里塞横屏画面」。
     *
     * 正确做法：把**整个播放器 UI** 放进一个「逻辑尺寸 = 屏宽高互换」的容器，
     * 再把容器整体转 90° 铺满屏幕。布局按横屏算、画面也是横的，
     * 完全不依赖系统 orientation。
     */
    /**
     * 用户口径（2026-11-23）**平板旋转最终方案：TextureView + 整层 90° 旋转**。
     *
     * 前两次为什么失败：
     *  · 只转视频层 → PlayerView 用 SurfaceView 输出，SurfaceView 是独立硬件合成层，
     *    不在 View 树里绘制，Compose 转不动它 → 画面变「竖排文字」。
     *  · 改用系统 requestedOrientation → Android 16 大屏直接忽略这个请求。
     *
     * 这次两条一起做（缺一不可）：
     *  ① Exo 的 PlayerView 用 XML 声明 surface_type=texture_view（见 view_texture_player.xml）
     *     → 画面变成普通纹理，绘制在 View 树内，可以被变换
     *  ② 整层（视频+控制条+弹幕）按「宽高互换画布」+ rotationZ=90° 绘制
     *     → 竖屏窗口里看到完整横屏画面，控件位置也正确
     *
     * 仅当系统确实不转（forceRotate=true）时启用；正常设备仍是普通全屏。
     */
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black),
        // 用户口径（2026-11-23）关键：旋转画布必须**居中**。
        // 之前是左上角摆放 + 手动平移，那个平移量是按「画布已居中」推的，
        // 实际画布在左上角 → 结果整体偏左、上方被截（用户截图证实）。
        // 用 contentAlignment=Center 让画布天然居中，绕自身中心转 90° 后
        // 正好铺满整屏，一个平移都不需要。
        contentAlignment = Alignment.Center,
    ) {
        // 双重门控：机型允许 + 系统确实没转成功
        val rotated = forceRotate && needsCanvasRotation
        /**
         * 用户口径（2026-11-23）平板尺寸专门适配。
         *
         * 设备实测：竖屏 1680×2520（比例 3:2），横屏 2520×1680。
         * 横屏画布的宽 = 屏幕的高（maxHeight），高 = 屏幕的宽（maxWidth），
         * 但必须**扣掉安全区**（状态栏 / 挖孔 / 导航栏），否则内容会顶到屏幕边缘
         * 或被系统栏压住 —— 之前画面上下被截就是这个原因之一。
         */
        /**
         * 用户口径（2026-11-23）**画布尺寸就用屏幕原始宽高，不扣安全区**。
         *
         * 之前扣了 safeDrawing（状态栏/导航栏），画布变小 → 视频跟着变小
         * → 旋转后两侧出现黑边（实测左 490 + 视频 700 + 右 490）。
         *
         * 视频是 FIT 模式，它自己会在画布内等比留黑边，
         * 画布只要给足整屏尺寸即可。系统栏本来就隐藏了（播放器全屏沉浸）。
         */
        val canvasW = if (rotated) maxHeight else maxWidth
        val canvasH = if (rotated) maxWidth else maxHeight
        Box(
            Modifier.then(
                if (rotated) {
                    /**
                     * 用户口径（2026-11-23）**左右黑边的真正原因**。
                     *
                     * 这里原来写 .size(canvasW, canvasH)。但 Compose 的子元素尺寸会被
                     * 父容器 constraints 钳制：画布要求 2520×1680，父容器 maxWidth=1680，
                     * 于是画布被压成 1680×1680。
                     *
                     * 实测验证（严丝合缝）：
                     *  · 画布被压成 1680×1680
                     *  · 视频 3840×1600（2.4:1）fit 进去 → 宽 1680、高 1680/2.4 = 700
                     *  · 旋转后视频宽 700，两侧黑边各 (1680-700)/2 = 490
                     *  · 截图实测：左 490 + 视频 700 + 右 490 = 1680 ✓
                     *
                     * 换成 requiredSize：明确告诉 Compose「不受父约束限制」，
                     * 画布才能真的拿到 2520×1680，视频宽度铺满后左右黑边消失。
                     */
                    Modifier
                        .requiredSize(canvasW, canvasH)
                        .graphicsLayer { rotationZ = 90f }
                } else {
                    Modifier.fillMaxSize()
                },
            ),
        ) {
        when (val core = player) {
            null -> Unit
            // mpv draws video and subtitles into its own SurfaceView.
            is MpvPlayer -> key(core) {
                /**
                 * 用户口径（2026-11-26）**mpv 的画面旋转由它自己做**。
                 *
                 * 这台平板系统不给横屏，靠外层画布 rotationZ=90 才能横过来。
                 * 但 mpv 的画面是 SurfaceView 独立图层，外层旋转对它完全无效
                 * （日志实证：canvasRotate=true 时 mpv 画面依然不对）。
                 *
                 * 所以把旋转交给 mpv 的 video-rotate：
                 * 需要横屏时转 90°，竖屏视频时不转。
                 */
                /**
                 * 用户口径（2026-11-26）**mpv 不做 video-rotate**。
                 *
                 * 上一版给 mpv 设了 video-rotate=90°，结果画面转了但界面没转：
                 * 截图实证「界面竖排、画面横躺、字幕竖排」——方向更乱了。
                 *
                 * 正确理解：
                 *  · 外层画布已经在做 rotationZ=90（为了让整机横过来）
                 *  · mpv 的 SurfaceView 是独立图层，外层旋转对它无效
                 *  · 如果再让 mpv 自己转，就变成「只转画面不转界面」
                 *
                 * 所以正确做法是：**把画布的横屏尺寸直接给 mpv**，
                 * 让它在横屏坐标系里绘制画面 —— 界面和画面就都对了。
                 * 下面 modifier 用 canvasW/canvasH 保证尺寸是横屏的。
                 */
                /**
                 * 用户口径（2026-11-26）：mpv 的旋转角度必须和外层画布一致。
                 * 外层 rotationZ=90（横屏）→ mpv video-rotate 也 90；
                 * 竖屏视频（portrait==true）→ 两者都不转。
                 */
                /**
                 * 用户口径（2026-11-26）**mpv 的 video-rotate 必须跟画布同角度**。
                 *
                 * 两版实测把关系定死了（用户横过来看确认的方向）：
                 *  · 1.4.8：画布 rotated=false（UI 竖排）+ video-rotate=90（画面转）
                 *          → 画面横、UI 竖 ✘
                 *  · 1.4.9：画布 rotated=true（UI 横）+ video-rotate=0（画面不转）
                 *          → UI 横、画面躺 ✘
                 *
                 * 结论：**两层都要转**。
                 * 为什么：SurfaceView 的内容不参与 View 树旋转，
                 * 画布转只让它的**布局框**变成横屏，内容仍然按原始方向绘制；
                 * 所以内容必须靠 video-rotate 再转一次，角度与画布相同。
                 */
                val mpvRotate = if (rotated) 90 else 0
                AndroidView(
                    factory = { (core.view.parent as? ViewGroup)?.removeView(core.view); core.view },
                    update = {
                        core.setResize(resize)
                        core.setVideoRotate(mpvRotate)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            /**
             * 用户口径（2026-11-26）**不再按 isDv 重建视图**。
             *
             * v1.4.5 把 isDv 放进 key，是想「识别到 DV 就重建为 SurfaceView」。
             * 但 SurfaceView 与画布旋转冲突（画面不跟着转），且系统不给横屏，
             * 结果三个片子全坏。现在渲染器不再按 DV 分叉，key 也就不需要它了。
             */
            else -> key(core) {
                AndroidView(
                    factory = { ctx ->
                        /**
                         * 用户口径（2026-11-23）渲染方式也按机型分：
                         *  · 需要画布旋转的设备（OPPO Pad Mini）→ TextureView
                         *    只有 TextureView 参与界面树变换，能被旋转；
                         *  · 其他设备（手机/普通平板）→ 保持 SurfaceView
                         *    性能更好、HDR 直出更稳，且系统方向本来就正常，
                         *    完全不需要旋转能力。
                         */
                        /**
                         * 用户口径（2026-11-26）**渲染器一律跟着画布旋转走**。
                         *
                         * v1.4.5 曾在这里按 isDv 切 SurfaceView（想让 DV 出画），
                         * 但 SurfaceView 不参与 View 树变换 → 画布转了画面不转，
                         * 而系统又不给横屏 → 三个片子全坏。
                         *
                         * 现在：只要需要画布旋转就用 TextureView，不再按 DV 分叉。
                         * DV 黑屏改由「解码路径」解决（见 VideoSession 的 DV 处理）。
                         */
                        val useTexture = needsCanvasRotation
                        val pv = if (useTexture) {
                            android.view.LayoutInflater.from(ctx)
                                .inflate(xyz.etkn.mobile.R.layout.view_texture_player, null) as PlayerView
                        } else {
                            PlayerView(ctx)
                        }
                        pv.apply {
                            useController = false
                            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            setShutterBackgroundColor(android.graphics.Color.BLACK)
                            setKeepContentOnPlayerReset(true)
                            setKeepContentOnPlayerReset(true)
                            // Plain white text with a dark outline instead of the system caption box
                            // (black background); ASS/SSA keep their own embedded styling.
                            subtitleView?.apply {
                                setStyle(
                                    CaptionStyleCompat(
                                        android.graphics.Color.WHITE,
                                        android.graphics.Color.TRANSPARENT,
                                        android.graphics.Color.TRANSPARENT,
                                        CaptionStyleCompat.EDGE_TYPE_OUTLINE,
                                        android.graphics.Color.BLACK,
                                        null,
                                    ),
                                )
                                setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.08f)
                                setBottomPaddingFraction(0.06f)
                            }
                            this.player = core
                        }
                    },
                    update = { view -> view.resizeMode = ResizeModes[resize].first },
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        val core = player
        if (ui.danmakuOn && !inPip && core != null) {
            key(core) {
                AndroidView(
                    factory = { ctx -> DanmakuView(ctx, core) },
                    update = { view ->
                        view.apply(danmakuStyle, themeArgb)
                        view.setCues(danmakuCues)
                    },
                    onRelease = { it.close() },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (!inPip && core != null) {
            GestureLayer(
                enabled = !locked,
                session = session,
                player = core,
                onToggle = { controls = !controls; touch() },
                onFeedback = { gesture = it },
            )
        }
        /**
         * 用户口径（2026-11-24）：**锁定状态下点屏幕** → 解锁按钮短暂出现 3 秒。
         *
         * GestureLayer 在锁定时是禁用的（enabled = !locked），所以锁屏时点屏幕
         * 什么都不会发生，用户又找不到解锁入口。这里补一个透明点击层：
         * 锁定时接住单击 → 让锁头重现，再 3 秒自动淡出。
         * 只消费单击，其他手势（双击、拖动）都透传给下面的层。
         */
        if (locked) {
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { lockShown = !lockShown })
                    },
            )
        }

        if (ui.loading && ui.error == null) {
            LoadingIndicator(Modifier.align(Alignment.Center).size(72.dp), color = Color.White)
        }
        ui.error?.let { message ->
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(message, color = Color.White, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                FilledTonalButton(onClick = onBack) { Text("返回") }
            }
        }
        gesture?.let { feedback ->
            // Notices sit high, clear of faces and subtitles; seek and level readouts stay central.
            // 长按调速（Speed）也算 notice：放顶部，且 5 秒后自动消失，不长期挡画面。
            val isNotice = feedback is GestureFeedback.Text || feedback is GestureFeedback.Speed
            val place = if (isNotice) {
                // 旋转画布已扣除安全区，这里不再重复补偿（否则双重偏移）
                Modifier.align(Alignment.TopCenter).padding(top = 28.dp)
            } else Modifier.align(Alignment.Center)
            GestureBadge(feedback, place)
        }
        // 用户口径（2026-11-22）：倍速徽标持续 5 秒后自动隐藏。
        // 用 key(gesture) 让每次倍速变化重新计时；松手本身已清空 gesture，这里兜住「一直按住不放」的情况。
        if (gesture is GestureFeedback.Speed) {
            LaunchedEffect(gesture) {
                delay(5000)
                if (gesture is GestureFeedback.Speed) gesture = null
            }
        }
        ui.notice?.let { message ->
            LaunchedEffect(message) {
                delay(3_500)
                session.clearNotice(message)
            }
            Text(
                message,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.TopCenter)
                    .padding(top = 64.dp).background(Color.Black.copy(alpha = 0.55f), Corner.pill)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        /**
         * 用户口径（2026-11-26）右上角内核按钮的显示文字。
         *
         * 自动模式下显示**实际在跑的那个**（Exo / mpv），
         * 否则只看设置值分不清现在用的是谁。
         */
        val engineToggleLabel: String = remember(player, enginePref) {
            val running = when (player) {
                is MpvPlayer -> "MPV"
                null -> null
                else -> "Exo"
            }
            when (enginePref) {
                xyz.etkn.mobile.data.settings.PlayerEngine.Exo -> "Exo"
                xyz.etkn.mobile.data.settings.PlayerEngine.Mpv -> "MPV"
                /**
                 * 用户口径（2026-11-26）**只显示 Exo / MPV 两个值**。
                 *
                 * 自动模式下不显示「自动」二字，而是显示**实际在跑的那个**：
                 * 还没起播时引擎未定，先默认显示 Exo（Auto 的起播默认就是 Exo）。
                 * 这样按钮上永远是当前真实内核，不会出现第三种状态让人困惑。
                 */
                xyz.etkn.mobile.data.settings.PlayerEngine.Auto -> running ?: "Exo"
            }
        }

        if (!inPip) {
            AnimatedVisibility(controls && !locked, enter = fadeIn(), exit = fadeOut()) {
                Controls(
                    ui = ui, playing = playing, position = position, duration = duration, buffered = buffered,
                    onBack = onBack, onPip = onPip,
                    onPlayPause = { core?.let { if (it.isPlaying) it.pause() else it.play() }; touch() },
                    onSeek = { session.resetAutoSkipIfBefore(it); core?.seekTo(it); touch() },
                    onSeekBy = { delta -> core?.let { p -> p.seekTo((p.currentPosition + delta).coerceAtLeast(0)) }; touch() },
                    onSheet = { sheet = it; touch() },
                    onResize = { resize = (resize + 1) % ResizeModes.size; touch() },
                    resizeLabel = ResizeModes[resize].second,
                    onRotate = { manualOrientation = true; applyOrientation(!(portrait ?: true)); touch() },
                    rotateLabel = if (portrait == true) "竖屏" else "横屏",
                    onLock = {
            locked = true
            controls = false
            lockShown = true   // 先显示 3 秒再自动淡出
        },
                    onNext = { session.playNext() },
                    onPrevious = { session.playPrevious() },
                    onSpeed = { session.setSpeed(it) },
                    onInfoOpenChange = { infoOpen = it },
                    // 用户口径（2026-11-23）平板画布旋转时，控件不再扣一遍安全区
                    // （旋转后竖屏的 inset 会变成左右大留白，把按钮挤到中间）
                    applyWindowInsets = !rotated,
                    /**
                     * 用户口径（2026-11-26）**右上角切换播放内核按钮**。
                     *
                     * 标签显示当前实际在用的内核：
                     *  · 设置是「自动」→ 显示当前真正跑着的那个（Exo / mpv）
                     *  · 设置明确选了 → 显示那个
                     * 点击 = 在 Exo 和 mpv 之间切换，写回设置，
                     * 已有的 LaunchedEffect(enginePref) 会立刻 reloadEngineFromSettings()
                     * → 从当前位置续播，不用退出播放器。
                     */
                    engineLabel = engineToggleLabel,
                    onToggleEngine = {
                        /**
                         * 用户口径（2026-11-26）**加点击日志**。
                         *
                         * 用户反馈「黑屏时点切换内核没有任何反应」，
                         * 但日志里完全没有「手动切换内核」记录 ——
                         * 说明要么点击没触发，要么触发了但提前 return。
                         * 这里先打一条，确认点击是否真的到达。
                         */
                        android.util.Log.i("ETKN-Play", "点击内核按钮（当前=${engineToggleLabel}）")
                        runCatching { session.toggleEngine() }
                            .onFailure { android.util.Log.w("ETKN-Play", "toggleEngine 失败: ${it.message}") }
                        touch()
                    },
                    onToggleDanmaku = {
                        // 弹幕开关：直接翻转 enabled（样式其它项保持不变）
                        session.setDanmakuStyle(session.danmakuStyle.value.copy(enabled = !ui.danmakuOn))
                        touch()
                    },
                )
            }
            /**
             * 用户口径（2026-11-24）：锁定后解锁按钮 3 秒后自动淡出，
             * 点屏幕再短暂出现（见下方共同层的 tap 处理）。
             */
            LaunchedEffect(locked, lockShown) {
                if (locked && lockShown) {
                    kotlinx.coroutines.delay(3000)
                    lockShown = false
                }
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = locked && lockShown,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(16.dp),
            ) {
                FilledIconButton(
                    onClick = { locked = false; controls = true },
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = 0.45f), contentColor = Color.White),
                ) { Icon(Icons.Rounded.Lock, "解锁") }
            }
            // 用户口径（2026-11-23）：自动跳过模式下按钮也要显示，按钮内部用色块填充当 5 秒倒计时。
            // 之前这里是 takeUnless { Auto && Intro } —— Auto 模式直接把片头按钮藏了，所以看不到倒计时。
            segment?.takeIf { skipVisible }?.let { s ->
                val label = when {
                    s.kind == SkipSegment.Kind.Intro -> "跳过片头"
                    ui.next != null -> "下一集"
                    else -> "跳过片尾"
                }
                val countingDown = ui.skipMode == SkipMode.Auto && skipCountdown > 0f
                // 用户口径（2026-11-23）：倒计时色块只在这颗胶囊按钮**内部**从左往右填，
                // 绝不能铺到整屏 —— 之前把填充层写成 fillMaxSize 的独立 Box，撑满的是整个播放界面，
                // 所以出现了「整个屏幕在倒计时」。现在直接在胶囊自己的 drawBehind 里画，
                // 尺寸天然就是按钮宽×按钮高。
                Row(
                    Modifier.align(Alignment.BottomEnd)
                        .padding(end = 24.dp, bottom = if (controls) 120.dp else 32.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                Box(
                    Modifier
                        .clip(Corner.pill)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .drawBehind {
                            if (countingDown) {
                                val w = size.width * skipCountdown.coerceIn(0f, 1f)
                                if (w > 0f) {
                                    drawRoundRect(
                                        color = Color.White.copy(alpha = 0.28f),
                                        size = androidx.compose.ui.geometry.Size(w, size.height),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2f),
                                    )
                                }
                            }
                        }
                        .clickable { session.skip(s) }
                        // 用户口径（2026-11-23）**跳过片头 / 取消 这一组不放大**，保持原尺寸
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (s.kind == SkipSegment.Kind.Intro) Icons.Rounded.FastForward else Icons.Rounded.SkipNext,
                            null,
                            Modifier.size(18.dp),
                            tint = Color.White,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(label, color = Color.White, fontSize = 14.sp)
                        if (countingDown) {
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "${(5 * (1f - skipCountdown)).toInt() + 1}s",
                                color = Color.White.copy(alpha = 0.75f),
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
                    // 用户口径（2026-11-23）：照参考图，胶囊右边还有一个「取消」。
                    // 点它 = 这次不跳（关掉倒计时，也不要再自动跳这一段）。
                    if (countingDown) {
                        Text(
                            "取消",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 14.sp,
                            modifier = Modifier.clickable {
                                // 用户口径（2026-11-23）：点取消 → 这组按钮立刻消失，别再固定在那儿
                                skipDismissed = true
                                skipCountdown = 0f
                                session.cancelAutoSkip(s)
                            },
                        )
                    }
                }
            }
        }
            /**
             * 用户口径（2026-11-26）**面板直接渲染在已旋转的画布内部**。
             *
             * 画布坐标系 == 用户看到的画面坐标系，所以面板的
             * 「右侧 = Alignment.CenterEnd」天然正确，不需要任何换算；
             * 方向也跟着画布一起转，不会再出现「画面横着、弹窗竖着」。
             *
             * 这也顺带解决了位置问题：之前它在独立 Window 里，
             * 绕开画布成了另一套坐标系，怎么调对齐都会偏。
             */
            if (sheet != Sheet.None) {
                PlayerSheet(
                    sheet, ui, session,
                    onSheet = { sheet = it },
                    onDismiss = { sheet = Sheet.None },
                    onRotate = { manualOrientation = true; applyOrientation(!(portrait ?: true)); touch() },
                    portrait = portrait ?: false,
                )
            }
        }
    }

}

/** 沿 ContextWrapper 链往上找到真正的 Activity（LocalContext 不一定是 Activity）。 */
private fun android.content.Context.findActivity(): android.app.Activity? {
    var c: android.content.Context? = this
    while (c is android.content.ContextWrapper) {
        if (c is android.app.Activity) return c
        c = c.baseContext
    }
    return c as? android.app.Activity
}

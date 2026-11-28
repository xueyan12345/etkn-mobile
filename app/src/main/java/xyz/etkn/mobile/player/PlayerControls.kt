package xyz.etkn.mobile.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.VideoSettings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.etkn.mobile.ui.components.Artwork
import xyz.etkn.mobile.ui.components.formatClock
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.images
import androidx.compose.ui.layout.ContentScale
import java.util.Locale
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import java.text.SimpleDateFormat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import android.content.Context
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Person
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween

/**
 * 播放器控制层。
 *
 * 用户口径（2026-11-23）按参考图重排：
 * · 左上：返回 + 剧名（服务端艺术字 logo，取不到就用文字标题）
 * · 顶部右侧：版本（多版本时）/ 画面比例 / 画中画 / 旋转屏幕 / 更多
 *   投屏已从这里移除（用户要求先去掉，后续另给位置）
 * · 中央：快退 10 秒 / 播放暂停 / 快进 10 秒（保持原样不动）
 * · 进度条上方：剧名 + 集号
 * · 进度条：加缓冲位置小条
 * · 进度条下方右侧图标：弹幕开关 / 弹幕设置 / 音轨 / 字幕 / 选集
 * · 左下：锁定
 */
@Composable
internal fun Controls(
    ui: VideoUi,
    playing: Boolean,
    duration: Long,
    position: Long,
    buffered: Long,
    onPlayPause: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onBack: () -> Unit,
    onPip: () -> Unit,
    onSheet: (Sheet) -> Unit,
    onResize: () -> Unit,
    resizeLabel: String,
    onRotate: () -> Unit,
    rotateLabel: String,
    onLock: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleDanmaku: () -> Unit,
    onSpeed: (Float) -> Unit,
    /** 用户口径（2026-11-23）：简介浮层打开状态上报，打开时不自动隐藏控制条。 */
    onInfoOpenChange: (Boolean) -> Unit = {},
    /**
     * 用户口径（2026-11-23）**OPPO 平板画布旋转时不能重复扣安全区**。
     *
     * 平板走「整层 90° 旋转」兜底时，画布已经是屏幕原始宽高（2520×1680），
     * 系统栏本来就是沉浸隐藏的。但 Controls 内部有 4 处
     * windowInsetsPadding(safeDrawing)，竖屏的 top/bottom inset 旋转后会变成
     * 左右方向的大留白 → 按钮被挤到中间、两侧空一大块。
     *
     * 所以旋转态传 false，让控件贴着画布边缘走；普通全屏仍为 true。
     */
    applyWindowInsets: Boolean = true,
    /**
     * 用户口径（2026-11-26）**播放界面右上角新增「切换播放内核」按钮**。
     *
     * 需求原文：「在播放器的播放界面，右上角现在不是3个按钮吗，
     * 你给我在上边再加一个按钮，就是可以切换播放内核的那个按钮」
     *
     * 做法：点一下就在 ExoPlayer / mpv 之间切换，当场生效（从当前位置续播），
     * 和设置页那个「播放内核」是同一个设置项，两边永远一致。
     * 按钮上的文字显示**当前实际在用的内核**，方便判断。
     */
    engineLabel: String = "",
    onToggleEngine: () -> Unit = {},
) {
    // 统一入口：需要扣安全区时才加这个 Modifier，避免四处各写一遍漏改
    val insetMod = if (applyWindowInsets) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier
    var scrubbing by remember { mutableStateOf(false) }
    var scrub by remember { mutableFloatStateOf(0f) }
    // 用户口径（2026-11-23）：点底部那一行「S1:E34:标题」弹剧集信息浮层。
    var showInfo by remember { mutableStateOf(false) }
    LaunchedEffect(showInfo) { onInfoOpenChange(showInfo) }
    // 用户口径（2026-11-23）：顶部中间显示系统时间 / 网速 / 电量，每秒刷新
    val status = rememberPlayerStatus()
    Box(Modifier.fillMaxSize()) {
        // 上下渐变，保证文字/按钮在亮画面上也看得清
        Box(Modifier.fillMaxWidth().height(200.dp).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))))
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.58f)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.84f)))),
        )

        // ===== 用户口径（2026-11-23，照参考图重排）=====
        // 正中间：只有时间。
        Text(
            status.time,
            color = Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.align(Alignment.TopCenter)
                .then(insetMod)
                .padding(top = 8.dp),
        )
        // 右上角：网速 + 电量百分比 + 电池图标（同一行，紧贴右边）。
        Row(
            Modifier.align(Alignment.TopEnd)
                .then(insetMod)
                .padding(end = 16.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 用户口径（2026-11-23）：网速要一直显示，不能时有时无。
            // 之前是 if (status.speed.isNotEmpty()) 才画 —— 取不到采样就整块消失。
            // 现在固定占位，取不到就显示 0 B/s。
            Text(
                status.speed.ifEmpty { "0 B/s" },
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
            )
            Text("${status.battery}%", color = Color.White, fontSize = 12.sp)
            // 电池图标：随电量变长度（像素风）
            BatteryGlyph(status.battery)
        }

        // ===== 顶栏：返回 + 剧名 + 右侧功能图标 =====
        // 用户口径（2026-11-23）：功能图标整体下移，明显低于「时间」那一行（照参考图）。
        Row(
            Modifier.fillMaxWidth().then(insetMod)
                .padding(horizontal = 10.dp).padding(top = 38.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(60.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = Color.White, modifier = Modifier.size(30.dp))
            }
            // 用户口径（2026-11-23）：剧名要用服务端挂好的书法艺术字 logo（ImageTags.Logo），
            // 不是普通文字。取不到 logo 才退回文字标题。
            Box(Modifier.weight(1f).padding(horizontal = 6.dp)) {
                // images() 是 @Composable，必须在 composable 上下文直接调，不能塞进 remember lambda
                val urls = images()
                val logoUrl = remember(ui.item?.id, urls) {
                    ui.item?.let { urls.logo(it, 480) }
                }
                if (logoUrl != null) {
                    Artwork(
                        logoUrl,
                        // 用户口径：剧名艺术字保持 1.5 倍不变
                        Modifier.height(48.dp).width(240.dp),
                        contentDescription = ui.title,
                        contentScale = ContentScale.Fit,
                        background = Color.Transparent,
                        alignment = Alignment.CenterStart,
                    )
                } else {
                    Text(
                        ui.title,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            /**
             * 用户口径（2026-11-26）**内核切换按钮放在那排按钮的最左边，样式完全一致**。
             *
             * 需求原文：
             *  · 「你把它加在那3个按钮的左边，变成4个按钮」
             *  · 「那个设置的小图标，那个小齿轮不要，直接就显示EXO/MPV」
             *  · 「我看你好像在下边还加了一个小黑色阴影的框，不要那个框，
             *     就跟原来那3个按钮保持一致就行，相当于多加了一个按钮」
             *
             * 所以：不带底板、不带图标、不带圆角填充，
             * 只有一个同样 60dp 触控区的纯文字按钮（Exo / MPV），
             * 和右边那些图标按钮同一行、同一尺寸、同一视觉重量。
             */
            if (engineLabel.isNotEmpty()) {
                IconButton(onClick = onToggleEngine, modifier = Modifier.size(60.dp)) {
                    Text(
                        engineLabel,
                        color = Color.White,
                        // 和 30dp 图标视觉配平：三个字母（Exo/MPV）用 17sp
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (ui.sources.size > 1) PlayerAction(Icons.Rounded.VideoSettings, "版本") { onSheet(Sheet.Versions) }
            PlayerAction(Icons.Rounded.AspectRatio, resizeLabel, onResize)
            PlayerAction(Icons.Rounded.PictureInPictureAlt, "画中画", onPip)
            PlayerAction(Icons.Rounded.ScreenRotation, rotateLabel, onRotate)
            // 用户口径（2026-11-23）：右上角三点按钮 + 它打开的菜单**整个删掉**。
            // 只删显示 —— 菜单背后的功能全部保留（旋转屏幕顶栏另有按钮、
            // 跳过片头片尾/定时关闭在设置页可改、切换内核由设置页内核选项控制），
            // 不做任何功能删减。
        }

        // ===== 用户口径（2026-11-23）左侧垂直居中：只放锁（从底栏移过来）=====
        Box(Modifier.align(Alignment.CenterStart).padding(start = 28.dp)) {
            PlayerAction(Icons.Rounded.LockOpen, "锁定", onLock)
        }

        // ===== 用户口径（2026-11-23）右侧垂直居中：倍速 + / 1.0x / − =====
        SpeedPad(
            speed = ui.speed,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 28.dp),
            onChange = onSpeed,
            onPick = { onSheet(Sheet.Speed) },
        )

        /**
         * 用户口径（2026-11-27）**中央只留播放/暂停，去掉左右 ±10 秒按钮**。
         *
         * 原来的 70dp 快退/快进图标占掉了中央一大块，用户要求去掉。
         * 快进快退能力没丢，还有两个入口：
         *  · 双击九宫格（左上后退 10 秒 / 右上快进 10 秒，见 PlayerGestures）
         *  · 底栏进度条拖动
         * `setSeekBackIncrementMs/setSeekForwardIncrementMs`（PlayerFactory）也没动，
         * 那是耳机线控和系统媒体控制用的，和界面按钮无关。
         */
        IconButton(onClick = onPlayPause, modifier = Modifier.align(Alignment.Center).size(95.dp)) {
            Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                if (playing) "暂停" else "播放",
                tint = Color.White, modifier = Modifier.size(75.dp),
            )
        }

        // ===== 底栏：剧名+集号 / 进度条 / 功能图标 =====
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().then(insetMod)
                .padding(horizontal = 20.dp, vertical = 5.dp),
        ) {
            val previews = ui.previews
            if (scrubbing && previews != null && duration > 0) SeekThumbnail(previews, scrub, duration)

            // 用户口径（2026-11-23）按参考图：这一行只放「S1:E34:本集标题」，
            // 不带剧名、不带分隔点。参考图格式就是   S1:E34:关铁山识破王老五身份
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(bottom = 2.dp)
                    // 用户口径（2026-11-23）：这一行可以点，弹出剧集简介浮层
                    .clickable { showInfo = true },
            ) {
                val code = ui.subtitle   // 服务端给的就是 "S1:E34:标题" 这种形态
                val line = when {
                    code.isEmpty() -> ui.title
                    code.startsWith("S") -> code
                    else -> ui.title
                }
                Text(
                    line,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 用户口径（2026-11-23）按参考图重做进度条：
                // 细轨道 + 纯白已播放 + 半透明白未播放 + 缓冲条（浅白，夹在轨道层）
                // + 实心白色圆形播放头；时间文字在左侧外侧同一行。
                // 用户口径（2026-11-23）照参考图：时间在进度条左端外侧，
                // 格式是「当前 / 总时长」同行（01:50 / 33:04）。
                Text(
                    buildString {
                        append(formatClock(if (scrubbing) (scrub * duration).toLong() else position))
                        if (duration > 0) {
                            append(" / ")
                            append(formatClock(duration))
                        }
                    },
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 15.sp,
                )
                Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    val progress = if (scrubbing) scrub
                    else if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    // 用户口径（2026-11-23）：缓冲段 = 按设置真正落盘的进度（ui.cachedPosition），
    // 不再用 player.bufferedPosition（那只是内核几秒的前向缓冲，和缓存设置无关，
    // 会让用户以为「设了 10GB 缓存怎么只有一小条」）。
    /**
     * 用户口径（2026-11-26）**缓冲条只认「我们自己的缓存进度」**。
     *
     * 旧写法 `maxOf(ui.cachedPosition, buffered)` 把播放器内核那几秒前向缓冲混进来，
     * 于是缓冲条位置一会儿是内核缓冲、一会儿是磁盘缓存，两者混着取最大值 → 忽长忽短。
     *
     * 现在：
     *  · cachedPosition > 0（有本地缓存）→ 只用它，内核缓冲不参与
     *  · cachedPosition == 0（没开缓存）→ 才退回内核缓冲
     */
    val shownBuffered = if (ui.cachedPosition > 0) ui.cachedPosition else buffered
    val bufferedFrac = if (duration > 0) (shownBuffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
                    // 整条高度 26dp，里面自绘：轨道 3dp、缓冲 3dp、播放头直径 14dp
                    Canvas(
                        Modifier.fillMaxWidth().height(39.dp)
                            .pointerInput(duration) {
                                detectTapGestures { offset ->
                                    if (duration > 0) {
                                        val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                        onSeek((frac * duration).toLong())
                                    }
                                }
                            }
                            .pointerInput(duration) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        if (duration > 0) {
                                            scrubbing = true
                                            scrub = (offset.x / size.width).coerceIn(0f, 1f)
                                        }
                                    },
                                    onDragEnd = {
                                        if (duration > 0) onSeek((scrub * duration).toLong())
                                        scrubbing = false
                                    },
                                    onDragCancel = { scrubbing = false },
                                ) { change, _ ->
                                    if (duration > 0) {
                                        scrub = (change.position.x / size.width).coerceIn(0f, 1f)
                                        change.consume()
                                    }
                                }
                            },
                    ) {
                        val cy = size.height / 2f
                        val trackH = 3.75.dp.toPx()
                        val thumbR = 8.75.dp.toPx()
                        val r = androidx.compose.ui.geometry.CornerRadius(trackH / 2f, trackH / 2f)
                        // 未播放轨道（半透明白）
                        drawRoundRect(
                            color = Color.White.copy(alpha = 0.30f),
                            topLeft = androidx.compose.ui.geometry.Offset(0f, cy - trackH / 2f),
                            size = androidx.compose.ui.geometry.Size(size.width, trackH),
                            cornerRadius = r,
                        )
                        // 缓冲进度（比轨道稍亮一点，压在轨道上）
                        if (bufferedFrac > 0.001f) {
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.55f),
                                topLeft = androidx.compose.ui.geometry.Offset(0f, cy - trackH / 2f),
                                size = androidx.compose.ui.geometry.Size(size.width * bufferedFrac, trackH),
                                cornerRadius = r,
                            )
                        }
                        // 已播放（纯白）
                        drawRoundRect(
                            color = Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, cy - trackH / 2f),
                            size = androidx.compose.ui.geometry.Size(size.width * progress, trackH),
                            cornerRadius = r,
                        )
                        // 播放头：实心白圆
                        drawCircle(
                            color = Color.White,
                            radius = thumbR,
                            center = androidx.compose.ui.geometry.Offset(
                                (size.width * progress).coerceIn(thumbR, size.width - thumbR),
                                cy,
                            ),
                        )
                    }
                }
                // 用户口径（2026-11-23）：进度条最右侧这个总时长删掉 ——
                // 左边已经是「当前 / 总时长」，右边再写一遍是重复的。
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 用户口径（2026-11-23）按参考图：左边 上一集 / 暂停 / 下一集；
                // 右边 弹幕开关 / 弹幕设置 / 音轨 / 字幕 / 选集（共 5 个）。
                // 弹幕那两个是这次按用户要求加回来的（之前换成了音轨/字幕/选集）。
                PlayerAction(Icons.Rounded.SkipPrevious, "上一集", onPrevious)
                PlayerAction(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "暂停" else "播放", onPlayPause)
                // 用户口径（2026-11-23）：只有「下一集已预加载好」时，才在按钮右上角
                // 显示一个白色小圆 + 黑色闪电。预加载没开 / 没跑完 / 没有下一集 → 完全不显示。
                NextEpisodeAction(onNext, warmed = ui.nextPreloaded)
                Spacer(Modifier.weight(1f))
                /**
                 * 用户口径（2026-11-27，#22）**弹幕开关和设置合并成一个按钮**。
                 *
                 * 需求原文：
                 *  「这个画圆圈的按钮，点一下是弹幕的设置，左边的那个按钮是点一下打开弹幕和关闭弹幕，
                 *    这两个我想给它融合在一块，把现在画圈的这个弹幕设置按钮给它去掉，
                 *    然后把左边的那个弹幕开关按钮移动到这块，然后点一下弹幕开关的按钮，就是正常开关，
                 *    我想在这个基础上加一个长按弹幕开关的按钮，是这个弹幕的设置，
                 *    相当于把这两个按钮合并成一个，功能还是那些，只不过操作方式变了」
                 *
                 * 所以：点一下 = 开关；长按 = 打开设置面板。
                 */
                DanmakuAction(
                    kind = 0,
                    label = if (ui.danmakuOn) "关闭弹幕" else "开启弹幕",
                    active = ui.danmakuOn,
                    onClick = onToggleDanmaku,
                    onLongClick = { onSheet(Sheet.Danmaku) },
                )
                PlayerAction(Icons.Rounded.Audiotrack, "音轨") { onSheet(Sheet.Audio) }
                PlayerAction(Icons.Rounded.ClosedCaption, "字幕") { onSheet(Sheet.Subtitle) }
                if (ui.item?.isEpisode == true) PlayerAction(Icons.AutoMirrored.Rounded.PlaylistPlay, "选集") { onSheet(Sheet.Episodes) }
            }
        }

        // 用户口径（2026-11-23）：点底部的「S1:E34:标题」弹出剧集信息浮层（照参考图）。
        if (showInfo) {
            EpisodeInfoSheet(ui, onDismiss = { showInfo = false })
        }
    }
}

/**
 * 用户口径（2026-11-23）：剧集信息浮层 —— **完全照参考图重做**。
 *
 * 参考图结构（逐项对照，之前几版都做错了）：
 *   ① 顶部：左侧 16:9 剧照 + 右侧文字区
 *   ② 剧名（最大字号、粗体）
 *   ③ S1:E34 - 集标题（第二层，两行分开，不是挤在一行）
 *   ④ ★ + 评分 + 日期（红色五角星，小字号）
 *   ⑤ 剧情/喜剧（纯文字斜杠分隔，不用胶囊标签）
 *   ⑥ 简介正文（完整显示不截断）
 *   ⑦ 「演职人员」分区标题（中大型、左对齐、无图标）
 *   ⑧ 竖版圆角矩形人像卡（约 3:4），下方 姓名 + 角色名（饰 XXX / 导演）
 *
 * 面板：深色半透明、大圆角、点外部关闭。
 */
@Composable
private fun EpisodeInfoSheet(ui: VideoUi, onDismiss: () -> Unit) {
    val item = ui.item ?: return
    val images = xyz.etkn.mobile.ui.components.images()
    Box(
        Modifier.fillMaxSize()
            .background(Color.Black.copy(alpha = 0.62f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth(0.94f)
                .heightIn(max = 560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF141414).copy(alpha = 0.96f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
        ) {
            // ① 顶部：剧照 + 文字
            Row(verticalAlignment = Alignment.Top) {
                // 剧照（用本集缩略图；没有就退到系列图）
                val shotUrl = remember(item.id) {
                    runCatching { images.primary(item, 640) }.getOrNull()
                }
                Box(
                    Modifier
                        .width(200.dp)
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF222222)),
                ) {
                    if (shotUrl != null) {
                        xyz.etkn.mobile.ui.components.Artwork(
                            shotUrl,
                            Modifier.fillMaxSize(),
                            item.name,
                            background = Color(0xFF222222),
                        )
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    // ② 剧名（最大字号）
                    Text(
                        item.seriesName ?: item.name,
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // ③ 集号 - 集标题（第二层，独立一行）
                    val code = buildString {
                        val season = item.parentIndexNumber
                        val ep = item.indexNumber
                        if (season != null && ep != null) append("S$season:E$ep")
                    }
                    val epTitle = item.name.takeIf { it.isNotBlank() && it != item.seriesName }
                    if (code.isNotEmpty() || epTitle != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            listOfNotNull(code.takeIf { it.isNotEmpty() }, epTitle).joinToString(" - "),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 15.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    // ④ ★ + 评分 + 日期
                    val rating = item.communityRating ?: item.tmdbRating ?: item.doubanRating
                    val date = item.premiereDate?.take(10)?.replace("-", "/")
                    if ((rating != null && rating > 0) || date != null) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (rating != null && rating > 0) {
                                Text("★", color = Color(0xFFE53935), fontSize = 13.sp)
                                Spacer(Modifier.width(3.dp))
                                Text(
                                    String.format(Locale.ROOT, "%.1f", rating),
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 13.sp,
                                )
                                if (date != null) Spacer(Modifier.width(10.dp))
                            }
                            if (date != null) {
                                Text(
                                    date,
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    // ⑤ 类型（纯文字斜杠分隔）
                    if (item.genres.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            item.genres.joinToString("/"),
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // ⑥ 简介（完整显示）
            item.overview?.takeIf { it.isNotBlank() }?.let { text ->
                Spacer(Modifier.height(16.dp))
                Text(
                    text,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 14.sp,
                    lineHeight = 21.sp,
                )
            }

            // ⑦⑧ 演职人员
            if (item.people.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "演职人员",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(item.people.take(20), key = { it.id + it.role + it.type }) { person ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(72.dp),
                        ) {
                            // 竖版圆角矩形人像卡（3:4）
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(3f / 4f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF2A2A2A)),
                                contentAlignment = Alignment.Center,
                            ) {
                                val personUrl = remember(person.id, person.imageTag) {
                                    runCatching { images.person(person, 200) }.getOrNull()
                                }
                                if (personUrl != null) {
                                    xyz.etkn.mobile.ui.components.Artwork(
                                        personUrl,
                                        Modifier.fillMaxSize(),
                                        person.name,
                                        background = Color(0xFF2A2A2A),
                                    )
                                } else {
                                    Icon(
                                        Icons.Rounded.Person,
                                        contentDescription = null,
                                        tint = Color.White.copy(alpha = 0.35f),
                                        modifier = Modifier.size(28.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                person.name,
                                color = Color.White,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            // 角色名：演员「饰 XXX」，导演「导演」
                            val subtitle = when {
                                person.type.equals("Director", true) -> "导演"
                                // 服务端 Role 已自带「饰 」前缀，直接用原文（否则「饰 饰 XXX」）
                                person.role.isNotBlank() && !person.role.equals(person.name, true) ->
                                    person.role.trim().let { if (it.startsWith("饰")) it else "饰 $it" }
                                else -> ""
                            }
                            if (subtitle.isNotEmpty()) {
                                Text(
                                    subtitle,
                                    color = Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 播放器顶部状态：系统时间 / 实时网速 / 电量。
 * 网速用 TrafficStats 两次采样差分（1 秒间隔），取不到就不显示（不编造 0.0）。
 */
private data class PlayerStatus(val time: String, val battery: Int, val speed: String)

@Composable
private fun rememberPlayerStatus(): PlayerStatus {
    val context = LocalContext.current
    var state by remember { mutableStateOf(PlayerStatus("", 0, "")) }
    LaunchedEffect(Unit) {
        val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
        var lastRx = 0L
        while (true) {
            /**
             * 用户口径（2026-11-27）**网速只算本应用，不混入系统其他流量**。
             *
             * 原来用 getTotalRxBytes() —— 那是「全机器所有进程的接收总量」，
             * 家里别的设备/后台更新都会算进来，和播放器实际下载速度对不上。
             *
             * 现在用 getUidRxBytes(自己uid)，并且只算 wlan + 移动数据两类，
             * 这样显示的就是「本 App 实际吃到的网速」，和系统状态栏看到的同源。
             *
             * 前提：应用已经产生过网络流量（播放器必然满足），否则返回 UNSUPPORTED。
             */
            val uid = android.os.Process.myUid()
            val uiBytes = android.net.TrafficStats.getUidRxBytes(uid)
            val rx = if (uiBytes == android.net.TrafficStats.UNSUPPORTED.toLong()) 0L else uiBytes
            // 用户口径（2026-11-23）：网速恒定有值。
            // 第一秒（lastRx 还没建立）或这一秒没有流量时返回 0 B/s，而不是空串，
            // 否则界面上会是「有时显示有时不显示」。
            val speed = when {
                lastRx <= 0L || rx <= lastRx -> "0 B/s"
                else -> {
                    val bytes = rx - lastRx
                    when {
                        bytes >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB/s", bytes / 1048576f)
                        bytes >= 1024 -> String.format(Locale.ROOT, "%.0f KB/s", bytes / 1024f)
                        else -> "$bytes B/s"
                    }
                }
            }
            lastRx = rx
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            state = PlayerStatus(
                time = fmt.format(java.util.Date()),
                battery = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0,
                speed = speed,
            )
            kotlinx.coroutines.delay(1_000)
        }
    }
    return state
}

/** 像素风电池图标：外壳 + 右侧凸起 + 按电量填充的内部格子。 */
@Composable
private fun BatteryGlyph(percent: Int) {
    val level = percent.coerceIn(0, 100)
    val filled = (level / 20f).let { if (level > 0) it.coerceAtLeast(0.6f) else 0f }
    Canvas(Modifier.size(width = 24.dp, height = 12.dp)) {
        val bodyW = size.width - 3.dp.toPx()
        val stroke = 1.5.dp.toPx()
        val r = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx(), 2.dp.toPx())
        // 外壳
        drawRoundRect(
            color = Color.White,
            size = androidx.compose.ui.geometry.Size(bodyW, size.height),
            cornerRadius = r,
            style = Stroke(width = stroke),
        )
        // 右侧凸起（正极）
        drawRoundRect(
            color = Color.White,
            topLeft = androidx.compose.ui.geometry.Offset(bodyW, size.height * 0.30f),
            size = androidx.compose.ui.geometry.Size(2.5.dp.toPx(), size.height * 0.40f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(0.8.dp.toPx(), 0.8.dp.toPx()),
        )
        // 内部填充
        if (filled > 0f) {
            val inset = stroke + 1.dp.toPx()
            val innerW = (bodyW - inset * 2) * (filled / 5f)
            drawRoundRect(
                color = Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(innerW, size.height - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.dp.toPx(), 1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun PlayerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    /**
     * 用户口径（2026-11-23）：播放界面所有元素放大到 1.5 倍。
     * 默认 Icon 是 24dp、IconButton 是 48dp 触控区，这里按 1.5 倍给足尺寸。
     */
    IconButton(onClick = onClick, modifier = Modifier.size(60.dp)) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
    }
}

/**
 * 用户口径（2026-11-23）：弹幕那排两个按钮改成参考图的样式 ——
 * 圆角矩形线框（无底板），内部横线/滑块意象，纯线条描边。
 * Material Icons 没有同款，这里用 Canvas 自绘。
 *
 * [kind] = 0 弹幕开关（方框 + 内部字块）；1 弹幕设置（方框 + 内部滑块线）。
 */
@Composable
private fun DanmakuAction(
    kind: Int,
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    /**
     * 用户口径（2026-11-27，#22）弹幕设置并入开关：长按打开设置面板。
     * null = 没有长按行为（目前只有弹幕开关会传）。
     */
    onLongClick: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(60.dp)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        // 用户口径（2026-11-23）：播放界面元素放大 1.5 倍（24dp → 36dp，线宽同步）
        Canvas(Modifier.size(30.dp)) {
            val tint = if (active) Color.White else Color.White.copy(alpha = 0.55f)
            val w = size.width
            val h = size.height
            val stroke = 2.1.dp.toPx()
            val pad = w * 0.10f
            val r = androidx.compose.ui.geometry.CornerRadius(5.dp.toPx(), 5.dp.toPx())
            // 外框：圆角矩形线框
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(pad, h * 0.18f),
                size = androidx.compose.ui.geometry.Size(w - pad * 2, h * 0.64f),
                cornerRadius = r,
                style = Stroke(width = stroke),
            )
            if (kind == 0) {
                // 弹幕：框内两条横线（模拟文字行）
                val lx = pad + w * 0.13f
                val rx = w - pad - w * 0.13f
                drawLine(tint, androidx.compose.ui.geometry.Offset(lx, h * 0.40f), androidx.compose.ui.geometry.Offset(rx, h * 0.40f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(lx, h * 0.60f), androidx.compose.ui.geometry.Offset(lx + (rx - lx) * 0.6f, h * 0.60f), strokeWidth = stroke, cap = StrokeCap.Round)
            } else {
                // 设置：框内一条滑轨 + 一个滑块
                val lx = pad + w * 0.13f
                val rx = w - pad - w * 0.13f
                drawLine(tint, androidx.compose.ui.geometry.Offset(lx, h * 0.50f), androidx.compose.ui.geometry.Offset(rx, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
                drawCircle(tint, radius = stroke * 1.5f, center = androidx.compose.ui.geometry.Offset(lx + (rx - lx) * 0.35f, h * 0.50f))
            }
        }
    }
}

/**
 * 右侧倍速控件（用户口径 2026-11-23，按参考图）：
 * 半透明深色圆角长条，垂直排列 + / 当前倍速 / −。
 * 点数字本身弹出倍速菜单可直接选。
 */
@Composable
private fun SpeedPad(speed: Float, modifier: Modifier, onChange: (Float) -> Unit, onPick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(25.dp))
            .background(Color.Black.copy(alpha = 0.36f))
            .padding(horizontal = 3.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable {
                onChange((((speed * 10).toInt() + 1) / 10f).coerceIn(0.3f, 4f))
            },
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Medium) }

        Box(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onPick).padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(String.format(Locale.ROOT, "%.1fx", speed), color = Color.White, fontSize = 16.25.sp, fontWeight = FontWeight.SemiBold)
        }

        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable {
                onChange((((speed * 10).toInt() - 1) / 10f).coerceIn(0.3f, 4f))
            },
            contentAlignment = Alignment.Center,
        ) { Text("−", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.Medium) }
    }
}

/** The frame under the finger while scrubbing, riding above the seek bar. */
@Composable
private fun SeekThumbnail(previews: SeekPreviews, fraction: Float, duration: Long) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        val width = 176.dp
        val height = width / previews.aspectRatio
        val x = ((maxWidth - width) * fraction.coerceIn(0f, 1f))
        Artwork(
            previews.urlAt((fraction * duration).toLong()),
            Modifier.offset(x = x).width(width).height(height).clip(Corner.card)
                .border(1.dp, Color.White.copy(alpha = 0.5f), Corner.card),
            background = Color.Black,
        )
    }
}

/**
 * 用户口径（2026-11-23）：下一集按钮 + 预加载指示。
 * 那个白色小圆里的黑闪电 = 「下一集已经缓存到本地，点了秒开」。
 * 只由 warmed 决定显不显示；warmed 来自 PlaybackCache.isWarmed(下一集 id)。
 */
@Composable
/**
 * 用户口径（2026-11-27，#25）**预加载气泡改小、去掉白圈、往按钮贴近**。
 *
 * 需求原文：
 *  「稍微改一改那个预加载的小气泡，有点太大了，样式也不好看，把那个圈去掉，
 *    就留那个闪电就行，然后也有点太往上了，给它离下一集按钮近一点」
 *
 * 改动：
 *  · 去掉白色实心圆底 —— 只留一个闪电图标，靠阴影保证在浅色画面上也看得清
 *  · 尺寸 17.5dp → 12dp
 *  · 位置从 TopEnd 改成 BottomEnd 的偏移量收小，让它贴着按钮右上角
 */
private fun NextEpisodeAction(onClick: () -> Unit, warmed: Boolean) {
    Box(contentAlignment = Alignment.TopEnd) {
        PlayerAction(Icons.Rounded.SkipNext, "下一集", onClick)
        if (warmed) {
            /**
             * 用户口径（2026-11-28，#151）**视频播放器的气泡也改成呼吸样式**。
             *
             * 用户原话：
             * ```
             * 「我想把这个效果也放在视频播放器里面，
             *   但是你要注意，视频播放器的组件是白色的」
             * ```
             *
             * ## 配色为什么不能照抄音乐页
             *
             * 音乐页那张卡的底色是 `surfaceContainerHigh`（浅灰），
             * 所以气泡用 `primary`（主题色）+ `onPrimary` 的文字。
             *
             * 而视频播放器这一层是**压在视频画面上**、所有控件都是纯白
             * （见 `PlayerAction`：`tint = Color.White`）。
             * 如果气泡也用白色，会和「下一集」图标糊成一片、看不出边界；
             * 所以这里反过来：
             * ```
             * 底：Color.White        白圆 —— 和图标同色系但更实
             * 图标：Color.Black      黑闪电 —— 在白圆上对比最强
             * ```
             * 白圆 + 黑闪在任何画面上都清楚，也和白色控制组件统一。
             *
             * 呼吸：透明度 0.5 ↔ 1.0，1.6 秒一轮（和音乐页参数一致）。
             */
            val breathe = rememberInfiniteTransition(label = "next-ep-ready")
            val alpha by breathe.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "next-ep-ready-alpha",
            )
            /**
             * 位置与比例**照抄音乐页的下一首气泡**（用户口径 #153）。
             *
             * 用户原话：
             * ```
             * 「播放器的下一集预加载的小气泡，你参考一下现在音频的那个播放小气泡。
             *   位置和整体的比例，你给我改一改，那个有点不对劲」
             * ```
             *
             * 音乐页（`NowPlaying.kt`）的参数：
             * ```
             * IconButton 48dp / 图标 30dp / 气泡 11dp / 闪电 8dp / offset(-4, 4)
             * ```
             * 视频页的 `PlayerAction` 是 **60dp 按钮 / 30dp 图标**
             * （用户 11-23 要求播放界面整体放大 1.5 倍）。
             *
             * 按钮大 1.25 倍（60÷48），所以气泡和偏移也乘 1.25，
             * 这样相对位置才和音乐页一致 —— 之前用固定 13dp/-4dp 是「按绝对像素抄」，
             * 放在 60dp 按钮上气泡离图标角远了、显得小，就是用户说的「有点不对劲」：
             * ```
             * 气泡  11 × 1.25 = 14dp
             * 闪电   8 × 1.25 = 10dp
             * 偏移  (-4, 4) × 1.25 = (-5, 5)
             * 阴影 2dp（不动，视觉厚度不该放大）
             * ```
             */
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    /**
                     * 用户口径（2026-11-28，#160）**气泡必须贴着「下一集」图标本身**。
                     *
                     * 用户原话：
                     * ```
                     * 「我怎么感觉你那个预加载下一集的小气泡，越改离那个下一集播放的按钮越远呢，
                     *   说的是视频播放的界面啊，不是音频」
                     * ```
                     *
                     * ## 用户是对的，我上一版改反了
                     *
                     * 关键在这个 `Box` 的对齐是 `TopEnd`，而里面的 `PlayerAction`
                     * 是 **60dp 的 IconButton，图标只有 30dp 居中**——
                     * 图标四周各有 **15dp 空白触摸区**。
                     *
                     * 所以气泡（TopEnd 对齐）的偏移量越大，就越靠盒子边缘；
                     * 偏移量越小，反而离居中的图标越远：
                     * ```
                     * offset(-2, 2) → 气泡中心(51, 9)  距图标右上角 8.5dp   ← 上一版，最远
                     * offset(-5, 5) → 气泡中心(48,12)  距图标右上角 4.2dp
                     * offset(-6, 6) → 气泡中心(47,13)  距图标右上角 2.8dp   ← 现在这版
                     * ```
                     * 上一版我把「收小偏移」当成「贴近」了，方向刚好相反。
                     *
                     * ## 为什么不用音乐页的 (-4, 4)
                     *
                     * 音乐页 `IconButton` 是默认 **48dp**（图标 30dp → 每边内缩 9dp），
                     * (-4,4) 在那里距图标约 4dp，视觉上正好。
                     * 视频页内缩是 15dp，同样的 -4 会跑到 10dp 以外。
                     * 所以要按「内缩量之差」补：15-9=6dp 以上。
                     *
                     * 取 (-6, 6)：2.8dp，贴得最紧又不压住图标笔画。
                     */
                    /**
                     * 用户口径（2026-11-28，#162）**还要再近一点**。
                     *
                     * 距图标右上角：
                     * ```
                     * (-6,6) → 2.8dp   上一版
                     * (-7,7) → 1.4dp   现在
                     * ```
                     * 再往外调就会压到图标笔画上了，1.4dp 是「贴着但不重叠」的极限。
                     */
                    .offset(x = (-7).dp, y = 7.dp)
                    .size(14.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .background(Color.White, CircleShape)
                    .shadow(2.dp, CircleShape),
            ) {
                Icon(
                    Icons.Rounded.Bolt,
                    contentDescription = "下一集已预加载",
                    tint = Color.Black,
                    modifier = Modifier.size(10.dp),
                )
            }
        }
    }
}

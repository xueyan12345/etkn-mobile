package xyz.etkn.mobile.player

import java.util.Locale

/**
 * 用户口径（2026-11-28，#211）**内置一份「编码 → 内核 + 解码方式」名单**。
 *
 * ## 为什么要这张表
 *
 * 用户原话：
 * ```
 * 「我的这个思路会不会比现在的自动判断标准效率更高？
 *   因为 ETKN 服务器里边应该有所有影片的编码，还有什么格式啥的吧，
 *   这样能直接在播放的时候就拿到影片的信息，然后自动就选择播放器」
 * 「咱们能整理出一个名单，相当于一个内核选择的白名单，
 *   直接内置进 APP，这样每次播放的时候能直接就秒播，选择最佳的内核」
 * 「看看有没有咱们库里没有的格式，然后人家官方的，或者是民间弄的名单有的，
 *   咱们也先别删，万一以后也加入那种格式的影片入库了呢」
 * 「原盘 ISO 的就不用了，我不看圆盘」
 * ```
 *
 * ## 数据来源（每条都可查证）
 *
 * ```
 * ① ETKN 库全量 mediainfo 扫描（48761/48762 条有数据）
 *    视频编码 15 种、音频编码 15 种
 * ② Android 官方「Supported media formats」（2026-10-07 更新）
 *    平台保证：H.263 / H.264 / HEVC / MPEG-4 SP / VP8 / VP9 / AV1
 * ③ NextLib 官方 README（本 App 用的 FFmpeg 扩展）
 *    视频：H.264 / HEVC / VP8 / VP9 / AV1(dav1d)
 *    —— 但实测 `ffmpegLavc63.1.101-mpeg2video` 也在包里，
 *       README 并不完整，以实测为准
 * ④ mpv 官方 `hwdec-codecs` 默认白名单（vd_lavc.c）
 *    h264,vc1,hevc,vp8,vp9,av1,prores,prores_aw,ffv1,dpx,apv
 * ⑤ 本机实测（OnePlus 13 / Android 16）
 *    MPEG-2 走 Exo+FFmpeg：5ms 就绪；走 mpv 硬解：失败
 * ```
 *
 * ## 三条决策原则
 *
 * ```
 * ① 系统能硬解的 → Exo + 硬解（最快、最省电）
 * ② 系统没硬解的老编码 → Exo + FFmpeg 软解（实测能解，别交给 mpv）
 * ③ 色彩/亮度有问题的 → mpv（HDR10 发暗）或 Exo（DV P5 偏绿）
 * ```
 *
 * **原盘 ISO 不纳入**（用户明确不看圆盘），disc 相关判断保持原样。
 */
object CodecPolicy {

    /** 播放内核。 */
    enum class Engine { Exo, Mpv }

    /** 解码方式。 */
    enum class Decoder { Hardware, Software, Either }

    /**
     * 一条决策结果。
     *
     * @param engine  用哪个内核
     * @param decoder 用硬解还是软解
     * @param reason  命中原因（写进日志，排查时一眼看出走的哪条规则）
     */
    data class Choice(val engine: Engine, val decoder: Decoder, val reason: String)

    /**
     * 视频编码规则表。
     *
     * key = mediainfo 里的 Codec（小写）。
     * 覆盖：库里实有 15 种 + 官方/FFmpeg 名单里可能有、库中暂时没有的（预留）。
     *
     * **预留的原则**：标成 Exo+Software 的一律安全 —— Exo 的 FFmpeg 扩展覆盖面最广，
     * 解不了也只是这一部黑屏，不会把整个 App 拖垮；标成 Mpv 的都是「Exo 确实不行」的。
     */
    private val VIDEO: Map<String, Choice> = buildMap {
        // ═══ 主流编码：系统硬解，Exo 最快 ═══
        put("h264", Choice(Engine.Exo, Decoder.Hardware, "h264 系统硬解"))
        put("avc1", Choice(Engine.Exo, Decoder.Hardware, "h264(avc1) 系统硬解"))
        put("hevc", Choice(Engine.Exo, Decoder.Hardware, "hevc 系统硬解"))
        put("h265", Choice(Engine.Exo, Decoder.Hardware, "hevc(h265) 系统硬解"))
        put("hvc1", Choice(Engine.Exo, Decoder.Hardware, "hevc(hvc1) 系统硬解"))
        put("hev1", Choice(Engine.Exo, Decoder.Hardware, "hevc(hev1) 系统硬解"))
        put("av1", Choice(Engine.Exo, Decoder.Either, "av1 有硬解用硬解，否则 dav1d"))
        put("vp9", Choice(Engine.Exo, Decoder.Either, "vp9"))

        // ═══ 老编码：系统多半没硬解，交给 Exo 的 FFmpeg 软解（实测可行）═══
        // 注意：**不要**给这些选 mpv —— mpv 硬解它们会失败且不回退（#206 实测）
        put("mpeg2video", Choice(Engine.Exo, Decoder.Software, "MPEG-2：实测 Exo+FFmpeg 5ms，mpv 硬解失败"))
        put("mpeg1video", Choice(Engine.Exo, Decoder.Software, "MPEG-1：Exo+FFmpeg"))
        put("mpeg4", Choice(Engine.Exo, Decoder.Software, "MPEG-4 Part2 (DivX/XviD)：Exo+FFmpeg"))
        put("msmpeg4v1", Choice(Engine.Exo, Decoder.Software, "MS-MPEG4 v1：Exo+FFmpeg"))
        put("msmpeg4v2", Choice(Engine.Exo, Decoder.Software, "MS-MPEG4 v2：Exo+FFmpeg"))
        put("msmpeg4v3", Choice(Engine.Exo, Decoder.Software, "MS-MPEG4 v3：Exo+FFmpeg"))
        put("wmv1", Choice(Engine.Exo, Decoder.Software, "WMV1：Exo+FFmpeg"))
        put("wmv2", Choice(Engine.Exo, Decoder.Software, "WMV2：Exo+FFmpeg"))
        put("wmv3", Choice(Engine.Exo, Decoder.Software, "WMV3(VC-1 简单档)：Exo+FFmpeg"))
        put("vc1", Choice(Engine.Exo, Decoder.Software, "VC-1：Exo+FFmpeg"))
        put("rv30", Choice(Engine.Exo, Decoder.Software, "RealVideo 30：Exo+FFmpeg"))
        put("rv40", Choice(Engine.Exo, Decoder.Software, "RealVideo 40：Exo+FFmpeg"))
        put("flv1", Choice(Engine.Exo, Decoder.Software, "Sorenson H.263(FLV)：Exo+FFmpeg"))
        put("h263", Choice(Engine.Exo, Decoder.Software, "H.263：Exo+FFmpeg"))
        put("mjpeg", Choice(Engine.Exo, Decoder.Software, "Motion JPEG：Exo+FFmpeg"))

        // ═══ 专业/中间格式（库里极少，预留）═══
        put("prores", Choice(Engine.Exo, Decoder.Software, "ProRes：Exo+FFmpeg"))
        put("dvvideo", Choice(Engine.Exo, Decoder.Software, "DV：Exo+FFmpeg"))
        put("huffyuv", Choice(Engine.Exo, Decoder.Software, "HuffYUV：Exo+FFmpeg"))
        put("lagarith", Choice(Engine.Exo, Decoder.Software, "Lagarith：Exo+FFmpeg"))
        put("utvideo", Choice(Engine.Exo, Decoder.Software, "Ut Video：Exo+FFmpeg"))
        put("rawvideo", Choice(Engine.Exo, Decoder.Software, "裸 RGB/YUV：Exo+FFmpeg"))
        put("cinepak", Choice(Engine.Exo, Decoder.Software, "Cinepak：Exo+FFmpeg"))
        put("indeo", Choice(Engine.Exo, Decoder.Software, "Indeo：Exo+FFmpeg"))
        put("msvideo1", Choice(Engine.Exo, Decoder.Software, "MS Video 1：Exo+FFmpeg"))

        // ═══ 新一代编码（库里没有，预留）═══
        put("vvc", Choice(Engine.Mpv, Decoder.Software, "H.266/VVC：mpv 的 FFmpeg 版本更新，支持更早"))
        put("h266", Choice(Engine.Mpv, Decoder.Software, "H.266/VVC：mpv"))
        put("avs2", Choice(Engine.Mpv, Decoder.Software, "AVS2（国产）：mpv"))
        put("avs3", Choice(Engine.Mpv, Decoder.Software, "AVS3（国产）：mpv"))
        put("theora", Choice(Engine.Exo, Decoder.Software, "Theora：Exo+FFmpeg"))
        put("vp6", Choice(Engine.Exo, Decoder.Software, "VP6（老 Flash）：Exo+FFmpeg"))
        put("vp8", Choice(Engine.Exo, Decoder.Software, "VP8：Exo+FFmpeg"))
        put("apv", Choice(Engine.Exo, Decoder.Software, "APV（Android16 新编码）：Exo"))
    }

    /**
     * 按视频编码 + 色彩信息给出决策。
     *
     * 色彩信息的优先级高于编码表：
     * ```
     * 杜比视界 P5/P7/P8  → Exo   （mpv 对 P5 偏绿，P7/P8 有 HDR10 兼容层）
     * HDR10 / HLG        → mpv   （Exo 走纹理视图丢元数据 → 发暗）
     * 其余                → 查编码表
     * ```
     *
     * @param codec          mediainfo 的 codec（如 mpeg2video）
     * @param extendedType   ExtendedVideoType（DolbyVision / None）
     * @param videoRange     VideoRange（HDR10 / HLG / 空）
     * @param colorTransfer  ColorTransfer（smpte2084 / arib-std-b67 / ...）
     */
    fun decide(
        codec: String?,
        extendedType: String? = null,
        videoRange: String? = null,
        colorTransfer: String? = null,
    ): Choice {
        val c = codec.orEmpty().lowercase(Locale.ROOT).trim()
        val et = extendedType.orEmpty().lowercase(Locale.ROOT).trim()
        val range = videoRange.orEmpty().uppercase(Locale.ROOT).trim()
        val transfer = colorTransfer.orEmpty().lowercase(Locale.ROOT).trim()

        // ① 杜比视界：全部交给 Exo
        val isDv = et.contains("dolbyvision") ||
            c.startsWith("dvhe") || c.startsWith("dvh1")
        if (isDv) {
            return Choice(Engine.Exo, Decoder.Either, "杜比视界（Exo 颜色正确，mpv P5 偏绿）")
        }

        // ② HDR10 / HLG：交给 mpv 做 tone-map
        val isHdr = range.contains("HDR") ||
            transfer == "smpte2084" || transfer == "arib-std-b67"
        if (isHdr) {
            return Choice(Engine.Mpv, Decoder.Either, "HDR（$range/$transfer）→ mpv tone-map 防发暗")
        }

        // ③ 查编码表
        VIDEO[c]?.let { return it }

        // ④ 表里没有 → 默认 Exo 软解（覆盖面最广，解不了只影响这一部）
        return Choice(Engine.Exo, Decoder.Either, "未知编码「$c」→ 兜底 Exo")
    }

    /**
     * 音频编码名单（仅用于诊断日志；播放器本身会自行协商音频解码器）。
     *
     * 库里有 15 种：aac ac3 cook dts eac3 flac mp2 mp3 opus pcm_s16le
     * pcm_s24le truehd wmapro wmav2 wmavoice
     * 预留：vorbis alac dca(dts-hd) mlp amr pcm_* speex mp1
     */
    private val AUDIO_SOFT_ONLY: Set<String> = setOf(
        "truehd", "mlp", "dts", "dca", "eac3", "ac3",
        "wmapro", "wmav2", "wmavoice", "cook", "mp2",
        "alac", "vorbis", "opus", "speex", "mp1",
    )

    /** 这个音频编码是否「基本只能软解」（用于日志提示，不做强制）。 */
    fun audioNeedsSoftware(codec: String?): Boolean =
        AUDIO_SOFT_ONLY.contains(codec.orEmpty().lowercase(Locale.ROOT).trim())

    /** 已知的全部视频编码（表里有的），供诊断/统计用。 */
    fun knownVideoCodecs(): List<String> = VIDEO.keys.sorted()
}

package xyz.etkn.mobile.player.bluray

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.CodecSpecificDataUtil
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.container.NalUnitUtil
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import okhttp3.OkHttpClient
import xyz.etkn.mobile.player.DolbyVision
import xyz.etkn.mobile.player.DolbyVisionTrackOutput
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.util.Locale

/**
 * MediaItem tag from the server's media source: [disc] when it is a Blu-ray image (`Container`
 * iso or an `.iso` path — the stream URL itself may lack the suffix, e.g. a cloud STRM target),
 * and the probed main [playlist] or -1 for libbluray's own pick.
 */
data class BlurayTag(val disc: Boolean, val playlist: Int, val hints: Map<Int, DiscHint> = emptyMap())

/** What the server's disc probe knows about one PID; fills gaps the demuxer leaves (size, channels). */
data class DiscHint(val width: Int, val height: Int, val channels: Int)

/**
 * Full-disc ISO playback, ported from ETKN TV. A `.iso` URI goes through libbluray: the ISO is
 * read by HTTP Range, the main title's MPEG-TS is demuxed natively and fed to the ordinary
 * ProgressiveMediaSource, so decoding, seeking, track selection and the player lifecycle stay
 * Media3's (hardware MediaCodec first). Everything else uses [ordinary].
 */
class BlurayMediaSourceFactory(
    private val ordinary: MediaSource.Factory,
    private val http: OkHttpClient,
    private val userAgent: String,
    private val upstream: DataSource.Factory,
) : MediaSource.Factory {

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = requireNotNull(mediaItem.localConfiguration)
        val uri = config.uri
        val tag = config.tag as? BlurayTag
        if (tag?.disc != true && !isIso(uri)) return ordinary.createMediaSource(mediaItem)
        val playlist = tag?.playlist ?: -1
        val session = Session(BlurayInput(http, uri, userAgent), playlist, tag?.hints.orEmpty())
        val data = DataSource.Factory { DiscDataSource(session, uri, upstream.createDataSource()) }
        val fallback = baseLayerExtractors()
        val extractors = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> = arrayOf(DiscExtractor(session))
            override fun createExtractors(address: Uri, headers: Map<String, List<String>>): Array<Extractor> =
                if (address == uri) createExtractors() else fallback.createExtractors(address, headers)
        }
        return DefaultMediaSourceFactory(data, extractors).createMediaSource(mediaItem)
    }

    override fun getSupportedTypes(): IntArray = ordinary.supportedTypes

    override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider): MediaSource.Factory {
        ordinary.setDrmSessionManagerProvider(drmSessionManagerProvider)
        return this
    }

    override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy): MediaSource.Factory {
        ordinary.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy)
        return this
    }

    companion object {
        fun isIso(uri: Uri): Boolean = uri.path?.lowercase(Locale.ROOT)?.endsWith(".iso") == true

        /**
         * Dolby Vision profile 7 (dual-layer, Blu-ray) has no phone decoder; its base layer is
         * plain HEVC HDR10, so present it as such instead of failing the DV decoder lookup.
         */
        val DV_PROFILE_5 = Regex("(dv(?:he|h1))\\.0?5\\.(\\d+)")

        fun baseLayer(format: Format): Format {
            val codecs = format.codecs?.lowercase(Locale.ROOT) ?: return format
            if (format.sampleMimeType != MimeTypes.VIDEO_DOLBY_VISION) return format
            /**
             * 用户口径（2026-11-26）**杜比视界 profile 5 也要降级成普通 HEVC**。
             *
             * 原来这里只处理 profile 7（因为 profile 7 有 HDR10 基础层，降级后还能看）。
             * 但实测（谷雨 = dvhe.05.08）：
             *  · profile 5 保持 video/dolby-vision → 交给 DV 解码器
             *  · 平板走纹理视图，IPTPQc2 色彩元数据在这一层丢失
             *  · 结果：Exo 偏色、mpv 也偏色（两个内核都偏）
             *
             * profile 5 的码流本质就是 HEVC（只是套了层 DV 元数据），
             * 降级成 video/hevc 交给系统 HEVC 解码器后：
             *  · 系统按标准流程处理亮度/色彩
             *  · 不会再有 IPTPQc2 被误读的问题
             * 代价是失去 DV 动态元数据（画质略降），但颜色正确、能看。
             */
            val isP7 = DV_PROFILE_7.matches(codecs)
            val isP5 = DV_PROFILE_5.matches(codecs)
            if (!isP7 && !isP5) return format
            val baseCodecs = NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData)
                ?: return format   // 拿不到基础层 codec 就原样返回，别把 format 搞坏
            return format.buildUpon()
                .setSampleMimeType(MimeTypes.VIDEO_H265)
                .setCodecs(baseCodecs)
                .build()
        }

        /**
         * Default extractors whose Dolby Vision profile 7 video plays as profile 8.1 where the
         * device can ([DolbyVision]), else as its HDR10 base layer ([baseLayer]).
         */
        fun baseLayerExtractors(
            delegate: ExtractorsFactory = DefaultExtractorsFactory(),
            convert: Boolean = DolbyVision.convertProfile7,
        ): ExtractorsFactory = object : ExtractorsFactory {
            override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())
            override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
                wrap(delegate.createExtractors(uri, responseHeaders))

            private fun wrap(extractors: Array<Extractor>): Array<Extractor> = Array(extractors.size) { i ->
                object : ForwardingExtractor(extractors[i]) {
                    override fun init(output: ExtractorOutput) {
                        super.init(object : ForwardingExtractorOutput(output) {
                            private val tracks = HashMap<Int, TrackOutput>()
                            override fun track(id: Int, type: Int): TrackOutput = tracks.getOrPut(id) {
                                DolbyVisionTrackOutput(super.track(id, type), convert && type == C.TRACK_TYPE_VIDEO)
                            }
                        })
                    }
                }
            }
        }

        private val DV_PROFILE_7 = Regex("dv(?:he|h1)\\.0?7(?:\\..*)?")

        private const val LOG_TAG = "ETKN-Disc"

        /** Blu-ray primary video PID; a disc track's format id is `etkn-ts-<pid>-<mime>`. */
        const val PRIMARY_VIDEO_PID = 0x1011
        /** UHD Blu-ray Dolby Vision enhancement layer (carries the RPUs). */
        const val DV_ENHANCEMENT_PID = 0x1015
        private const val MAX_RPU_WAIT = 8
        private val DISC_TRACK = Regex("etkn-ts-(\\d+)-")

        /** PID of a disc track from its (possibly source-prefixed) format id, else -1. */
        fun discPid(formatId: String?): Int = formatId?.let { DISC_TRACK.find(it) }?.groupValues?.get(1)?.toIntOrNull() ?: -1
    }

    /**
     * One opened title. Reads happen on the loading thread, but the seek map is queried from the
     * playback thread, which may outlive the handle: those calls go through [seekPoint] / [durationUs]
     * and never touch a closed native handle.
     */
    private class Session(val input: BlurayInput, val playlist: Int, val hints: Map<Int, DiscHint>) {
        @Volatile var handle = 0L
        @Volatile var durationUs = C.TIME_UNSET

        @Synchronized
        fun open(): Long {
            if (handle == 0L) {
                if (!BlurayNative.loaded) throw IOException("原盘组件未能加载")
                handle = BlurayNative.open(input, playlist)
                durationUs = BlurayNative.duration(handle)
            }
            return handle
        }

        /** Loading-thread handle; a closed session surfaces as an I/O error, not a native crash. */
        fun current(): Long = handle.takeIf { it != 0L } ?: throw IOException("原盘已关闭")

        @Synchronized
        fun seekPoint(timeUs: Long): Long = if (handle == 0L) 0 else BlurayNative.seekPoint(handle, timeUs)

        @Synchronized
        fun close() {
            if (handle != 0L) {
                BlurayNative.close(handle)
                handle = 0
            }
            runCatching { input.close() }
        }
    }

    /** The ISO URI reads the selected title's transport stream; any other URI is passed through. */
    private class DiscDataSource(val session: Session, val iso: Uri, val fallback: DataSource) : BaseDataSource(true) {
        private var disc = false
        private var started = false
        private var remaining = 0L

        override fun open(dataSpec: DataSpec): Long {
            disc = dataSpec.uri == iso
            if (!disc) return fallback.open(dataSpec)
            transferInitializing(dataSpec)
            try {
                val handle = session.open()
                val size = BlurayNative.size(handle)
                if (dataSpec.position < 0 || dataSpec.position > size) throw IOException("原盘跳转超出正片范围")
                if (BlurayNative.seek(handle, dataSpec.position) != dataSpec.position) throw IOException("原盘无法跳转到指定位置")
                remaining = size - dataSpec.position
                if (dataSpec.length != C.LENGTH_UNSET.toLong()) remaining = minOf(remaining, dataSpec.length)
                transferStarted(dataSpec)
                started = true
                return remaining
            } catch (error: Exception) {
                session.close()
                throw error
            }
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!disc) return fallback.read(buffer, offset, length)
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val count = BlurayNative.read(session.current(), buffer, offset, minOf(remaining, length.toLong()).toInt())
            if (count <= 0) throw EOFException("原盘正片数据不完整")
            remaining -= count
            bytesTransferred(count)
            return count
        }

        override fun getUri(): Uri? = if (disc) iso else fallback.uri

        override fun close() {
            if (!disc) {
                fallback.close()
                return
            }
            if (started) {
                started = false
                transferEnded()
            }
        }
    }

    /** Native packets → Media3 samples; track identity is PID + codec (TrueHD and its AC3 core share a PID). */
    private class DiscExtractor(val session: Session) : Extractor {
        private lateinit var output: ExtractorOutput
        private val known = LinkedHashMap<String, TrackOutput>()
        private val tracks = HashMap<Int, TrackOutput>()
        private val pgs = HashMap<Int, PgsAssembler>()
        private var buffer = ByteArray(2 * 1024 * 1024)
        private val metadata = LongArray(3)
        private var initialized = false
        // Dolby Vision profile 7 played as 8.1: base-layer frames wait for the enhancement layer's
        // RPU of the same presentation time, which is converted and appended to the frame.
        private var dvPicture = -1
        private var dvEnhancement = -1
        private val rpus = java.util.TreeMap<Long, ByteArray>()
        private val waiting = ArrayDeque<Frame>()

        private class Frame(val timeUs: Long, val flags: Int, val bytes: ByteArray, val size: Int)

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
            if (!initialized) {
                val descriptions = BlurayNative.tracks(session.open(), input, input.position)
                val first = known.isEmpty()
                tracks.clear()
                pgs.clear()
                // One picture: the primary video (PID 0x1011). A Dolby Vision profile 7 disc also
                // carries its enhancement layer (0x1015), and secondary video may exist; neither
                // plays on its own, and exposing them lets the selector pick a wrong stream.
                val videos = descriptions.filter { it.type == C.TRACK_TYPE_VIDEO }
                val picture = videos.firstOrNull { it.pid == PRIMARY_VIDEO_PID } ?: videos.firstOrNull()
                val enhancement = videos.firstOrNull { it.pid == DV_ENHANCEMENT_PID }
                val dolbyVision = DolbyVision.convertProfile7 && picture?.pid == PRIMARY_VIDEO_PID && enhancement != null
                dvPicture = if (dolbyVision) picture!!.index else -1
                dvEnhancement = if (dolbyVision) enhancement!!.index else -1
                if (first) {
                    Log.i(LOG_TAG, "disc title ${session.durationUs / 1_000_000}s playlist=${session.playlist} tracks: " + descriptions.joinToString(" | ") {
                        "${it.pid}:${it.mime}" + (if (it.width > 0) " ${it.width}x${it.height}" else "") +
                            (if (it.channels > 0) " ${it.channels}ch ${it.sampleRate}Hz" else "") +
                            " init=${it.initialization.size}" + (if (it.type == C.TRACK_TYPE_VIDEO && it !== picture) " skipped" else "")
                    } + if (dolbyVision) " | dolby vision 7->8.1" else "")
                }
                for (track in descriptions) {
                    if (track.type == C.TRACK_TYPE_VIDEO && track !== picture) continue
                    val identity = "etkn-ts-${track.pid}-${track.mime}"
                    var target = known[identity]
                    if (first) {
                        val format = Format.Builder().setId(identity).setSampleMimeType(track.mime)
                            .setContainerMimeType(MimeTypes.VIDEO_MP2T).setLanguage(track.language.ifEmpty { null })
                        val hint = session.hints[track.pid]
                        if (track.type == C.TRACK_TYPE_VIDEO) {
                            // The demux-only FFmpeg reports no picture size; hardware decoders refuse
                            // 0×0, so take it from the stream's own SPS, then from the server's probe.
                            val header = VideoHeader.parse(track.mime, track.initialization)
                            format.setWidth(header?.width ?: track.width.takeIf { it > 0 } ?: hint?.width ?: Format.NO_VALUE)
                                .setHeight(header?.height ?: track.height.takeIf { it > 0 } ?: hint?.height ?: Format.NO_VALUE)
                                .setMaxInputSize(buffer.size)
                            header?.color?.let(format::setColorInfo)
                        }
                        if (track.type == C.TRACK_TYPE_AUDIO) {
                            format.setChannelCount(track.channels.takeIf { it > 0 } ?: hint?.channels?.takeIf { it > 0 } ?: Format.NO_VALUE)
                                .setSampleRate(track.sampleRate).setPcmEncoding(track.pcmEncoding)
                        }
                        if (track.initialization.isNotEmpty()) {
                            val nals = if (track.mime == MimeTypes.VIDEO_H264) CodecSpecificDataUtil.splitNalUnits(track.initialization) else null
                            format.setInitializationData(nals?.toList() ?: listOf(track.initialization))
                        }
                        target = output.track(known.size, track.type)
                        target.format(if (dolbyVision && track === picture) DolbyVision.profile81(format.build()) else baseLayer(format.build()))
                        known[identity] = target
                    }
                    if (target != null) {
                        tracks[track.index] = target
                        if (track.mime == MimeTypes.APPLICATION_PGS) pgs[track.index] = PgsAssembler(target)
                    }
                }
                if (first) {
                    output.endTracks()
                    output.seekMap(object : SeekMap {
                        override fun isSeekable() = true
                        override fun getDurationUs() = session.durationUs
                        override fun getSeekPoints(timeUs: Long) = SeekMap.SeekPoints(SeekPoint(timeUs, session.seekPoint(timeUs)))
                    })
                }
                initialized = true
            }
            val count = BlurayNative.packet(session.current(), input, buffer, metadata)
            if (count < -1) {
                if (-count > 16 * 1024 * 1024) throw IOException("原盘视频帧过大")
                buffer = ByteArray(-count)
                return Extractor.RESULT_CONTINUE
            }
            if (count == -1) {
                flushFrames(force = true)
                return Extractor.RESULT_END_OF_INPUT
            }
            if (count > 0) {
                val index = metadata[0].toInt()
                if (index == dvEnhancement) {
                    DolbyVision.convertedRpu(buffer, 0, count)?.let { rpus[metadata[1]] = it }
                    while (rpus.size > 64) rpus.pollFirstEntry()
                    flushFrames(force = false)
                    return Extractor.RESULT_CONTINUE
                }
                if (index == dvPicture) {
                    waiting.addLast(Frame(metadata[1], metadata[2].toInt(), buffer.copyOf(count), count))
                    flushFrames(force = false)
                    return Extractor.RESULT_CONTINUE
                }
                val subtitles = pgs[index]
                val track = tracks[index]
                if (subtitles != null) {
                    subtitles.sample(buffer, count, metadata[1], metadata[2].toInt())
                } else if (track != null) {
                    track.sampleData(ParsableByteArray(buffer, count), count)
                    track.sampleMetadata(metadata[1], metadata[2].toInt(), count, 0, null)
                }
            }
            return Extractor.RESULT_CONTINUE
        }

        /** Hands on waiting frames whose RPU arrived (or that waited too long, or all at the end). */
        private fun flushFrames(force: Boolean) {
            val track = tracks[dvPicture] ?: return waiting.clear()
            while (waiting.isNotEmpty()) {
                val head = waiting.first()
                val rpu = rpus.remove(head.timeUs)
                if (rpu == null && !force && waiting.size <= MAX_RPU_WAIT) return
                waiting.removeFirst()
                val bytes = if (rpu != null) DolbyVision.append(head.bytes, head.size, rpu) else head.bytes
                val size = if (rpu != null) bytes.size else head.size
                track.sampleData(ParsableByteArray(bytes, size), size)
                track.sampleMetadata(head.timeUs, head.flags, size, 0, null)
            }
        }

        override fun seek(position: Long, timeUs: Long) {
            waiting.clear()
            rpus.clear()
            pgs.values.forEach(PgsAssembler::reset)
            if (initialized) {
                if (session.handle != 0L) BlurayNative.reset(session.handle)
                initialized = false
            }
        }

        override fun release() {
            pgs.clear()
            session.close()
        }
    }

    /** Disc PGS: publish a display set only at its END segment, stamped with the presentation time. */
    private class PgsAssembler(private val output: TrackOutput) {
        private var pending: ByteArrayOutputStream? = null
        private var timeUs = 0L
        private var flags = 0

        fun reset() {
            pending = null
        }

        fun sample(packet: ByteArray, length: Int, time: Long, sampleFlags: Int) {
            var position = 0
            while (length - position >= 3) {
                val type = packet[position].toInt() and 255
                val size = 3 + ((packet[position + 1].toInt() and 255) shl 8) + (packet[position + 2].toInt() and 255)
                if (size > length - position) {
                    reset()
                    return
                }
                if (type == 0x16) {
                    reset()
                    if (size < 14) return
                    pending = ByteArrayOutputStream()
                    timeUs = time
                    flags = sampleFlags
                }
                pending?.let { set ->
                    if (size > MAX_BYTES - set.size()) {
                        reset()
                        return
                    }
                    set.write(packet, position, size)
                    if (type == 0x80) {
                        val displaySet = set.toByteArray()
                        output.sampleData(ParsableByteArray(displaySet), displaySet.size)
                        output.sampleMetadata(timeUs, flags, displaySet.size, 0, null)
                        reset()
                    }
                }
                position += size
            }
            if (position != length) reset()
        }

        private companion object {
            const val MAX_BYTES = 16 * 1024 * 1024
        }
    }
}

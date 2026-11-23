package xyz.etkn.mobile.player

import android.content.Context
import android.content.Intent
import xyz.etkn.mobile.data.downloads.DownloadEntry
import xyz.etkn.mobile.data.model.Item
import xyz.etkn.mobile.graph

object PlayerLauncher {
    const val EXTRA_ITEM = "item_id"
    const val EXTRA_TYPE = "item_type"
    const val EXTRA_SERIES = "series_id"
    const val EXTRA_SEASON = "season_id"
    const val EXTRA_RESUME = "resume"
    const val EXTRA_START_MS = "start_ms"
    const val EXTRA_SOURCE = "source_id"
    const val EXTRA_DOWNLOAD = "download_id"

    /**
     * 用户口径（2026-11-23）：点章节要从该章节起点开始播。
     * [startMs] 优先于续播进度（章节跳转不能被抓取的上次进度覆盖）。
     */
    fun play(
        context: Context,
        item: Item,
        resume: Boolean = true,
        sourceId: String? = null,
        startMs: Long? = null,
    ) {
        PlaybackPrefetch.start(context.graph, item)
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_ITEM, item.id)
            .putExtra(EXTRA_TYPE, item.type)
            .putExtra(EXTRA_SERIES, item.seriesId ?: if (item.isSeries) item.id else null)
            .putExtra(EXTRA_SEASON, item.seasonId ?: if (item.isSeason) item.id else null)
            .putExtra(EXTRA_RESUME, resume)
            .putExtra(
                EXTRA_START_MS,
                // 显式指定起点（章节跳转）优先；否则按续播逻辑
                startMs ?: if (resume && !item.userData.played) item.positionMs else 0L,
            )
            .putExtra(EXTRA_SOURCE, sourceId)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /** A finished download, played from disk. */
    fun playDownload(context: Context, entry: DownloadEntry) {
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(EXTRA_ITEM, entry.itemId)
            .putExtra(EXTRA_DOWNLOAD, entry.downloadId)
        if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

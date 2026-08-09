package org.veliora.television

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream

/**
 * m3u8 播放列表广告过滤 DataSource。
 * 行为对齐 js/player.js filterAdsFromM3U8。
 * 仅对 .m3u8 请求生效并全量读入内存（播放列表只有 KB 级），TS 分片原样透传。
 *
 * ⚠️ #EXT-X-DISCONTINUITY 不可以直接删掉。
 * 它是「此处时间戳不连续，请重置时间基准」的信号：采集站的中插广告来自另一套转码，
 * PTS 和正片不在同一条时间轴上。删标记但保留分片（本类最初的做法）等于让解析器
 * 按连续时间轴处理，撞到 PTS 大跳时会当成时间戳回卷去「纠正」，得到一堆废时间戳，
 * 播放位置永远等不到可播数据 —— 表现就是播到广告点反复缓冲、卡死不动，
 * 而且广告一片没删（老逻辑只删标记行，#EXTINF 和分片 URL 全留着），纯有害。
 *
 * 现在的做法：按 discontinuity 把播放列表切成块，删掉判定为广告的整块分片，
 * 保留下来的块之间仍留一个 discontinuity 标记（拼接处时间戳本来就可能不连续）。
 * 判不准就整块留下 —— 有标记在，播放至少是正确的，最坏结果只是广告照播。
 */
@UnstableApi
class M3u8AdFilterDataSource(private val upstream: DataSource) : DataSource {

    class Factory(private val upstreamFactory: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            M3u8AdFilterDataSource(upstreamFactory.createDataSource())
    }

    companion object {
        // 播放列表大小保险上限；超过则视为异常内容，直接截断
        private const val MAX_PLAYLIST_BYTES = 10 * 1024 * 1024
    }

    private var filtered: ByteArray? = null
    private var readPos = 0
    private var passthrough = false

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val path = dataSpec.uri.path ?: ""
        if (!path.endsWith(".m3u8", ignoreCase = true)) {
            passthrough = true
            return upstream.open(dataSpec)
        }

        passthrough = false
        upstream.open(dataSpec)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() < MAX_PLAYLIST_BYTES) {
            val n = upstream.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }

        // 相对分片路径要按重定向后的最终 URL 解析，才能比出「哪些分片不是同一套转码」
        val base = upstream.uri ?: dataSpec.uri
        val result = M3u8AdFilter.filter(out.toByteArray().toString(Charsets.UTF_8), base)
            .toByteArray(Charsets.UTF_8)
        filtered = result
        readPos = 0
        return result.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (passthrough) return upstream.read(buffer, offset, length)
        val data = filtered ?: return C.RESULT_END_OF_INPUT
        if (readPos >= data.size) return C.RESULT_END_OF_INPUT
        val n = minOf(length, data.size - readPos)
        System.arraycopy(data, readPos, buffer, offset, n)
        readPos += n
        return n
    }

    override fun getUri(): Uri? = upstream.uri

    override fun close() {
        filtered = null
        upstream.close()
    }
}

/**
 * 播放列表级的广告分片过滤，纯字符串处理，与 DataSource 解耦（便于对照 js 版本）。
 */
internal object M3u8AdFilter {

    private const val TAG = "VelioraAdFilter"
    private const val DISCONTINUITY = "#EXT-X-DISCONTINUITY"

    // 广告块时长上限：中插广告都是几十秒量级。超过这个长度的异源块宁可留着，
    // 免得把「正片分了两个目录存放」的片源砍掉半部片
    private const val MAX_AD_BLOCK_SEC = 180.0

    private class Segment(val tags: List<String>, val uri: String, val durationSec: Double)

    fun filter(content: String, playlistUri: Uri): String {
        // 主播放列表（只有 #EXT-X-STREAM-INF）与无广告分界的普通列表都原样返回
        if (!content.contains("#EXTINF")) return content
        // 注意 #EXT-X-DISCONTINUITY-SEQUENCE 也含这个前缀，只认独占一行的标记
        if (content.lineSequence().none { it.trim() == DISCONTINUITY }) return content

        val header = mutableListOf<String>()
        val trailer = mutableListOf<String>()
        val blocks = mutableListOf<MutableList<Segment>>(mutableListOf())
        val pendingTags = mutableListOf<String>()
        var seenSegment = false

        for (raw in content.split('\n')) {
            val line = raw.trimEnd('\r')
            val t = line.trim()
            when {
                t.isEmpty() -> Unit
                t == DISCONTINUITY -> blocks.add(mutableListOf())
                t == "#EXT-X-ENDLIST" -> trailer.add(line)
                t.startsWith("#") ->
                    if (seenSegment || t.startsWith("#EXTINF")) pendingTags.add(line)
                    else header.add(line)
                else -> {
                    seenSegment = true
                    val dur = pendingTags.firstOrNull { it.trim().startsWith("#EXTINF") }
                        ?.let { extinfDuration(it) } ?: 0.0
                    blocks.last().add(Segment(pendingTags.toList(), line, dur))
                    pendingTags.clear()
                }
            }
        }
        // 收尾时还挂着的标签（没有对应分片）跟着尾部一起输出，不丢内容
        val dangling = pendingTags.toList()

        val kept = blocks.filter { it.isNotEmpty() }
        if (kept.size < 2) return content

        // 正片目录的锚点：优先取播放列表自己所在的目录（正片分片绝大多数就放在它旁边），
        // 分片另挂 CDN 导致一块都不在这个目录时，退化为「总时长最长那块」。
        // 分片目录不同 = 另一套转码，几乎必是插进来的广告。
        val selfDir = dirOf(".", playlistUri)
        val dirs = kept.map { blockDir(it, playlistUri) }
        val mainDir = if (dirs.contains(selfDir)) selfDir
        else dirs[kept.indices.maxByOrNull { i -> kept[i].sumOf { it.durationSec } } ?: return content]
        // 正片目录得占住大头才敢动手：占不到就说明这个列表不符合「正片 + 中插广告」的形态
        // （比如正片本身横跨多个目录），此时原样返回，宁可广告照播也不砍掉正片
        val totalSec = kept.sumOf { block -> block.sumOf { it.durationSec } }
        val mainSec = kept.indices.sumOf { i ->
            if (dirs[i] == mainDir) kept[i].sumOf { it.durationSec } else 0.0
        }
        if (totalSec > 0 && mainSec < totalSec * 0.6) return content

        var droppedSegments = 0
        var droppedSec = 0.0
        val out = StringBuilder()
        header.forEach { out.append(it).append('\n') }
        var emittedBlock = false
        for ((i, block) in kept.withIndex()) {
            val dur = block.sumOf { it.durationSec }
            val isAd = dirs[i] != mainDir &&
                dur <= MAX_AD_BLOCK_SEC &&
                // 整块含密钥声明时不敢删：后面的分片可能依赖这次 #EXT-X-KEY 换钥
                block.none { seg -> seg.tags.any { it.trim().startsWith("#EXT-X-KEY") } }
            if (isAd) {
                droppedSegments += block.size
                droppedSec += dur
                continue
            }
            // 块与块之间原本就有 discontinuity，保留它（多一个标记只是多一次时间基准重置，无害）
            if (emittedBlock) out.append(DISCONTINUITY).append('\n')
            emittedBlock = true
            for (seg in block) {
                seg.tags.forEach { out.append(it).append('\n') }
                out.append(seg.uri).append('\n')
            }
        }
        // 一片广告都没删的话，原样返回，不做无谓的重写（空行、行尾都保持源站原貌）
        if (droppedSegments == 0) return content
        dangling.forEach { out.append(it).append('\n') }
        trailer.forEach { out.append(it).append('\n') }

        Log.i(TAG, "过滤广告分片 $droppedSegments 个 / ${droppedSec.toInt()} 秒")
        return out.toString()
    }

    /** #EXTINF:12.5,title → 12.5 */
    private fun extinfDuration(line: String): Double {
        val v = line.substringAfter(':', "").substringBefore(',').trim()
        return v.toDoubleOrNull() ?: 0.0
    }

    /** 块内分片所在目录（取出现最多的那个），用于区分正片与异源广告 */
    private fun blockDir(block: List<Segment>, playlistUri: Uri): String =
        block.map { dirOf(it.uri, playlistUri) }
            .groupingBy { it }.eachCount()
            .maxByOrNull { it.value }?.key ?: ""

    private fun dirOf(segUri: String, playlistUri: Uri): String {
        val abs = try {
            java.net.URI(playlistUri.toString()).resolve(segUri).toString()
        } catch (_: Exception) {
            segUri   // 解析不了（含未编码字符等）就按原样比，同源分片字面量本来也一致
        }
        val noQuery = abs.substringBefore('?').substringBefore('#')
        return noQuery.substringBeforeLast('/', noQuery)
    }
}

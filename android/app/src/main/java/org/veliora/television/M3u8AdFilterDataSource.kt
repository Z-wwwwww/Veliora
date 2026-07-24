package org.veliora.television

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream

/**
 * m3u8 播放列表广告过滤 DataSource。
 * 行为对齐 js/player.js filterAdsFromM3U8：移除含 #EXT-X-DISCONTINUITY 的行
 * （采集站的中插广告以 discontinuity 分界注入）。
 * 仅对 .m3u8 请求生效并全量读入内存（播放列表只有 KB 级），TS 分片原样透传。
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

        val result = out.toByteArray().toString(Charsets.UTF_8)
            .lineSequence()
            .filterNot { it.contains("#EXT-X-DISCONTINUITY") }
            .joinToString("\n")
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

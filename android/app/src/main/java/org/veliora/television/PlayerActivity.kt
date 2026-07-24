package org.veliora.television

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.graphics.Color
import android.net.TrafficStats
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

/**
 * 原生播放页：替代 WebView 里 player.html（ArtPlayer + hls.js + MSE）的播放链路。
 * HLS 由 Media3 原生解析，MediaCodec 硬解直通，4K/HEVC 能力取决于芯片而非 WebView。
 *
 * 遥控器约定（控制条隐藏时）：
 * - 左/右：快退/快进 15 秒；OK：暂停/继续并唤出控制条
 * - 上/下：唤出控制条（内含上一集/下一集/进度条，方向键导航）
 * - 菜单键 / 长按 OK：清晰度选择（仅多码率源），手动锁档并记忆偏好
 * - 返回：控制条可见则先收起，否则退出回到选片页
 */
@OptIn(UnstableApi::class)
class PlayerActivity : Activity() {

    companion object {
        const val EXTRA_EPISODES = "episodes"     // ArrayList<String>：全部集数直链
        const val EXTRA_INDEX = "index"           // 起播集数下标
        const val EXTRA_TITLE = "title"
        const val EXTRA_POSITION_SEC = "position" // 页面传来的续播秒数（可为 0）
        const val EXTRA_AD_FILTER = "adFilter"
        const val RESULT_FALLBACK = 9             // 原生播放失败 → MainActivity 回退 WebView 播放器

        private const val PREFS = "playbackProgress"
        private const val SEEK_STEP_MS = 15_000L
        private const val MIN_RESUME_MS = 10_000L   // 进度小于此值不续播
        private const val NEAR_END_MS = 30_000L     // 距结尾小于此值视为已看完

        // 清晰度偏好（按分辨率高度记忆，0=自动；下划线前缀避免与进度存储的 URL 键冲突）
        private const val KEY_QUALITY = "__preferredQuality"
        private const val OK_LONG_PRESS_MS = 600L
        private const val SPEED_INTERVAL_MS = 500L
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private lateinit var loadingBox: LinearLayout
    private lateinit var speedText: TextView
    private lateinit var prefs: SharedPreferences
    private var player: ExoPlayer? = null
    private var episodes: List<String> = emptyList()
    private var videoTitle = ""

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    // ---------- 缓冲加载层（转圈 + 实时下载网速） ----------
    private var lastRxBytes = 0L
    private var lastRxTime = 0L
    private val speedTicker = object : Runnable {
        override fun run() {
            val now = android.os.SystemClock.elapsedRealtime()
            val rx = readRxBytes()
            if (rx >= 0 && lastRxTime > 0 && lastRxBytes >= 0 && rx >= lastRxBytes) {
                val elapsedMs = now - lastRxTime
                if (elapsedMs > 0) {
                    val bytesPerSec = (rx - lastRxBytes) * 1000 / elapsedMs
                    speedText.text = "加载中… " + formatSpeed(bytesPerSec)
                }
            }
            lastRxBytes = rx
            lastRxTime = now
            handler.postDelayed(this, SPEED_INTERVAL_MS)
        }
    }

    // ---------- 清晰度 ----------
    private data class QualityOption(val group: Tracks.Group, val trackIndex: Int, val height: Int, val bitrate: Int)

    private var qualityOptions: List<QualityOption> = emptyList()
    private var manualQualityHeight = 0   // 手动锁定的分辨率高度，0=自动（自适应）
    private var currentVideoHeight = 0    // 实际在播的分辨率高度（用于浮层显示）
    private var okLongPressFired = false
    private val okLongPress = Runnable { okLongPressFired = true; openQualityDialog() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        episodes = intent.getStringArrayListExtra(EXTRA_EPISODES) ?: arrayListOf()
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        if (episodes.isEmpty()) {
            finish()
            return
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        playerView = PlayerView(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
            controllerShowTimeoutMs = 4000
            controllerAutoShow = false
            setShowNextButton(episodes.size > 1)
            setShowPreviousButton(episodes.size > 1)
        }
        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(28, 14, 28, 14)
            setBackgroundColor(0x99000000.toInt())
            visibility = View.GONE
        }
        speedText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            text = "加载中…"
        }
        loadingBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 36, 48, 36)
            setBackgroundColor(0x99000000.toInt())
            addView(ProgressBar(this@PlayerActivity), LinearLayout.LayoutParams(96, 96))
            addView(speedText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 20 })
            visibility = View.GONE
        }

        val root = FrameLayout(this)
        root.addView(
            playerView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.START
            ).apply { setMargins(40, 40, 0, 0) }
        )
        root.addView(
            loadingBox,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        setContentView(root)

        initPlayer()
    }

    private fun initPlayer() {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(ProxyHandler.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(30_000)
        val dataSourceFactory: DataSource.Factory =
            if (intent.getBooleanExtra(EXTRA_AD_FILTER, true))
                M3u8AdFilterDataSource.Factory(httpFactory)
            else httpFactory

        // 硬解优先，失败时允许回退软解（低端芯片上部分异常流可救活）
        val renderersFactory = DefaultRenderersFactory(this).setEnableDecoderFallback(true)

        val exo = ExoPlayer.Builder(this, renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            .build()
        player = exo
        playerView.player = exo

        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentVideoHeight = 0
                showOverlayHint()
            }

            override fun onTracksChanged(tracks: Tracks) {
                rebuildQualityOptions(tracks)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0 && videoSize.height != currentVideoHeight) {
                    currentVideoHeight = videoSize.height
                    showOverlayHint()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> showLoading()
                    Player.STATE_ENDED -> finish() // 全部集数播完
                    else -> hideLoading()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // 原生管线播不了的流（如奇葩封装），回退 WebView hls.js 播放器兜底
                Toast.makeText(
                    this@PlayerActivity,
                    "原生播放失败，切换网页播放器…",
                    Toast.LENGTH_SHORT
                ).show()
                setResult(RESULT_FALLBACK)
                finish()
            }
        })

        val startIndex = intent.getIntExtra(EXTRA_INDEX, 0).coerceIn(0, episodes.size - 1)
        val extraPosMs = intent.getIntExtra(EXTRA_POSITION_SEC, 0) * 1000L
        val savedPosMs = savedProgress(episodes[startIndex])
        val startPosMs = when {
            extraPosMs > MIN_RESUME_MS -> extraPosMs
            savedPosMs > 0 -> savedPosMs
            else -> C.TIME_UNSET
        }

        exo.setMediaItems(episodes.map(MediaItem::fromUri), startIndex, startPosMs)
        exo.playWhenReady = true
        exo.prepare()

        if (startPosMs != C.TIME_UNSET && startPosMs > 0) {
            Toast.makeText(this, "已从上次进度继续播放", Toast.LENGTH_SHORT).show()
        }
        showLoading()   // prepare 后立即进入加载态，避免起播前黑屏无反馈
        showOverlayHint()
    }

    // 个别设备不支持按 UID 统计（返回 -1），退回全局流量；仍为 -1 则不显示网速
    private fun readRxBytes(): Long =
        TrafficStats.getUidRxBytes(Process.myUid())
            .takeIf { it >= 0 } ?: TrafficStats.getTotalRxBytes()

    private fun showLoading() {
        if (loadingBox.visibility == View.VISIBLE) return
        speedText.text = "加载中…"
        loadingBox.visibility = View.VISIBLE
        lastRxBytes = readRxBytes()
        lastRxTime = android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(speedTicker)
        handler.postDelayed(speedTicker, SPEED_INTERVAL_MS)
    }

    private fun hideLoading() {
        handler.removeCallbacks(speedTicker)
        lastRxTime = 0L
        loadingBox.visibility = View.GONE
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / 1048576.0)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    private fun showOverlayHint() {
        val exo = player ?: return
        val idx = exo.currentMediaItemIndex
        val base =
            if (episodes.size > 1) "$videoTitle  第 ${idx + 1}/${episodes.size} 集" else videoTitle
        val quality = when {
            manualQualityHeight > 0 -> "${manualQualityHeight}P"
            currentVideoHeight > 0 && qualityOptions.size > 1 -> "自动 ${currentVideoHeight}P"
            currentVideoHeight > 0 -> "${currentVideoHeight}P"
            else -> ""
        }
        val hint = if (qualityOptions.size > 1) "\n菜单键 / 长按OK 切换清晰度" else ""
        overlay.text = base + (if (quality.isNotEmpty()) "  ·  $quality" else "") + hint
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 3000)
    }

    // ---------- 清晰度选择（多码率 HLS：默认自适应，可手动锁档并记忆） ----------

    // 枚举当前媒体的视频档位；多码率源恢复上次手动锁定的清晰度
    private fun rebuildQualityOptions(tracks: Tracks) {
        val opts = mutableListOf<QualityOption>()
        tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }.forEach { group ->
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                if (format.height <= 0 && format.bitrate <= 0) continue
                opts.add(QualityOption(group, i, format.height, format.bitrate))
            }
        }
        opts.sortWith(compareByDescending<QualityOption> { it.height }.thenByDescending { it.bitrate })
        qualityOptions = opts
        // 每个媒体项的 TrackGroup 都是新对象，切集后需按记忆的高度重新锁档；
        // 记忆档位缺失（或单码率）则回到自动。重复应用相同参数是 no-op，不会循环触发。
        manualQualityHeight = 0
        if (opts.size > 1) {
            val saved = prefs.getInt(KEY_QUALITY, 0)
            if (saved > 0) {
                opts.firstOrNull { it.height == saved }?.let { applyQuality(it, save = false) }
            }
        }
        showOverlayHint()
    }

    private fun qualityLabel(opt: QualityOption): String {
        val sameHeight = qualityOptions.count { it.height == opt.height } > 1
        return when {
            opt.height > 0 && sameHeight && opt.bitrate > 0 -> "${opt.height}P (${opt.bitrate / 1000}kbps)"
            opt.height > 0 -> "${opt.height}P"
            opt.bitrate > 0 -> "${opt.bitrate / 1000}kbps"
            else -> "档位 ${opt.trackIndex + 1}"
        }
    }

    // opt 为 null 表示恢复自动（自适应）
    private fun applyQuality(opt: QualityOption?, save: Boolean = true) {
        val exo = player ?: return
        val builder = exo.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (opt != null) {
            builder.addOverride(TrackSelectionOverride(opt.group.mediaTrackGroup, opt.trackIndex))
        }
        exo.trackSelectionParameters = builder.build()
        manualQualityHeight = opt?.height ?: 0
        if (save) prefs.edit().putInt(KEY_QUALITY, manualQualityHeight).apply()
    }

    private fun openQualityDialog() {
        if (qualityOptions.size <= 1) {
            Toast.makeText(this, "当前片源只有一个清晰度", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = arrayOf("自动") + qualityOptions.map { qualityLabel(it) }.toTypedArray()
        val checked =
            if (manualQualityHeight == 0) 0
            else qualityOptions.indexOfFirst { it.height == manualQualityHeight } + 1
        AlertDialog.Builder(this)
            .setTitle("清晰度")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                applyQuality(if (which == 0) null else qualityOptions[which - 1])
                showOverlayHint()
                dialog.dismiss()
            }
            .show()
    }

    // ---------- 续播进度（SharedPreferences，按集数直链为键） ----------

    private fun savedProgress(url: String): Long {
        val raw = prefs.getString(url, null) ?: return 0
        val parts = raw.split(',')
        if (parts.size != 2) return 0
        val pos = parts[0].toLongOrNull() ?: return 0
        val dur = parts[1].toLongOrNull() ?: return 0
        return if (pos > MIN_RESUME_MS && dur - pos > NEAR_END_MS) pos else 0
    }

    private fun saveProgress() {
        val exo = player ?: return
        val idx = exo.currentMediaItemIndex
        if (idx !in episodes.indices) return
        val pos = exo.currentPosition
        val dur = exo.duration
        if (dur <= 0 || pos < 5_000) return
        val editor = prefs.edit()
        if (dur - pos < NEAR_END_MS) editor.remove(episodes[idx]) // 看完即清除续播点
        else editor.putString(episodes[idx], "$pos,$dur")
        editor.apply()
    }

    // ---------- 遥控器按键 ----------

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val exo = player ?: return super.dispatchKeyEvent(event)

        // OK 键需同时处理按下/抬起以区分短按（暂停/继续）与长按（清晰度菜单）
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (playerView.isControllerFullyVisible) {
                handler.removeCallbacks(okLongPress)   // 长按期间控制条被唤出的兜底
                return super.dispatchKeyEvent(event)   // 控制条可见时交给控制条导航
            }
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                    okLongPressFired = false
                    handler.postDelayed(okLongPress, OK_LONG_PRESS_MS)
                }
                KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(okLongPress)
                    if (!okLongPressFired) {
                        if (exo.isPlaying) exo.pause() else exo.play()
                        playerView.showController()
                    }
                }
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                openQualityDialog()
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                if (playerView.isControllerFullyVisible) {
                    playerView.hideController()
                } else {
                    saveProgress()
                    finish()
                }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (exo.isPlaying) exo.pause() else exo.play()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (exo.hasNextMediaItem()) exo.seekToNextMediaItem()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (exo.hasPreviousMediaItem()) exo.seekToPreviousMediaItem()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val target = exo.currentPosition + SEEK_STEP_MS
                exo.seekTo(if (exo.duration > 0) target.coerceAtMost(exo.duration) else target)
                return true
            }
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                exo.seekTo((exo.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                return true
            }
        }

        // 控制条隐藏时的方向键快捷操作；可见时交给控制条自身导航
        if (!playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    exo.seekTo((exo.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
                    showOverlayHint()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val target = exo.currentPosition + SEEK_STEP_MS
                    exo.seekTo(if (exo.duration > 0) target.coerceAtMost(exo.duration) else target)
                    showOverlayHint()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playerView.showController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- 生命周期 ----------

    override fun onPause() {
        super.onPause()
        saveProgress()
        player?.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(okLongPress)
        handler.removeCallbacks(speedTicker)
        if (::playerView.isInitialized) playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}

package org.veliora.television

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
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
 * - 左/右：按一下快退/快进 15 秒（连按累加）；按住转为连续扫描，倍速 8x→…→256x 递增
 *   （按片长封顶，整片最快 12 秒扫完），松手才落点。全程底部显示进度条 + 目标时间 + 倍速。
 *   OK：暂停/继续并唤出控制条
 * - 上/下 / 菜单键 / 长按 OK：唤出功能区（控制条 + 清晰度、跳过片头片尾按钮，方向键导航）
 *   菜单键再按一次收起，与返回键收起功能区一致
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
        // 正常退出时带回给 MainActivity 的观看位置（RESULT_OK），页面据此更新观看历史
        const val RESULT_EXTRA_INDEX = "index"
        const val RESULT_EXTRA_POSITION_SEC = "positionSec"
        const val RESULT_EXTRA_DURATION_SEC = "durationSec"

        private const val PREFS = "playbackProgress"
        private const val MIN_RESUME_MS = 10_000L   // 进度小于此值不续播
        private const val NEAR_END_MS = 30_000L     // 距结尾小于此值视为已看完

        // ---------- 快进/快退节奏 ----------
        // 短按一次跳 15 秒；连按累加成一次 seek；按住转为连续扫描，倍速逐档抬升。
        private const val SEEK_STEP_MS = 15_000L
        private const val SEEK_LONG_PRESS_MS = 350L    // 按住超过此时长转入连续扫描
        private const val SEEK_TICK_MS = 100L          // 扫描节拍
        private const val SEEK_COMMIT_DELAY_MS = 250L  // 连按合并窗口：停手这么久才真正 seek
        // 倍速档位与切档时刻（进入扫描后计时）：档位给得比一般手机播放器陡，
        // 电视上用户就是要「按住不放，几秒钟跨过大半集」
        // 最后一档给得很大是故意的：4.5 秒之后实际由下面的「整片扫完时长」封顶，
        // 于是不管 20 分钟的剧集还是 2 小时的电影，按住十几秒都能从头扫到尾
        private val SEEK_RATES = intArrayOf(8, 16, 32, 64, 128, 1024)
        private val SEEK_RATE_AT_MS = longArrayOf(0, 600, 1_200, 2_000, 3_000, 4_500)
        // 倍速上限 = 片长 / 这个时间：整片最快 12 秒扫完，短片也就不会一眨眼冲过片尾
        private const val SEEK_FULL_SWEEP_MS = 12_000L
        private const val SEEK_RATE_UNKNOWN_MAX = 64   // 时长未知（直播等）无法封顶，保守限速
        // 扫描硬上限：纯兜底（个别遥控器只发 DOWN 不发 UP）
        private const val SEEK_SCAN_MAX_MS = 30_000L

        // ---------- 卡死自愈 ----------
        // 采集源分片时不时会有一片下不下来（源站抽风 / 分片本身就是坏的），
        // ExoPlayer 重试几轮仍拿不到数据就一直停在缓冲态，用户看到的就是「反复转圈」。
        // 判定条件卡得很严：位置不动 + 这段时间内几乎一个字节都没收到 —— 只有真死才跳，
        // 网速慢（有数据在进来）绝不触发，否则会把慢速用户的缓冲进度反复清掉。
        private const val STALL_TIMEOUT_MS = 25_000L
        private const val STALL_MIN_BYTES = 128 * 1024L
        private const val STALL_TICK_MS = 1_000L
        private const val STALL_SKIP_MS = 10_000L
        private const val MAX_STALL_SKIPS = 3     // 每集最多跳这么多次，跳不动就不再折腾

        // ---------- 跳过片头片尾 ----------
        // 不做自动识别：采集源是边播边解的 HLS，没有跨集预分析音视频指纹的地方，
        // 误判代价还高。改由用户在播放设置里定一次，按剧名记住，同剧其余集自动套用。
        private const val KEY_SKIP_ENABLED = "__skipEnabled"
        private const val KEY_SKIP_INTRO_DEFAULT = "__skipIntroDefault"
        private const val KEY_SKIP_OUTRO_DEFAULT = "__skipOutroDefault"
        private const val PREFIX_SKIP_INTRO = "__skipIntro:"
        private const val PREFIX_SKIP_OUTRO = "__skipOutro:"
        private val SKIP_PRESETS_SEC = intArrayOf(0, 30, 60, 90, 120, 180)
        private const val SKIP_TICK_MS = 1_000L
        // 起播这么久内才算「从头开播」：续播断点若正落在片头里，那是用户上次停的地方，别抢
        private const val SKIP_START_GRACE_MS = 5_000L

        // 清晰度偏好（按分辨率高度记忆，0=自动；下划线前缀避免与进度存储的 URL 键冲突）
        private const val KEY_QUALITY = "__preferredQuality"
        private const val OK_LONG_PRESS_MS = 600L
        // 功能区按钮行离底边的高度：要抬到 Media3 控制条（时间 + 进度条 + 按钮）上方
        private const val FUNCTION_BAR_BOTTOM_DP = 120
        private const val FUNCTION_BAR_KEEP_MS = 2_000L
        private const val SPEED_INTERVAL_MS = 500L
    }

    private lateinit var playerView: PlayerView
    private lateinit var overlay: TextView
    private lateinit var loadingBox: LinearLayout
    private lateinit var speedText: TextView
    // 快进浮层：底部进度条 + 目标时间 / 总时长 / 倍速（快进时要能看见落点在整片的位置）
    private lateinit var seekBox: LinearLayout
    private lateinit var seekText: TextView
    private lateinit var seekBar: ProgressBar
    // 功能区：控制条上方那排功能按钮（控制条本身只有上一集/下一集/进度条）
    private lateinit var functionBar: LinearLayout
    private lateinit var qualityButton: TextView
    private lateinit var skipSwitchButton: TextView
    private lateinit var introButton: TextView
    private lateinit var outroButton: TextView
    private lateinit var prefs: SharedPreferences
    private var player: ExoPlayer? = null
    private var episodes: List<String> = emptyList()
    private var videoTitle = ""

    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlay = Runnable { overlay.visibility = View.GONE }
    private val hideSeekBox = Runnable { seekBox.visibility = View.GONE }

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

    // ---------- 卡死自愈状态 ----------
    private var stallSinceMs = 0L        // 本轮「位置不动」的起点，0 = 未在计时
    private var stallPositionMs = 0L
    private var stallRxAtStart = 0L
    private var stallSkips = 0
    private val stallChecker = object : Runnable {
        override fun run() {
            val exo = player
            // 暂停中不算卡：用户自己按的
            if (exo == null || exo.playbackState != Player.STATE_BUFFERING || !exo.playWhenReady) {
                stallSinceMs = 0L
                return
            }
            val now = android.os.SystemClock.elapsedRealtime()
            val pos = exo.currentPosition
            val rx = readRxBytes()
            val stalledFor = now - stallSinceMs
            val gotBytes = rx >= 0 && stallRxAtStart >= 0 && rx - stallRxAtStart >= STALL_MIN_BYTES
            if (stallSinceMs == 0L || Math.abs(pos - stallPositionMs) > 500) {
                stallSinceMs = now
                stallPositionMs = pos
                stallRxAtStart = rx
            } else if (stalledFor >= STALL_TIMEOUT_MS && !gotBytes && stallSkips < MAX_STALL_SKIPS) {
                val dur = exo.duration
                val target = pos + STALL_SKIP_MS
                if (dur <= 0 || target < dur - 1_000) {
                    stallSkips++
                    stallSinceMs = 0L
                    exo.seekTo(target)
                    Toast.makeText(
                        this@PlayerActivity,
                        "片源此处读不动，已跳过 ${STALL_SKIP_MS / 1000} 秒",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            handler.postDelayed(this, STALL_TICK_MS)
        }
    }

    // ---------- 跳过片头片尾状态 ----------
    private var skipEnabled = true
    private var skipIntroSec = 0          // 片头时长（秒），0 = 不跳
    private var skipOutroSec = 0          // 片尾时长（距结尾的秒数，各集长度不一也通用），0 = 不跳
    private var pendingIntroSkip = false  // 本集片头还没判定过（每集只判一次，用户自己倒回片头不再抢）
    private var outroHandledIndex = -1    // 已跳过片尾的集下标，防止在片尾区间里反复触发
    private val skipChecker = object : Runnable {
        override fun run() {
            checkSkipSegments()
            handler.postDelayed(this, SKIP_TICK_MS)
        }
    }

    // ---------- 清晰度 ----------
    private data class QualityOption(val group: Tracks.Group, val trackIndex: Int, val height: Int, val bitrate: Int)

    private var qualityOptions: List<QualityOption> = emptyList()
    private var manualQualityHeight = 0   // 手动锁定的分辨率高度，0=自动（自适应）
    private var currentVideoHeight = 0    // 实际在播的分辨率高度（用于浮层显示）
    private var okLongPressFired = false
    private val okLongPress = Runnable { okLongPressFired = true; playerView.showController() }

    // ---------- 快进/快退状态 ----------
    // seekTargetMs >= 0 表示正在攒一次 seek：期间只更新目标点与浮层，不真的 seek。
    // 每次按键都立刻 seek 的话，HLS 会被反复冲缓冲区（原实现连系统自动重复也照单全收，
    // 按住一秒就是十几次 seek，画面卡死且位置乱跳）。
    private var seekTargetMs = -1L
    private var seekDirection = 0        // +1 快进 / -1 快退
    private var seekScanStartMs = 0L     // 进入连续扫描的时刻，0 = 尚未进入

    // ---------- 后台/前台的断点 ----------
    // 电视上硬解码器是独占的稀缺资源：切到别的播放类 App 会把它抢走，
    // 我们若攥着不放，回前台时 surface 与解码器都已失效，只剩黑帧。
    // 所以 onStop 记下断点后彻底释放，onStart 再原地重建续播。
    private var resumeIndex = -1
    private var resumePositionMs = -1L
    private var resumePlayWhenReady = true

    private val seekLongPress = Runnable { startSeekScan() }
    private val seekCommit = Runnable { commitSeek() }
    private val seekScanTick = object : Runnable {
        override fun run() {
            // 控制条被唤出时方向键归控制条；扫描超上限也收尾，避免停不下来
            val held = android.os.SystemClock.elapsedRealtime() - seekScanStartMs
            if (seekTargetMs < 0 || playerView.isControllerFullyVisible || held > SEEK_SCAN_MAX_MS) {
                commitSeek()
                return
            }
            val rate = seekScanRate()
            advanceSeek(seekDirection * rate * SEEK_TICK_MS)
            handler.postDelayed(this, SEEK_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        episodes = intent.getStringArrayListExtra(EXTRA_EPISODES) ?: arrayListOf()
        videoTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        if (episodes.isEmpty()) {
            finish()
            return
        }
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        loadSkipSettings()

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
        // ---- 快进浮层：目标时间 + 进度条（对齐主流电视播放器：快进时进度条常驻可见）----
        seekText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
        }
        seekBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            // progress = 快进目标点（红），secondaryProgress = 当前实际播放位置（浅灰）
            progressTintList = ColorStateList.valueOf(Color.parseColor("#E50914"))
            secondaryProgressTintList = ColorStateList.valueOf(Color.parseColor("#8AFFFFFF"))
            progressBackgroundTintList = ColorStateList.valueOf(Color.parseColor("#59FFFFFF"))
        }
        seekBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 22, 36, 26)
            setBackgroundColor(0xA6000000.toInt())
            addView(seekText, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            addView(seekBar, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 18).apply { topMargin = 18 })
            visibility = View.GONE
        }

        qualityButton = barButton { openQualityDialog() }
        skipSwitchButton = barButton {
            skipEnabled = !skipEnabled
            prefs.edit().putBoolean(KEY_SKIP_ENABLED, skipEnabled).apply()
            refreshFunctionBar()
            toast(if (skipEnabled) "已开启自动跳过片头片尾" else "已关闭自动跳过片头片尾")
        }
        introButton = barButton { openSkipDialog(true) }
        outroButton = barButton { openSkipDialog(false) }
        functionBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            listOf(qualityButton, skipSwitchButton, introButton, outroButton).forEach {
                addView(it, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = 18 })
            }
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
        // 底部快进浮层：左右留出电视安全区，别贴边
        root.addView(
            seekBox,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { setMargins(70, 0, 70, 60) }
        )
        // 功能区跟控制条一起显隐：上/下、菜单键唤出，返回键收起，二者始终成套出现
        root.addView(
            functionBar,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.START
            ).apply {
                setMargins(70, 0, 70, (FUNCTION_BAR_BOTTOM_DP * resources.displayMetrics.density).toInt())
            }
        )
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                functionBar.visibility = visibility
                if (visibility == View.VISIBLE) refreshFunctionBar()
            }
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
        // 播放器不在这里创建：交给 onStart，与 onStop 的释放成对（见 resumeIndex 注释）
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
                stallSkips = 0    // 跳过次数按集算
                pendingIntroSkip = true
                showOverlayHint()
            }

            override fun onTracksChanged(tracks: Tracks) {
                rebuildQualityOptions(tracks)
                refreshFunctionBar()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0 && videoSize.height != currentVideoHeight) {
                    currentVideoHeight = videoSize.height
                    showOverlayHint()
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        showLoading()
                        startStallWatch()
                    }
                    Player.STATE_ENDED -> { saveProgress(); finish() } // 全部集数播完
                    else -> {
                        hideLoading()
                        stopStallWatch()
                    }
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

        // 后台释放前记下的断点优先（回前台要接着播），否则才是首次进入的续播逻辑
        val fromBackground = resumePositionMs >= 0
        val startIndex = (if (fromBackground) resumeIndex else intent.getIntExtra(EXTRA_INDEX, 0))
            .coerceIn(0, episodes.size - 1)
        val extraPosMs = intent.getIntExtra(EXTRA_POSITION_SEC, 0) * 1000L
        val savedPosMs = savedProgress(episodes[startIndex])
        // 本机 SharedPreferences 里的断点一定比页面历史里的新（每次 onPause 都存），优先用它；
        // 页面传来的 position 只在本机没记录时兜底（比如历史是网页播放器写的）
        val startPosMs = when {
            fromBackground -> resumePositionMs
            savedPosMs > 0 -> savedPosMs
            extraPosMs > MIN_RESUME_MS -> extraPosMs
            else -> C.TIME_UNSET
        }

        exo.setMediaItems(episodes.map(MediaItem::fromUri), startIndex, startPosMs)
        exo.playWhenReady = resumePlayWhenReady
        exo.prepare()

        if (!fromBackground && startPosMs != C.TIME_UNSET && startPosMs > 0) {
            Toast.makeText(this, "已从上次进度继续播放", Toast.LENGTH_SHORT).show()
        }
        pendingIntroSkip = true
        outroHandledIndex = -1
        handler.removeCallbacks(skipChecker)
        handler.post(skipChecker)
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

    private fun startStallWatch() {
        stallSinceMs = 0L
        handler.removeCallbacks(stallChecker)
        handler.postDelayed(stallChecker, STALL_TICK_MS)
    }

    private fun stopStallWatch() {
        handler.removeCallbacks(stallChecker)
        stallSinceMs = 0L
    }

    private fun formatSpeed(bytesPerSec: Long): String = when {
        bytesPerSec >= 1024 * 1024 -> String.format("%.1f MB/s", bytesPerSec / 1048576.0)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }

    // ---------- 快进/快退 ----------
    // 主流电视播放器（Apple TV、Android TV、以及云视听极光 / 奇异果这类国内 TV 端）
    // 共同的那套手感：
    //   1) 按一下 = 定量跳一步（10~15 秒），不是「按住才动」
    //   2) 连按 = 累加成一次 seek，界面立刻显示目标点，手停下来才真的跳
    //   3) 按住 = 连续扫描，倍速逐档抬升，松手落点
    //   4) 整个过程底部常驻进度条 + 目标时间 + 倍速，落点在整片里的位置一眼可见
    // 我们把系统的自动重复（repeatCount>0）全部丢掉，扫描完全由自己的节拍驱动，
    // 这样速度不受各家遥控器重复率影响，且整个长按只产生一次 seek。

    private fun seekScanRate(): Int {
        if (seekScanStartMs == 0L) return 0
        val held = android.os.SystemClock.elapsedRealtime() - seekScanStartMs
        var rate = SEEK_RATES[0]
        for (i in SEEK_RATES.indices) {
            if (held >= SEEK_RATE_AT_MS[i]) rate = SEEK_RATES[i]
        }
        val dur = player?.duration ?: 0
        return if (dur > 0) {
            val cap = (dur / (SEEK_FULL_SWEEP_MS / 1000)).toInt().coerceAtLeast(SEEK_RATES[0])
            rate.coerceAtMost(cap)
        } else {
            rate.coerceAtMost(SEEK_RATE_UNKNOWN_MAX)
        }
    }

    private fun onSeekKeyDown(direction: Int) {
        val exo = player ?: return
        // 遥控器带独立快进键时控制条可能正开着，两套底部 UI 会叠在一起
        if (playerView.isControllerFullyVisible) playerView.hideController()
        if (seekTargetMs < 0) seekTargetMs = exo.currentPosition
        seekDirection = direction
        handler.removeCallbacks(seekCommit)
        // 按下即给一步反馈；若继续按住，SEEK_LONG_PRESS_MS 后由扫描接管
        advanceSeek(direction * SEEK_STEP_MS)
        handler.postDelayed(seekLongPress, SEEK_LONG_PRESS_MS)
    }

    private fun onSeekKeyUp() {
        handler.removeCallbacks(seekLongPress)
        stopSeekScan()
        handler.removeCallbacks(seekCommit)
        handler.postDelayed(seekCommit, SEEK_COMMIT_DELAY_MS)
    }

    private fun startSeekScan() {
        if (seekTargetMs < 0) return
        seekScanStartMs = android.os.SystemClock.elapsedRealtime()
        handler.removeCallbacks(seekScanTick)
        handler.post(seekScanTick)
    }

    private fun stopSeekScan() {
        handler.removeCallbacks(seekScanTick)
        seekScanStartMs = 0L
    }

    private fun advanceSeek(deltaMs: Long) {
        val exo = player ?: return
        val dur = exo.duration
        var target = seekTargetMs + deltaMs
        if (target < 0) target = 0
        // 留 1 秒余量：正好落在结尾会直接触发切集
        if (dur > 0 && target > dur - 1_000) target = (dur - 1_000).coerceAtLeast(0)
        seekTargetMs = target
        showSeekOverlay()
    }

    private fun commitSeek() {
        stopSeekScan()
        handler.removeCallbacks(seekCommit)
        val exo = player
        val target = seekTargetMs
        seekTargetMs = -1L
        if (exo == null || target < 0) return
        exo.seekTo(target)
        // 落点后进度条再停留一会儿，让人看清跳到哪了
        handler.removeCallbacks(hideSeekBox)
        handler.postDelayed(hideSeekBox, 1500)
    }

    private fun showSeekOverlay() {
        val exo = player ?: return
        val dur = exo.duration
        val pos = exo.currentPosition
        val delta = seekTargetMs - pos
        val arrow = if (seekDirection >= 0) "▶▶" else "◀◀"
        val sign = if (delta >= 0) "+" else "-"
        val rate = seekScanRate()
        seekText.text = buildString {
            append(arrow).append(' ').append(formatTime(seekTargetMs))
            if (dur > 0) append(" / ").append(formatTime(dur))
            append("    ").append(sign).append(Math.abs(delta) / 1000).append(" 秒")
            if (rate > 0) append("    ").append(rate).append("x")
        }
        // 进度条：secondaryProgress 是画在 progress 底下的，所以红条取两点中较小的那个、
        // 浅色取较大的那个 —— 「当前位置 ↔ 落点」之间就总能露出一段浅色带：
        // 快进时它是红条右侧要跳过去的这段，快退时它是被放弃的这段。
        if (dur > 0) {
            val lo = Math.min(pos, seekTargetMs)
            val hi = Math.max(pos, seekTargetMs)
            seekBar.visibility = View.VISIBLE
            seekBar.progress = (lo * 1000 / dur).toInt().coerceIn(0, 1000)
            seekBar.secondaryProgress = (hi * 1000 / dur).toInt().coerceIn(0, 1000)
        } else {
            seekBar.visibility = View.GONE   // 直播/未知时长，只显示时间
        }
        seekBox.visibility = View.VISIBLE
        handler.removeCallbacks(hideSeekBox)
    }

    private fun formatTime(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 500) / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
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
        refreshFunctionBar()
    }

    // ---------- 功能区 ----------

    private fun barButton(onClick: () -> Unit): TextView {
        val focused = GradientDrawable().apply {
            cornerRadius = 14f
            setColor(Color.parseColor("#E50914"))
        }
        val normal = GradientDrawable().apply {
            cornerRadius = 14f
            setColor(0xA6000000.toInt())
        }
        return TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(30, 16, 30, 16)
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(), normal)
            }
            setOnClickListener { onClick() }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    handler.removeCallbacks(keepFunctionBar)
                    handler.post(keepFunctionBar)
                }
            }
        }
    }

    // 焦点在功能区上时不断给控制条续命，否则 4 秒超时会把整个功能区收走
    private val keepFunctionBar = object : Runnable {
        override fun run() {
            if (functionBar.visibility == View.VISIBLE && functionBar.hasFocus()) {
                playerView.showController()
                handler.postDelayed(this, FUNCTION_BAR_KEEP_MS)
            }
        }
    }

    private fun refreshFunctionBar() {
        if (!::functionBar.isInitialized) return
        qualityButton.text = "清晰度：" + when {
            qualityOptions.size <= 1 -> "仅一档"
            manualQualityHeight == 0 -> "自动"
            else -> qualityOptions.firstOrNull { it.height == manualQualityHeight }
                ?.let { qualityLabel(it) } ?: "自动"
        }
        skipSwitchButton.text = "自动跳过：" + if (skipEnabled) "开" else "关"
        introButton.text = "片头：" + skipLabel(skipIntroSec)
        outroButton.text = "片尾：" + skipLabel(skipOutroSec)
    }

    // ---------- 跳过片头片尾 ----------

    private fun loadSkipSettings() {
        skipEnabled = prefs.getBoolean(KEY_SKIP_ENABLED, true)
        // 全局默认只对多集内容生效：电影就一集，套用「片头 90 秒」会直接切掉开场
        val multi = episodes.size > 1
        skipIntroSec = prefs.getInt(PREFIX_SKIP_INTRO + videoTitle,
            if (multi) prefs.getInt(KEY_SKIP_INTRO_DEFAULT, 0) else 0)
        skipOutroSec = prefs.getInt(PREFIX_SKIP_OUTRO + videoTitle,
            if (multi) prefs.getInt(KEY_SKIP_OUTRO_DEFAULT, 0) else 0)
    }

    private fun checkSkipSegments() {
        val exo = player ?: return
        val dur = exo.duration
        if (dur <= 0) return          // 时长还没解析出来（或直播）时不判，没有参照系
        val idx = exo.currentMediaItemIndex
        val pos = exo.currentPosition

        if (pendingIntroSkip) {
            pendingIntroSkip = false
            val introMs = skipIntroSec * 1000L
            // 片头点必须落在前半段：短片被套上长片头设置时宁可不跳
            if (skipEnabled && introMs in 1 until dur / 2 && pos < SKIP_START_GRACE_MS) {
                exo.seekTo(introMs)
                toast("已跳过片头 $skipIntroSec 秒")
                return
            }
        }

        // 暂停时不切集：用户自己按停在片尾（看演职员表/彩蛋）就别抢
        val outroMs = skipOutroSec * 1000L
        if (skipEnabled && exo.playWhenReady && outroMs in 1 until dur / 2 &&
            idx != outroHandledIndex && pos >= dur - outroMs
        ) {
            outroHandledIndex = idx
            if (exo.hasNextMediaItem()) {
                if (idx in episodes.indices) prefs.edit().remove(episodes[idx]).apply() // 本集算看完
                exo.seekToNextMediaItem()
                toast("已跳过片尾，播放下一集")
            } else {
                // 最后一集：走到结尾，交给 STATE_ENDED 的既有流程收尾（清断点 + 退出）
                exo.seekTo((dur - 500).coerceAtLeast(0))
                toast("已跳过片尾")
            }
        }
    }

    private fun skipLabel(sec: Int) = if (sec <= 0) "不跳过" else "$sec 秒"

    // intro=true 设片头（从头算起），false 设片尾（距结尾算起）
    private fun openSkipDialog(intro: Boolean) {
        val current = if (intro) skipIntroSec else skipOutroSec
        // 「用当前进度设定」：正片刚开始（或片尾刚起）时按一下，比凭空猜秒数准
        val exo = player
        val dur = exo?.duration ?: 0L
        val pos = exo?.currentPosition ?: 0L
        val fromNowSec =
            if (dur <= 0 || pos <= 0) null
            else (((if (intro) pos else dur - pos) / 1000).toInt())
                .takeIf { it in 1 until (dur / 2000).toInt() }

        val presets = SKIP_PRESETS_SEC.toMutableList()
        if (current > 0 && current !in presets) presets.add(current)
        presets.sort()
        val labels = presets.map { skipLabel(it) }.toMutableList()
        if (fromNowSec != null) labels.add("用当前进度设定（$fromNowSec 秒）")
        AlertDialog.Builder(this)
            .setTitle(if (intro) "跳过片头" else "跳过片尾")
            .setSingleChoiceItems(labels.toTypedArray(), presets.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                applySkip(intro, if (which < presets.size) presets[which] else (fromNowSec ?: 0))
            }
            .setOnDismissListener { playerView.showController() }
            .show()
    }

    private fun applySkip(intro: Boolean, sec: Int) {
        val key = (if (intro) PREFIX_SKIP_INTRO else PREFIX_SKIP_OUTRO) + videoTitle
        val defKey = if (intro) KEY_SKIP_INTRO_DEFAULT else KEY_SKIP_OUTRO_DEFAULT
        // 本剧记住，同时作为「新剧集默认」：换一部多集内容直接沿用，不用每部重设
        val editor = prefs.edit().putInt(key, sec).putInt(defKey, sec)
        if (intro) skipIntroSec = sec else skipOutroSec = sec
        outroHandledIndex = -1
        if (sec > 0 && !skipEnabled) {   // 关着总开关又来设秒数，显然是想用
            skipEnabled = true
            editor.putBoolean(KEY_SKIP_ENABLED, true)
        }
        editor.apply()
        refreshFunctionBar()
        val what = if (intro) "片头" else "片尾"
        toast(if (sec <= 0) "已关闭跳过$what" else "已设为跳过$what $sec 秒，本剧其余集自动套用")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
            .setOnDismissListener { playerView.showController() }
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
        if (dur <= 0) return
        // 每次存断点顺带把结果更新好，finish() 时（返回键/全部播完）自然带回 MainActivity
        setResult(RESULT_OK, Intent().apply {
            putExtra(RESULT_EXTRA_INDEX, idx)
            putExtra(RESULT_EXTRA_POSITION_SEC, (pos / 1000).toInt())
            putExtra(RESULT_EXTRA_DURATION_SEC, (dur / 1000).toInt())
        })
        if (pos < 5_000) return
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

        // 快进/快退键要同时吃 DOWN 与 UP（短按累加、长按扫描、松手落点），
        // 所以放在下面 ACTION_DOWN 早退之前。方向键仅在控制条隐藏时接管，媒体键始终接管。
        val seekDir = when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> 1
            KeyEvent.KEYCODE_MEDIA_REWIND -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (playerView.isControllerFullyVisible) 0 else 1
            KeyEvent.KEYCODE_DPAD_LEFT -> if (playerView.isControllerFullyVisible) 0 else -1
            else -> 0
        }
        if (seekDir != 0) {
            when (event.action) {
                // repeatCount > 0 是系统自动重复，一律丢弃：扫描由 seekScanTick 自己定速
                KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) onSeekKeyDown(seekDir)
                KeyEvent.ACTION_UP -> onSeekKeyUp()
            }
            return true
        }

        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)

        when (event.keyCode) {
            // 菜单键唤出功能区，再按一次收起（与返回键收起功能区一致）
            KeyEvent.KEYCODE_MENU -> {
                if (playerView.isControllerFullyVisible) playerView.hideController()
                else playerView.showController()
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
        }

        // 控制条隐藏时上/下唤出控制条；可见时交给控制条自身导航
        if (!playerView.isControllerFullyVisible) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    playerView.showController()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- 生命周期 ----------

    override fun onStart() {
        super.onStart()
        // episodes 为空时 onCreate 已 finish()，playerView 也没建起来，不能碰
        if (player == null && episodes.isNotEmpty()) initPlayer()
    }

    override fun onResume() {
        super.onResume()
        // 后台前是暂停态的话，回来先把控制条亮出来 —— 否则画面停在一帧，看着像卡死
        if (player != null && !resumePlayWhenReady) playerView.showController()
    }

    override fun onPause() {
        super.onPause()
        commitSeek()      // 攒着没提交的快进要先落点，否则存的是旧进度
        saveProgress()
        val exo = player ?: return
        resumePlayWhenReady = exo.playWhenReady
        exo.pause()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()   // 记下断点后彻底放手解码器，回前台由 onStart 重建
    }

    override fun onDestroy() {
        cancelPending()
        releasePlayer()
        super.onDestroy()
    }

    private fun releasePlayer() {
        val exo = player ?: return
        resumeIndex = exo.currentMediaItemIndex
        resumePositionMs = exo.currentPosition.coerceAtLeast(0)
        cancelPending()
        hideLoading()
        if (::playerView.isInitialized) playerView.player = null
        player = null     // 先摘掉引用，release() 触发的回调就不会再碰这个实例
        exo.release()
    }

    private fun cancelPending() {
        handler.removeCallbacks(hideOverlay)
        handler.removeCallbacks(okLongPress)
        handler.removeCallbacks(speedTicker)
        handler.removeCallbacks(seekLongPress)
        handler.removeCallbacks(seekCommit)
        handler.removeCallbacks(seekScanTick)
        handler.removeCallbacks(hideSeekBox)
        handler.removeCallbacks(stallChecker)
        handler.removeCallbacks(skipChecker)
        handler.removeCallbacks(keepFunctionBar)
    }
}

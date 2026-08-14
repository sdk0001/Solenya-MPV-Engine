package com.solenya.engine.mpv

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.DeadObjectException
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.solenya.external.IExternalPlayerCallback
import com.solenya.external.IExternalPlayerService
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.Utils
import java.net.URLDecoder
import kotlin.math.abs
import kotlin.math.roundToInt

private const val TRACK_TYPE_AUDIO = 1
private const val TRACK_TYPE_TEXT = 3
private const val TAG = "MpvEngineService"
private const val MPV_ENGINE_API_VERSION = 1
private const val MPV_ENGINE_VERSION = "1.0.0"
private const val STARTUP_TIMEOUT_MS = 15_000L
private const val PLAYBACK_STATS_INTERVAL_MS = 10_000L
private const val DEFAULT_USER_AGENT = "IPTVSmarters/1.0.0 (Linux;Android 11) ExoPlayerLib/2.18.1"
private const val ANDROID_HWDEC_CODECS = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"
private const val ANDROID_HWDEC_CHAIN = "mediacodec,mediacodec-copy"
private const val AUDIO_PASSTHROUGH_CODECS = "ac3,eac3,dts,dts-hd,truehd"
private const val LIVE_FORWARD_CACHE_BYTES = 32L * 1024L * 1024L
private const val LIVE_BACK_CACHE_BYTES = 4L * 1024L * 1024L
private const val VOD_FORWARD_CACHE_BYTES = 64L * 1024L * 1024L
private const val VOD_BACK_CACHE_BYTES = 32L * 1024L * 1024L

private data class StreamRequest(
    val url: String,
    val headers: Map<String, String>
)

class MpvEngineService : Service(), MPVLib.EventObserver {
    private val mainHandler = Handler(Looper.getMainLooper())

    private var callback: IExternalPlayerCallback? = null
    private var surface: Surface? = null
    private var nativeSurface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var displayRefreshRate = 0f
    private var lastAppliedSurfaceSize: String? = null
    private var lastAppliedDisplayRefreshRate = 0f

    private var currentUrl: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentOptions: Bundle = Bundle.EMPTY
    private var pendingStartPositionMs = 0L
    private var currentPositionMs = 0L
    private var durationMs = 0L

    private var initialized = false
    private var surfaceAttached = false
    private var loadPendingForSurface = false
    private var loadCommandInFlight = false
    private var nativeFileActive = false
    private var playing = false
    private var buffering = false
    private var released = false
    private var firstFrameRendered = false
    private var playbackGeneration = 0
    private var activeNativeGeneration = -1
    private var replacementEndEventsToIgnore = 0

    private var lastVideoFormatRefreshMs = 0L
    private var lastPlaybackStatsMs = 0L
    private var lastDecoderState: String? = null
    private var lastNotifiedContentFrameRate = 0f

    private val progressTicker = object : Runnable {
        override fun run() {
            if (!released && initialized) {
                runCatching {
                    MPVLib.getPropertyDouble("time-pos")?.let {
                        currentPositionMs = (it * 1000.0).toLong().coerceAtLeast(0L)
                    }
                    MPVLib.getPropertyDouble("duration")?.let {
                        durationMs = (it * 1000.0).toLong().coerceAtLeast(0L)
                    }
                    pushPosition()
                    logPlaybackStatsIfDue()
                }.onFailure { Log.w(TAG, "Unable to refresh MPV playback state", it) }
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private val binder = object : IExternalPlayerService.Stub() {
        override fun getApiVersion(): Int = MPV_ENGINE_API_VERSION

        override fun getEngineVersion(): String = MPV_ENGINE_VERSION

        override fun setCallback(callback: IExternalPlayerCallback?) {
            runOnMain {
                this@MpvEngineService.callback = callback
                pushState()
            }
        }

        override fun attachSurface(surface: Surface?) {
            runOnMain {
                if (surface == null || !surface.isValid) return@runOnMain
                if (this@MpvEngineService.surface !== surface) {
                    detachNativeSurface()
                    this@MpvEngineService.surface = surface
                }
                val attached = attachSurfaceIfReady()
                if (attached && loadPendingForSurface && currentUrl != null) {
                    startCurrentPlayback()
                } else if (attached && nativeFileActive && !firstFrameRendered) {
                    scheduleStartupWatchdog()
                }
            }
        }

        override fun clearSurface() {
            runOnMain {
                cancelStartupWatchdog()
                detachNativeSurface()
                surface = null
            }
        }

        override fun updateSurfaceMetrics(width: Int, height: Int, displayRefreshRate: Float) {
            runOnMain {
                surfaceWidth = width.coerceAtLeast(0)
                surfaceHeight = height.coerceAtLeast(0)
                this@MpvEngineService.displayRefreshRate = displayRefreshRate
                    .takeIf { it.isFinite() && it in 1f..240f }
                    ?: 0f
                applySurfaceMetrics()
            }
        }

        override fun play(url: String?, options: Bundle?, startPositionMs: Long) {
            runOnMain {
                playInternal(url, options ?: Bundle.EMPTY, startPositionMs)
            }
        }

        override fun retry() {
            runOnMain {
                val url = currentUrl ?: return@runOnMain
                playInternal(url, currentOptions, currentPositionMs)
            }
        }

        override fun pause() {
            runOnMain {
                if (!initialized) return@runOnMain
                runCatching { MPVLib.setPropertyBoolean("pause", true) }
                playing = false
                notifyCallback("onPlayingChanged") { it.onPlayingChanged(false) }
            }
        }

        override fun resume() {
            runOnMain {
                if (!initialized) return@runOnMain
                runCatching { MPVLib.setPropertyBoolean("pause", false) }
                if (firstFrameRendered) {
                    playing = true
                    notifyCallback("onPlayingChanged") { it.onPlayingChanged(true) }
                }
            }
        }

        override fun togglePlayPause() {
            runOnMain { if (playing) pause() else resume() }
        }

        override fun stop() {
            runOnMain { stopPlayback() }
        }

        override fun release() {
            runOnMain { releaseMpv() }
        }

        override fun seekTo(positionMs: Long) {
            runOnMain { seekToInternal(positionMs) }
        }

        override fun seekRelative(seconds: Int) {
            runOnMain { seekToInternal(currentPositionMs + seconds * 1000L) }
        }

        override fun selectTrack(trackType: Int, trackId: Int) {
            runOnMain {
                if (!initialized) return@runOnMain
                when (trackType) {
                    TRACK_TYPE_AUDIO -> runCatching { MPVLib.setPropertyInt("aid", trackId) }
                    TRACK_TYPE_TEXT -> runCatching { MPVLib.setPropertyInt("sid", trackId) }
                }
            }
        }

        override fun disableSubtitles() {
            runOnMain {
                if (initialized) runCatching { MPVLib.setPropertyString("sid", "no") }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        releaseMpv()
        super.onDestroy()
    }

    private fun playInternal(rawUrl: String?, options: Bundle, startPositionMs: Long) {
        if (rawUrl.isNullOrBlank()) {
            notifyCallback("onError") { it.onError("invalid_url", "Missing playback URL") }
            return
        }

        val request = parseStreamRequest(rawUrl, options)
        if (request.url.isBlank()) {
            notifyCallback("onError") { it.onError("invalid_url", "Missing playback URL") }
            return
        }

        cancelStartupWatchdog()
        if (nativeFileActive || loadCommandInFlight) {
            replacementEndEventsToIgnore = (replacementEndEventsToIgnore + 1).coerceAtMost(4)
        }

        playbackGeneration += 1
        activeNativeGeneration = -1
        currentUrl = request.url
        currentHeaders = request.headers
        currentOptions = Bundle(options)
        pendingStartPositionMs = startPositionMs.coerceAtLeast(0L)
        currentPositionMs = pendingStartPositionMs
        durationMs = 0L
        firstFrameRendered = false
        playing = false
        loadPendingForSurface = true
        lastVideoFormatRefreshMs = 0L
        lastPlaybackStatsMs = 0L
        lastDecoderState = null
        lastNotifiedContentFrameRate = 0f

        notifyCallback("onContentFrameRateChanged") { it.onContentFrameRateChanged(0f) }
        notifyCallback("onPlayingChanged") { it.onPlayingChanged(false) }
        setBuffering(true)

        runCatching {
            ensureInitialized()
            applyPlaybackOptions(currentOptions)
            startCurrentPlayback()
        }.onFailure { error ->
            Log.e(TAG, "Unable to start MPV", error)
            loadPendingForSurface = false
            setBuffering(false)
            notifyCallback("onError") {
                it.onError("mpv_load_failed", error.message ?: "MPV load failed")
            }
        }
    }

    private fun ensureInitialized() {
        if (initialized) return
        released = false
        runCatching { Utils.copyAssets(this) }
            .onFailure { Log.w(TAG, "Unable to prepare MPV assets", it) }

        MPVLib.create(applicationContext)
        setOptionChecked("config", "yes")
        setOptionChecked("config-dir", filesDir.path)
        setOptionChecked("gpu-shader-cache-dir", cacheDir.path)
        setOptionChecked("icc-cache-dir", cacheDir.path)
        applyBaseOptions()
        MPVLib.init()

        MPVLib.setPropertyBoolean("pause", false)
        observeProperties()
        MPVLib.addObserver(this)

        initialized = true
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
        applySurfaceMetrics()
    }

    private fun applyBaseOptions() {
        setOptionChecked("profile", "fast")
        setOptionChecked("vo", "gpu")
        setOptionChecked("gpu-context", "android")
        setOptionChecked("opengl-es", "yes")
        setOptionChecked("hwdec", ANDROID_HWDEC_CHAIN)
        setOptionChecked("hwdec-codecs", ANDROID_HWDEC_CODECS)
        setOptionChecked("ao", "audiotrack,opensles")
        setOptionChecked("audio-set-media-role", "yes")
        setOptionChecked("audio-channels", "auto-safe")
        setOptionChecked("user-agent", DEFAULT_USER_AGENT)
        setOptionChecked("network-timeout", "15")
        setOptionChecked("demuxer-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2")
        setOptionChecked("ytdl", "no")
        setOptionChecked("tls-verify", "yes")
        setOptionChecked("tls-ca-file", "${filesDir.path}/cacert.pem")
        setOptionChecked("audio-file-auto", "no")
        setOptionChecked("input-default-bindings", "no")
        setOptionChecked("vd-lavc-film-grain", "cpu")
        setOptionChecked("save-position-on-quit", "no")
        setOptionChecked("force-window", "no")
        setOptionChecked("idle", "once")
        if (displayRefreshRate > 0f) {
            setOptionChecked("display-fps-override", displayRefreshRate.toString())
            lastAppliedDisplayRefreshRate = displayRefreshRate
        }
    }

    private fun applyPlaybackOptions(options: Bundle) {
        val requestedBufferSeconds = options.getInt("bufferSeconds", 10).coerceIn(1, 30)
        val isLive = options.getBoolean("isLive", false)
        val softwareDecoder = options.getString("videoDecoder").equals("SOFTWARE", ignoreCase = true)

        setRuntimeProperty("hwdec", if (softwareDecoder) "no" else ANDROID_HWDEC_CHAIN)
        setRuntimeProperty("cache", "yes")
        setRuntimeProperty("cache-secs", requestedBufferSeconds.toString())
        setRuntimeProperty("cache-pause", "yes")
        setRuntimeProperty("cache-pause-initial", "yes")
        setRuntimeProperty("demuxer-cache-wait", "no")
        setRuntimeProperty("video-sync", "audio")
        setRuntimeProperty("autosync", "0")
        setRuntimeProperty("interpolation", "no")
        setRuntimeProperty("vd-lavc-fast", "no")
        setRuntimeProperty("vd-lavc-show-all", "no")
        setRuntimeProperty("deinterlace", if (options.getBoolean("deinterlacing", true)) "yes" else "no")
        setRuntimeProperty("sub-auto", if (options.getBoolean("autoSubtitles", false)) "fuzzy" else "no")

        if (isLive) {
            val initialBufferSeconds = if (requestedBufferSeconds <= 1) 0.5 else 2.0
            setRuntimeProperty("cache-pause-wait", initialBufferSeconds.toString())
            setRuntimeProperty("demuxer-readahead-secs", requestedBufferSeconds.coerceAtMost(3).toString())
            setRuntimeProperty("demuxer-max-bytes", LIVE_FORWARD_CACHE_BYTES.toString())
            setRuntimeProperty("demuxer-max-back-bytes", LIVE_BACK_CACHE_BYTES.toString())
            setRuntimeProperty("hr-seek", "no")
        } else {
            val initialBufferSeconds = requestedBufferSeconds.coerceAtMost(3)
            setRuntimeProperty("cache-pause-wait", initialBufferSeconds.toString())
            setRuntimeProperty("demuxer-readahead-secs", requestedBufferSeconds.coerceAtMost(10).toString())
            setRuntimeProperty("demuxer-max-bytes", VOD_FORWARD_CACHE_BYTES.toString())
            setRuntimeProperty("demuxer-max-back-bytes", VOD_BACK_CACHE_BYTES.toString())
            setRuntimeProperty("hr-seek", "yes")
        }

        applyStreamHeaders(currentHeaders)
        applyAudioOptions(options)
        Log.d(
            TAG,
            "Applied MPV profile type=${if (isLive) "live" else "vod"} " +
                "buffer=${requestedBufferSeconds}s decoder=${if (softwareDecoder) "software" else "hardware"}"
        )
    }

    private fun applyAudioOptions(options: Bundle) {
        if (options.getBoolean("audioPassthrough", false)) {
            setRuntimeProperty("audio-spdif", AUDIO_PASSTHROUGH_CODECS)
            setRuntimeProperty("audio-channels", "auto")
        } else {
            setRuntimeProperty("audio-spdif", "")
            setRuntimeProperty("audio-channels", "auto-safe")
        }
    }

    private fun startCurrentPlayback() {
        val url = currentUrl ?: return
        if (!attachSurfaceIfReady()) {
            loadPendingForSurface = true
            Log.d(TAG, "Deferring MPV load until a valid Surface is attached")
            return
        }

        loadPendingForSurface = false
        loadCommandInFlight = true
        Log.d(TAG, "Starting deterministic MPV load generation=$playbackGeneration")
        MPVLib.command("loadfile", url, "replace")
        scheduleStartupWatchdog()
    }

    private fun attachSurfaceIfReady(): Boolean {
        val requestedSurface = surface ?: return false
        if (!initialized || !requestedSurface.isValid) return false
        if (surfaceAttached && nativeSurface === requestedSurface) {
            applySurfaceMetrics()
            return true
        }

        if (surfaceAttached) detachNativeSurface()
        return runCatching {
            MPVLib.attachSurface(requestedSurface)
            nativeSurface = requestedSurface
            surfaceAttached = true
            setRuntimeProperty("force-window", "yes")
            MPVLib.setPropertyString("vo", "gpu")
            applySurfaceMetrics()
            true
        }.onFailure { Log.e(TAG, "Unable to attach MPV Surface", it) }
            .getOrDefault(false)
    }

    private fun detachNativeSurface() {
        if (!surfaceAttached) {
            nativeSurface = null
            return
        }

        if (initialized) {
            // mpv-android requires the VO to stop using the Surface before the
            // JNI global reference is released by detachSurface().
            runCatching { MPVLib.setPropertyString("vo", "null") }
            runCatching { MPVLib.setPropertyString("force-window", "no") }
            runCatching { MPVLib.detachSurface() }
                .onFailure { Log.w(TAG, "Unable to detach MPV Surface", it) }
        }
        surfaceAttached = false
        nativeSurface = null
        lastAppliedSurfaceSize = null
    }

    private fun applySurfaceMetrics() {
        if (!initialized) return
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            val size = "${surfaceWidth}x$surfaceHeight"
            if (size != lastAppliedSurfaceSize) {
                runCatching { MPVLib.setPropertyString("android-surface-size", size) }
                    .onFailure { Log.w(TAG, "Unable to update MPV Surface size", it) }
                lastAppliedSurfaceSize = size
            }
        }
        if (abs(displayRefreshRate - lastAppliedDisplayRefreshRate) >= 0.01f) {
            setRuntimeProperty("display-fps-override", displayRefreshRate.toString())
            lastAppliedDisplayRefreshRate = displayRefreshRate
        }
    }

    private fun scheduleStartupWatchdog() {
        cancelStartupWatchdog()
        val generation = playbackGeneration
        mainHandler.postAtTime({
            if (
                generation == playbackGeneration &&
                initialized &&
                surfaceAttached &&
                currentUrl != null &&
                !firstFrameRendered
            ) {
                Log.w(TAG, "MPV startup timed out generation=$generation; no automatic decoder mutation applied")
                setBuffering(false)
                notifyCallback("onError") {
                    it.onError("mpv_start_timeout", "MPV did not render a frame in time")
                }
            }
        }, STARTUP_WATCHDOG_TOKEN, SystemClock.uptimeMillis() + STARTUP_TIMEOUT_MS)
    }

    private fun cancelStartupWatchdog() {
        mainHandler.removeCallbacksAndMessages(STARTUP_WATCHDOG_TOKEN)
    }

    private fun stopPlayback() {
        playbackGeneration += 1
        activeNativeGeneration = -1
        cancelStartupWatchdog()
        if (initialized && (nativeFileActive || loadCommandInFlight)) {
            replacementEndEventsToIgnore = (replacementEndEventsToIgnore + 1).coerceAtMost(4)
            runCatching { MPVLib.command("stop") }
        }
        nativeFileActive = false
        loadCommandInFlight = false
        loadPendingForSurface = false
        currentUrl = null
        currentHeaders = emptyMap()
        currentOptions = Bundle.EMPTY
        pendingStartPositionMs = 0L
        currentPositionMs = 0L
        durationMs = 0L
        firstFrameRendered = false
        playing = false
        lastNotifiedContentFrameRate = 0f
        setBuffering(false)
        notifyCallback("onContentFrameRateChanged") { it.onContentFrameRateChanged(0f) }
        notifyCallback("onPlayingChanged") { it.onPlayingChanged(false) }
        pushPosition()
    }

    private fun releaseMpv() {
        if (released && !initialized) return
        released = true
        playbackGeneration += 1
        activeNativeGeneration = -1
        cancelStartupWatchdog()
        mainHandler.removeCallbacks(progressTicker)

        if (initialized) {
            runCatching { MPVLib.command("stop") }
            detachNativeSurface()
            runCatching { MPVLib.removeObserver(this) }
            runCatching { MPVLib.destroy() }
                .onFailure { Log.w(TAG, "Unable to destroy MPV", it) }
        }

        initialized = false
        surfaceAttached = false
        nativeSurface = null
        surface = null
        currentUrl = null
        currentHeaders = emptyMap()
        currentOptions = Bundle.EMPTY
        nativeFileActive = false
        loadCommandInFlight = false
        loadPendingForSurface = false
        replacementEndEventsToIgnore = 0
        firstFrameRendered = false
        playing = false
        buffering = false
        callback = null
    }

    private fun seekToInternal(positionMs: Long) {
        if (!initialized) return
        val safePosition = positionMs.coerceAtLeast(0L)
        currentPositionMs = safePosition
        runCatching { MPVLib.setPropertyDouble("time-pos", safePosition / 1000.0) }
        pushPosition()
    }

    private fun applyPendingStartPosition() {
        val start = pendingStartPositionMs
        if (start <= 0L) return
        pendingStartPositionMs = 0L
        seekToInternal(start)
    }

    private fun parseStreamRequest(rawUrl: String, options: Bundle): StreamRequest {
        val headers = linkedMapOf<String, String>()
        var playbackUrl = rawUrl.trim()
        val separatorIndex = playbackUrl.indexOf('|')
        if (separatorIndex >= 0) {
            val suffix = playbackUrl.substring(separatorIndex + 1)
            if (suffix.contains('=') || suffix.contains(':')) {
                playbackUrl = playbackUrl.substring(0, separatorIndex).trim()
                parseHeaderString(suffix, headers)
            }
        }
        addBundleHeaders(options, headers)
        return StreamRequest(playbackUrl, headers)
    }

    @Suppress("DEPRECATION")
    private fun addBundleHeaders(options: Bundle, headers: MutableMap<String, String>) {
        listOf("requestHeaders", "headers", "httpHeaders").forEach { key ->
            when (val value = options.get(key)) {
                is Bundle -> value.keySet().forEach { headerName ->
                    putHeader(headers, headerName, value.get(headerName)?.toString())
                }
                is Array<*> -> value.forEach { parseHeaderString(it?.toString().orEmpty(), headers) }
                is ArrayList<*> -> value.forEach { parseHeaderString(it?.toString().orEmpty(), headers) }
                is String -> parseHeaderString(value, headers)
            }
        }

        mapOf(
            "userAgent" to "User-Agent",
            "user-agent" to "User-Agent",
            "User-Agent" to "User-Agent",
            "httpUserAgent" to "User-Agent",
            "referer" to "Referer",
            "referrer" to "Referer",
            "Referer" to "Referer",
            "httpReferer" to "Referer",
            "origin" to "Origin",
            "Origin" to "Origin",
            "cookie" to "Cookie",
            "Cookie" to "Cookie",
            "httpCookie" to "Cookie",
            "authorization" to "Authorization",
            "Authorization" to "Authorization",
            "httpAuthorization" to "Authorization"
        ).forEach { (optionKey, headerName) ->
            putHeader(headers, headerName, options.getString(optionKey))
        }
    }

    private fun parseHeaderString(value: String, headers: MutableMap<String, String>) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        val parts = if (trimmed.contains('\n')) trimmed.lineSequence().toList() else trimmed.split('&')
        parts.forEach { part ->
            val token = part.trim().trimStart('?')
            if (token.isBlank()) return@forEach
            val separatorIndex = listOf(token.indexOf('='), token.indexOf(':'))
                .filter { it >= 0 }
                .minOrNull() ?: return@forEach
            val name = decodeHeaderToken(token.substring(0, separatorIndex).trim())
            val headerValue = decodeHeaderToken(token.substring(separatorIndex + 1).trim())
            putHeader(headers, name, headerValue)
        }
    }

    private fun putHeader(headers: MutableMap<String, String>, name: String?, value: String?) {
        val headerName = canonicalHeaderName(name?.trim().orEmpty())
        val headerValue = value?.trim().orEmpty()
        if (
            headerName.isBlank() ||
            headerValue.isBlank() ||
            headerName.any { it <= ' ' || it == ':' } ||
            headerValue.any { it == '\r' || it == '\n' }
        ) return
        headers.keys.firstOrNull { it.equals(headerName, ignoreCase = true) }
            ?.let { existingName -> headers.remove(existingName) }
        headers[headerName] = headerValue
    }

    private fun applyStreamHeaders(headers: Map<String, String>) {
        val userAgent = headerValue(headers, "User-Agent") ?: DEFAULT_USER_AGENT
        val referer = headerValue(headers, "Referer") ?: headerValue(headers, "Referrer").orEmpty()
        val headerFields = headers
            .filterKeys {
                !it.equals("User-Agent", ignoreCase = true) &&
                    !it.equals("Referer", ignoreCase = true) &&
                    !it.equals("Referrer", ignoreCase = true)
            }
            .map { (name, value) -> escapeMpvListValue("$name: $value") }

        setRuntimeProperty("user-agent", userAgent)
        setRuntimeProperty("referrer", referer)
        setRuntimeProperty("http-header-fields", headerFields.joinToString(","))
        Log.d(TAG, "Applied ${headers.size} HTTP request header(s); values omitted")
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }
            ?.value
            ?.takeIf { it.isNotBlank() }

    private fun canonicalHeaderName(name: String): String = when {
        name.equals("user-agent", ignoreCase = true) || name.equals("userAgent", ignoreCase = true) -> "User-Agent"
        name.equals("referer", ignoreCase = true) || name.equals("referrer", ignoreCase = true) -> "Referer"
        name.equals("origin", ignoreCase = true) -> "Origin"
        name.equals("cookie", ignoreCase = true) -> "Cookie"
        name.equals("authorization", ignoreCase = true) -> "Authorization"
        else -> name
    }

    private fun decodeHeaderToken(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    private fun escapeMpvListValue(value: String): String =
        value.replace("\\", "\\\\").replace(",", "\\,")

    private fun observeProperties() {
        mapOf(
            "time-pos" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "duration" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "pause" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "paused-for-cache" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "eof-reached" to MPVLib.MpvFormat.MPV_FORMAT_FLAG,
            "track-list" to MPVLib.MpvFormat.MPV_FORMAT_NONE,
            "video-params/w" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "video-params/h" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "video-params/gamma" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "video-params/primaries" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "estimated-vf-fps" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "container-fps" to MPVLib.MpvFormat.MPV_FORMAT_DOUBLE,
            "video-format" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "video-codec" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "hwdec-current" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "audio-codec" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "audio-codec-name" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "audio-params/channel-count" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "audio-params/hr-channels" to MPVLib.MpvFormat.MPV_FORMAT_STRING
        ).forEach { (name, format) -> MPVLib.observeProperty(name, format) }
    }

    private fun setOptionChecked(name: String, value: String) {
        val result = MPVLib.setOptionString(name, value)
        if (result < 0) Log.w(TAG, "MPV rejected option $name code=$result")
    }

    private fun setRuntimeProperty(name: String, value: String) {
        runCatching { MPVLib.setPropertyString(name, value) }
            .onFailure { Log.w(TAG, "MPV rejected runtime property $name", it) }
    }

    private fun notifyCallback(eventName: String, block: (IExternalPlayerCallback) -> Unit) {
        val target = callback ?: return
        runCatching { block(target) }
            .onFailure { error ->
                if (error is DeadObjectException || error is RemoteException) {
                    Log.w(TAG, "Dropping callback after client disconnect event=$eventName")
                    if (callback === target) callback = null
                } else {
                    Log.w(TAG, "Callback failed event=$eventName", error)
                }
            }
    }

    private fun setBuffering(value: Boolean) {
        if (buffering == value) return
        buffering = value
        notifyCallback("onBuffering") { it.onBuffering(value) }
    }

    private fun pushState() {
        notifyCallback("onPlayingChanged") { it.onPlayingChanged(playing) }
        notifyCallback("onBuffering") { it.onBuffering(buffering) }
        notifyCallback("onContentFrameRateChanged") { it.onContentFrameRateChanged(lastNotifiedContentFrameRate) }
        pushPosition()
        updateVideoFormat(force = true)
    }

    private fun pushPosition() {
        notifyCallback("onPositionChanged") { it.onPositionChanged(currentPositionMs, durationMs) }
    }

    private fun logDecoderState(reason: String) {
        if (!initialized) return
        val hwdec = MPVLib.getPropertyString("hwdec-current").orEmpty().ifBlank { "unknown" }
        val videoCodec = MPVLib.getPropertyString("video-codec").orEmpty().ifBlank { "unknown" }
        val audioCodec = firstNonBlank(
            MPVLib.getPropertyString("audio-codec"),
            MPVLib.getPropertyString("audio-codec-name")
        ).orEmpty().ifBlank { "unknown" }
        val audioChannels = firstNonBlank(
            MPVLib.getPropertyString("audio-params/hr-channels"),
            MPVLib.getPropertyInt("audio-params/channel-count")?.toString()
        ).orEmpty().ifBlank { "unknown" }
        val state = "$reason|hwdec=$hwdec|video=$videoCodec|audio=$audioCodec|channels=$audioChannels"
        if (state != lastDecoderState) {
            lastDecoderState = state
            Log.i(TAG, "MPV decoder state $state")
        }
    }

    private fun logPlaybackStatsIfDue() {
        if (!firstFrameRendered) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPlaybackStatsMs < PLAYBACK_STATS_INTERVAL_MS) return
        lastPlaybackStatsMs = now
        val decoderDrops = MPVLib.getPropertyInt("decoder-frame-drop-count") ?: 0
        val voDrops = MPVLib.getPropertyInt("frame-drop-count") ?: 0
        val mistimed = MPVLib.getPropertyInt("mistimed-frame-count") ?: 0
        val avSync = MPVLib.getPropertyDouble("avsync") ?: 0.0
        val cacheDuration = MPVLib.getPropertyDouble("demuxer-cache-duration") ?: 0.0
        Log.d(
            TAG,
            "MPV stats decoderDrops=$decoderDrops voDrops=$voDrops mistimed=$mistimed " +
                "avsync=${"%.3f".format(avSync)} cache=${"%.1f".format(cacheDuration)}s"
        )
    }

    private fun markFirstFrameRendered() {
        if (!surfaceAttached || surface?.isValid != true) return
        cancelStartupWatchdog()
        if (!firstFrameRendered) Log.i(TAG, "MPV playback ready generation=$playbackGeneration")
        firstFrameRendered = true
        playing = MPVLib.getPropertyBoolean("pause") != true
        setBuffering(false)
        notifyCallback("onReady") { it.onReady() }
        notifyCallback("onPlayingChanged") { it.onPlayingChanged(playing) }
    }

    private fun updateVideoFormat(force: Boolean = false) {
        if (!initialized) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastVideoFormatRefreshMs < 500L) return
        lastVideoFormatRefreshMs = now

        val exactFrameRate = normalizeFrameRate(MPVLib.getPropertyDouble("estimated-vf-fps"))
            .takeIf { it > 0f }
            ?: normalizeFrameRate(MPVLib.getPropertyDouble("container-fps"))
        if (abs(exactFrameRate - lastNotifiedContentFrameRate) >= 0.01f) {
            lastNotifiedContentFrameRate = exactFrameRate
            notifyCallback("onContentFrameRateChanged") { it.onContentFrameRateChanged(exactFrameRate) }
        }

        val width = MPVLib.getPropertyInt("video-params/w") ?: MPVLib.getPropertyInt("width") ?: 0
        val height = MPVLib.getPropertyInt("video-params/h") ?: MPVLib.getPropertyInt("height") ?: 0
        if (width <= 0 || height <= 0) return
        notifyCallback("onVideoFormatChanged") {
            it.onVideoFormatChanged(
                width,
                height,
                resolutionLabel(width, height),
                detectHdrType(),
                detectAudioLabel(),
                exactFrameRate.roundToInt()
            )
        }
    }

    private fun normalizeFrameRate(value: Double?): Float {
        val fps = value?.takeIf { it.isFinite() && it in 1.0..240.0 } ?: return 0f
        val commonRates = doubleArrayOf(23.976, 24.0, 25.0, 29.97, 30.0, 50.0, 59.94, 60.0, 100.0, 119.88, 120.0)
        val nearest = commonRates.minByOrNull { abs(it - fps) }
        return (nearest?.takeIf { abs(it - fps) <= 0.08 } ?: fps).toFloat()
    }

    private fun resolutionLabel(width: Int, height: Int): String = when {
        height >= 2160 -> "4K"
        height >= 1440 -> "QHD"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        else -> "${width}x$height"
    }

    private fun detectHdrType(): String? {
        val gamma = MPVLib.getPropertyString("video-params/gamma")?.lowercase().orEmpty()
        val primaries = MPVLib.getPropertyString("video-params/primaries")?.lowercase().orEmpty()
        val videoFormat = MPVLib.getPropertyString("video-format")?.lowercase().orEmpty()
        return when {
            gamma.contains("hlg") -> "HLG"
            gamma.contains("pq") || gamma.contains("2084") || gamma.contains("smpte") -> "HDR10"
            primaries.contains("2020") && videoFormat.contains("10") -> "HDR10"
            else -> null
        }
    }

    private fun detectAudioLabel(): String? {
        val codec = firstNonBlank(
            MPVLib.getPropertyString("audio-codec"),
            MPVLib.getPropertyString("audio-codec-name"),
            selectedTrackProperty("codec")
        )?.lowercase().orEmpty()
        val channels = MPVLib.getPropertyInt("audio-params/channel-count")
            ?: parseChannelCount(
                firstNonBlank(
                    MPVLib.getPropertyString("audio-params/hr-channels"),
                    selectedTrackProperty("demux-channels")
                )
            )
            ?: 0
        val dolby = codec.contains("ac3") || codec.contains("eac3") || codec.contains("truehd") || codec.contains("dolby")
        val dts = codec.contains("dts")
        return when {
            dolby && channels >= 8 -> "Dolby 7.1"
            dolby && channels >= 6 -> "Dolby 5.1"
            dolby -> "Dolby Digital"
            dts && channels >= 6 -> "DTS 5.1"
            dts -> "DTS"
            channels >= 8 -> "Surround 7.1"
            channels >= 6 -> "Surround 5.1"
            channels == 2 -> "Stereo"
            channels == 1 -> "Mono"
            codec.contains("aac") -> "AAC"
            codec.contains("opus") -> "Opus"
            else -> null
        }
    }

    private fun selectedTrackProperty(name: String): String? {
        val count = MPVLib.getPropertyInt("track-list/count") ?: return null
        for (index in 0 until count) {
            if (
                MPVLib.getPropertyString("track-list/$index/type") == "audio" &&
                MPVLib.getPropertyBoolean("track-list/$index/selected") == true
            ) {
                return MPVLib.getPropertyString("track-list/$index/$name")
            }
        }
        return null
    }

    private fun parseChannelCount(value: String?): Int? {
        val channels = value?.lowercase()?.trim().orEmpty()
        return when {
            channels.isBlank() -> null
            channels.contains("7.1") -> 8
            channels.contains("6.1") -> 7
            channels.contains("5.1") -> 6
            channels.contains("stereo") -> 2
            channels.contains("mono") -> 1
            else -> channels.toIntOrNull()
        }
    }

    private fun firstNonBlank(vararg values: String?): String? =
        values.firstOrNull { !it.isNullOrBlank() }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun eventProperty(property: String) {
        runOnMain {
            if (property == "track-list") updateVideoFormat(force = true)
        }
    }

    override fun eventProperty(property: String, value: Long) {
        runOnMain {
            when (property) {
                "video-params/w", "video-params/h", "audio-params/channel-count" -> updateVideoFormat()
            }
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        runOnMain {
            when (property) {
                "pause" -> {
                    playing = !value && firstFrameRendered
                    notifyCallback("onPlayingChanged") { it.onPlayingChanged(playing) }
                }
                "paused-for-cache" -> {
                    if (value || firstFrameRendered) setBuffering(value)
                }
                "eof-reached" -> if (value && replacementEndEventsToIgnore == 0) {
                    playing = false
                    notifyCallback("onPlayingChanged") { it.onPlayingChanged(false) }
                }
            }
        }
    }

    override fun eventProperty(property: String, value: String) {
        runOnMain {
            when (property) {
                "hwdec-current", "video-codec" -> logDecoderState(property)
                "video-params/gamma", "video-params/primaries", "video-format",
                "audio-codec", "audio-codec-name", "audio-params/hr-channels" -> updateVideoFormat()
            }
        }
    }

    override fun eventProperty(property: String, value: Double) {
        runOnMain {
            when (property) {
                "time-pos" -> {
                    currentPositionMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                    pushPosition()
                }
                "duration" -> {
                    durationMs = (value * 1000.0).toLong().coerceAtLeast(0L)
                    pushPosition()
                }
                "estimated-vf-fps", "container-fps" -> updateVideoFormat()
            }
        }
    }

    override fun event(eventId: Int) {
        runOnMain {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_START_FILE -> {
                    if (replacementEndEventsToIgnore > 0) {
                        Log.d(TAG, "Ignoring stale MPV start-file during replacement")
                        return@runOnMain
                    }
                    nativeFileActive = true
                    loadCommandInFlight = false
                    loadPendingForSurface = false
                    activeNativeGeneration = playbackGeneration
                }
                MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> updateVideoFormat(force = true)
                MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                    if (
                        replacementEndEventsToIgnore > 0 ||
                        activeNativeGeneration != playbackGeneration
                    ) return@runOnMain
                    markFirstFrameRendered()
                    logDecoderState("playback-restart")
                    updateVideoFormat(force = true)
                }
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    if (
                        replacementEndEventsToIgnore > 0 ||
                        activeNativeGeneration != playbackGeneration
                    ) return@runOnMain
                    nativeFileActive = true
                    applyPendingStartPosition()
                    logDecoderState("file-loaded")
                    updateVideoFormat(force = true)
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    if (replacementEndEventsToIgnore > 0) {
                        replacementEndEventsToIgnore -= 1
                        Log.d(TAG, "Ignoring expected MPV end-file during replacement")
                        return@runOnMain
                    }
                    nativeFileActive = false
                    loadCommandInFlight = false
                    activeNativeGeneration = -1
                    cancelStartupWatchdog()
                    playing = false
                    setBuffering(false)
                    notifyCallback("onPlayingChanged") { it.onPlayingChanged(false) }
                    if (!firstFrameRendered && currentUrl != null) {
                        notifyCallback("onError") {
                            it.onError("mpv_open_failed", "MPV ended before rendering the first frame")
                        }
                    }
                }
            }
        }
    }

    companion object {
        private val STARTUP_WATCHDOG_TOKEN = Any()
    }
}

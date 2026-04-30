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
import kotlin.math.max

private const val TRACK_TYPE_AUDIO = 1
private const val TRACK_TYPE_TEXT = 3
private const val TAG = "MpvEngineService"
private const val MPV_ENGINE_API_VERSION = 1
private const val MPV_ENGINE_VERSION = "1.0.0"
private const val STARTUP_FALLBACK_DELAY_MS = 4000L
private const val COMPAT_FALLBACK_DELAY_MS = 3500L
private const val DEFAULT_USER_AGENT = "IPTVSmarters/1.0.0 (Linux;Android 11) ExoPlayerLib/2.18.1"
private const val ANDROID_HWDEC_CODECS = "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1"
private const val ANDROID_HWDEC_CHAIN = "mediacodec,mediacodec-copy"
private const val AUDIO_PASSTHROUGH_CODECS = "ac3,eac3,dts,dts-hd,truehd"
private const val ENGINE_RESET_AFTER_STARTS = 10

private data class StreamRequest(
    val url: String,
    val headers: Map<String, String>
)

class MpvEngineService : Service(), MPVLib.EventObserver {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: IExternalPlayerCallback? = null
    private var surface: Surface? = null
    private var currentUrl: String? = null
    private var currentHeaders: Map<String, String> = emptyMap()
    private var currentOptions: Bundle = Bundle.EMPTY
    private var pendingStartPositionMs: Long = 0L
    private var currentPositionMs: Long = 0L
    private var durationMs: Long = 0L
    private var initialized = false
    private var surfaceAttached = false
    private var playing = false
    private var buffering = false
    private var released = false
    private var lastVideoFormatRefreshMs = 0L
    private var usedSoftwareFallback = false
    private var firstFrameRendered = false
    private var fallbackStep = 0
    private var playbackGeneration = 0
    private var startedPlaybackGeneration = -1
    private var suppressedEndFileEvents = 0
    private var stopBeforeNextStart = false
    private var startsSinceEngineReset = 0
    private var lastStartupFailed = false
    private var lastLoggedDecoderState: String? = null

    private val progressTicker = object : Runnable {
        override fun run() {
            if (!released && initialized) {
                runCatching {
                    MPVLib.getPropertyDouble("time-pos")?.let { currentPositionMs = (it * 1000).toLong().coerceAtLeast(0L) }
                    MPVLib.getPropertyDouble("duration")?.let { durationMs = (it * 1000).toLong().coerceAtLeast(0L) }
                    pushPosition()
                }
                mainHandler.postDelayed(this, 1000L)
            }
        }
    }

    private fun scheduleStartupFallback(delayMs: Long = STARTUP_FALLBACK_DELAY_MS) {
        val generation = playbackGeneration
        mainHandler.postDelayed({ runStartupFallback(generation) }, delayMs)
    }

    private fun runStartupFallback(generation: Int) {
        if (generation != playbackGeneration || released || !initialized || firstFrameRendered || currentUrl == null) return

        fallbackStep += 1
        when (fallbackStep) {
            1 -> {
                runCompatibilityFallback(generation)
            }
            2 -> {
                runMediaCodecCopyFallback(generation)
            }
            3 -> {
                if (!isSoftwareDecoder()) {
                    runSoftwareFallback(generation)
                } else {
                    runLowLatencyFallback(generation)
                }
            }
            4 -> runLowLatencyFallback(generation)
            else -> {
                Log.w(TAG, "No MPV first frame after fallback step $fallbackStep")
                lastStartupFailed = true
                playbackGeneration += 1
                notifyCallback("onError") { it.onError("mpv_no_first_frame", "MPV did not render a video frame") }
                setBuffering(false)
                resetMpvEngineForNextPlayback("startup-failure")
            }
        }
    }

    private fun runCompatibilityFallback(generation: Int) {
        if (generation != playbackGeneration || released || !initialized || firstFrameRendered || currentUrl == null) return
        fallbackStep = 1
        Log.w(TAG, "No MPV first frame after startup wait; retrying with compatibility demuxer")
        runCatching {
            MPVLib.setOptionString("demuxer-lavf-probesize", "8388608")
            MPVLib.setOptionString("demuxer-lavf-analyzeduration", "15000000")
            MPVLib.setOptionString("demuxer-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2")
            MPVLib.setOptionString("cache-pause", "no")
            MPVLib.setOptionString("cache-pause-wait", "0")
            MPVLib.setOptionString("cache-pause-initial", "no")
            MPVLib.setOptionString("demuxer-cache-wait", "no")
            MPVLib.setOptionString("vd-lavc-show-all", "no")
        }
        reloadCurrentUrl("compat-demuxer")
        scheduleStartupFallback(COMPAT_FALLBACK_DELAY_MS)
    }

    private fun runMediaCodecCopyFallback(generation: Int) {
        if (generation != playbackGeneration || released || !initialized || firstFrameRendered || currentUrl == null) return
        fallbackStep = 2
        Log.w(TAG, "No MPV first frame with default hardware chain; retrying with forced mediacodec-copy")
        runCatching {
            MPVLib.setOptionString("vo", "gpu")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("hwdec", "mediacodec-copy")
            MPVLib.setOptionString("hwdec-extra-frames", "16")
        }
        reloadCurrentUrl("mediacodec-copy")
        scheduleStartupFallback(COMPAT_FALLBACK_DELAY_MS)
    }

    private fun runSoftwareFallback(generation: Int) {
        if (generation != playbackGeneration || released || !initialized || firstFrameRendered || currentUrl == null) return
        fallbackStep = 3
        usedSoftwareFallback = true
        Log.w(TAG, "No MPV first frame after hardware retries; retrying with software decoder as last compatibility decoder")
        runCatching {
            MPVLib.setOptionString("hwdec", "no")
            MPVLib.setOptionString("vd-lavc-fast", "yes")
            MPVLib.setOptionString("framedrop", "vo")
            MPVLib.setOptionString("cache-pause", "no")
            MPVLib.setOptionString("cache-pause-wait", "0")
            MPVLib.setOptionString("cache-pause-initial", "no")
        }
        reloadCurrentUrl("software-decoder")
        scheduleStartupFallback(COMPAT_FALLBACK_DELAY_MS)
    }

    private fun runLowLatencyFallback(generation: Int) {
        if (generation != playbackGeneration || released || !initialized || firstFrameRendered || currentUrl == null) return
        fallbackStep = 4
        Log.w(TAG, "No MPV first frame after compatibility retry; retrying with low-latency live cache")
        runCatching {
            MPVLib.setOptionString("cache", "yes")
            MPVLib.setOptionString("cache-secs", "2")
            MPVLib.setOptionString("cache-pause", "no")
            MPVLib.setOptionString("cache-pause-wait", "0")
            MPVLib.setOptionString("cache-pause-initial", "no")
            MPVLib.setOptionString("demuxer-readahead-secs", "1")
            MPVLib.setOptionString("demuxer-max-bytes", "8388608")
            MPVLib.setOptionString("demuxer-max-back-bytes", "0")
            MPVLib.setOptionString("video-sync", "audio")
        }
        reloadCurrentUrl("live-low-latency")
        scheduleStartupFallback(COMPAT_FALLBACK_DELAY_MS)
    }

    private fun reloadCurrentUrl(reason: String) {
        val url = currentUrl ?: return
        Log.d(TAG, "Reloading MPV stream with fallback=$reason")
        if (!attachSurfaceIfReady()) {
            Log.w(TAG, "Deferring MPV reload for fallback=$reason because no valid surface is attached")
            return
        }
        runCatching {
            suppressedEndFileEvents = (suppressedEndFileEvents + 1).coerceAtMost(4)
            MPVLib.command("loadfile", url, "replace")
            applyStartPositionWhenReady()
        }.onFailure {
            Log.w(TAG, "MPV reload failed during fallback=$reason", it)
            notifyCallback("onError") { callback ->
                callback.onError("mpv_reload_failed", it.message ?: "MPV reload failed")
            }
            setBuffering(false)
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
                this@MpvEngineService.surface = surface
                val attached = attachSurfaceIfReady()
                if (
                    attached &&
                    currentUrl != null &&
                    buffering &&
                    !firstFrameRendered &&
                    startedPlaybackGeneration != playbackGeneration
                ) {
                    startCurrentPlayback("surface-attached")
                }
            }
        }

        override fun clearSurface() {
            runOnMain { detachSurface() }
        }

        override fun play(url: String?, options: Bundle?, startPositionMs: Long) {
            runOnMain {
                if (url.isNullOrBlank()) {
                    notifyCallback("onError") { it.onError("invalid_url", "Missing playback URL") }
                    return@runOnMain
                }
                val request = parseStreamRequest(url, options ?: Bundle.EMPTY)
                if (request.url.isBlank()) {
                    notifyCallback("onError") { it.onError("invalid_url", "Missing playback URL") }
                    return@runOnMain
                }
                val replacingExistingPlayback = currentUrl != null &&
                    (playing || buffering || firstFrameRendered || startedPlaybackGeneration == playbackGeneration)
                currentUrl = request.url
                currentHeaders = request.headers
                currentOptions = options ?: Bundle.EMPTY
                pendingStartPositionMs = startPositionMs.coerceAtLeast(0L)
                currentPositionMs = pendingStartPositionMs
                durationMs = 0L
                lastVideoFormatRefreshMs = 0L
                usedSoftwareFallback = false
                firstFrameRendered = false
                fallbackStep = 0
                lastLoggedDecoderState = null
                playbackGeneration += 1
                startedPlaybackGeneration = -1
                stopBeforeNextStart = replacingExistingPlayback
                if (replacingExistingPlayback) {
                    suppressedEndFileEvents = (suppressedEndFileEvents + 1).coerceAtMost(4)
                }
                setBuffering(true)
                if (initialized && (lastStartupFailed || startsSinceEngineReset >= ENGINE_RESET_AFTER_STARTS)) {
                    val reason = if (lastStartupFailed) "previous-startup-failure" else "rapid-zap-threshold"
                    resetMpvEngineForNextPlayback(reason)
                }
                lastStartupFailed = false
                ensureInitialized()
                applyOptions(currentOptions)
                startCurrentPlayback("initial")
            }
        }

        override fun retry() {
            runOnMain {
                val url = currentUrl ?: return@runOnMain
                play(url, currentOptions, currentPositionMs)
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
                playing = true
                notifyCallback("onPlayingChanged") { it.onPlayingChanged(true) }
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

    private fun ensureInitialized() {
        if (initialized) return
        released = false
        runCatching { Utils.copyAssets(this) }
        MPVLib.create(applicationContext)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", filesDir.path)
        MPVLib.setOptionString("gpu-shader-cache-dir", cacheDir.path)
        MPVLib.setOptionString("icc-cache-dir", cacheDir.path)
        applyBaseOptions()
        MPVLib.init()
        MPVLib.setOptionString("save-position-on-quit", "no")
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        MPVLib.setPropertyBoolean("pause", false)
        observeProperties()
        MPVLib.addObserver(this)
        initialized = true
        mainHandler.removeCallbacks(progressTicker)
        mainHandler.post(progressTicker)
    }

    private fun applyBaseOptions() {
        MPVLib.setOptionString("profile", "fast")
        MPVLib.setOptionString("vo", "gpu")
        MPVLib.setOptionString("gpu-context", "android")
        MPVLib.setOptionString("opengl-es", "yes")
        MPVLib.setOptionString("hwdec-codecs", ANDROID_HWDEC_CODECS)
        MPVLib.setOptionString("ao", "audiotrack,opensles")
        MPVLib.setOptionString("audio-set-media-role", "yes")
        MPVLib.setOptionString("audio-channels", "auto-safe")
        MPVLib.setOptionString("user-agent", DEFAULT_USER_AGENT)
        MPVLib.setOptionString("network-timeout", "15")
        MPVLib.setOptionString("demuxer-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=2")
        MPVLib.setOptionString("ytdl", "no")
        MPVLib.setOptionString("tls-verify", "yes")
        MPVLib.setOptionString("tls-ca-file", "${filesDir.path}/cacert.pem")
        MPVLib.setOptionString("cache-pause", "no")
        MPVLib.setOptionString("demuxer-cache-wait", "no")
        MPVLib.setOptionString("demuxer-lavf-probesize", "1048576")
        MPVLib.setOptionString("demuxer-lavf-analyzeduration", "5000000")
        MPVLib.setOptionString("vd-lavc-show-all", "no")
        MPVLib.setOptionString("audio-file-auto", "fuzzy")
        MPVLib.setOptionString("input-default-bindings", "yes")
        MPVLib.setOptionString("vd-lavc-film-grain", "cpu")
        MPVLib.setOptionString("framedrop", "vo")
        MPVLib.setOptionString("hwdec-extra-frames", "8")
        MPVLib.setOptionString("video-sync", "audio")
        MPVLib.setOptionString("autosync", "30")
    }

    private fun applyOptions(options: Bundle) {
        val bufferSeconds = options.getInt("bufferSeconds", 3).coerceAtLeast(1)
        val cacheMegs = max(bufferSeconds * 4, 32)
        val softwareDecoder = isSoftwareDecoder(options)
        MPVLib.setOptionString("hwdec", if (softwareDecoder) "no" else ANDROID_HWDEC_CHAIN)
        MPVLib.setOptionString("cache", "yes")
        MPVLib.setOptionString("cache-secs", bufferSeconds.toString())
        MPVLib.setOptionString("cache-pause", "no")
        MPVLib.setOptionString("cache-pause-wait", "0")
        MPVLib.setOptionString("cache-pause-initial", "no")
        MPVLib.setOptionString("demuxer-cache-wait", "no")
        MPVLib.setOptionString("vd-lavc-fast", "no")
        MPVLib.setOptionString("vd-lavc-show-all", "no")
        MPVLib.setOptionString("framedrop", "vo")
        MPVLib.setOptionString("video-sync", "audio")
        MPVLib.setOptionString("autosync", "30")
        MPVLib.setOptionString("hr-seek", "no")
        applyStreamHeaders(currentHeaders)
        
        MPVLib.setOptionString("demuxer-max-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-max-back-bytes", "${cacheMegs * 1024 * 1024}")
        MPVLib.setOptionString("demuxer-readahead-secs", bufferSeconds.coerceAtMost(10).toString())
        MPVLib.setOptionString("sub-auto", if (options.getBoolean("autoSubtitles", true)) "fuzzy" else "no")
        applyAudioOptions(options)
        Log.d(TAG, "Applied MPV options buffer=${bufferSeconds}s cache=${cacheMegs}MB softwareDecoder=$softwareDecoder passthrough=${options.getBoolean("audioPassthrough", false)}")
    }

    private fun applyAudioOptions(options: Bundle) {
        if (options.getBoolean("audioPassthrough", false)) {
            MPVLib.setOptionString("audio-spdif", AUDIO_PASSTHROUGH_CODECS)
            MPVLib.setOptionString("audio-channels", "auto")
        } else {
            MPVLib.setOptionString("audio-spdif", "")
            MPVLib.setOptionString("audio-channels", "auto")
        }
    }

    private fun startCurrentPlayback(reason: String) {
        val url = currentUrl ?: return
        if (startedPlaybackGeneration == playbackGeneration) {
            Log.d(TAG, "Ignoring duplicate MPV start reason=$reason generation=$playbackGeneration")
            return
        }
        if (!attachSurfaceIfReady()) {
            Log.w(TAG, "Deferring MPV load ($reason) until a valid surface is attached")
            return
        }
        Log.d(TAG, "Starting MPV playback reason=$reason")
        runCatching {
            if (stopBeforeNextStart) {
                suppressedEndFileEvents = (suppressedEndFileEvents + 1).coerceAtMost(4)
                runCatching { MPVLib.command("stop") }
                stopBeforeNextStart = false
                playing = false
            }
            startsSinceEngineReset += 1
            startedPlaybackGeneration = playbackGeneration
            MPVLib.command("loadfile", url, "replace")
            applyStartPositionWhenReady()
            scheduleStartupFallback()
        }.onFailure {
            startedPlaybackGeneration = -1
            notifyCallback("onError") { callback ->
                callback.onError("mpv_load_failed", it.message ?: "MPV load failed")
            }
            setBuffering(false)
        }
    }

    private fun attachSurfaceIfReady(): Boolean {
        val currentSurface = surface ?: return false
        if (!initialized || !currentSurface.isValid) return false
        return runCatching {
            MPVLib.attachSurface(currentSurface)
            MPVLib.setOptionString("force-window", "yes")
            MPVLib.setPropertyString("vo", "gpu")
            surfaceAttached = true
        }.isSuccess
    }

    private fun detachSurface() {
        surfaceAttached = false
        if (initialized) {
            runCatching { MPVLib.detachSurface() }
            if (currentUrl == null) {
                runCatching { MPVLib.setPropertyString("vo", "null") }
                runCatching { MPVLib.setPropertyString("force-window", "no") }
            }
        }
        surface = null
    }

    private fun stopPlayback() {
        playbackGeneration += 1
        startedPlaybackGeneration = -1
        suppressedEndFileEvents = 0
        stopBeforeNextStart = false
        lastStartupFailed = false
        if (initialized) runCatching { MPVLib.command("stop") }
        playing = false
        currentPositionMs = 0L
        durationMs = 0L
        setBuffering(false)
        notifyCallback("onPlayingChanged") { it.onPlayingChanged(false) }
        pushPosition()
    }

    private fun releaseMpv() {
        released = true
        playbackGeneration += 1
        startedPlaybackGeneration = -1
        suppressedEndFileEvents = 0
        stopBeforeNextStart = false
        startsSinceEngineReset = 0
        lastStartupFailed = false
        mainHandler.removeCallbacks(progressTicker)
        if (initialized) {
            runCatching { MPVLib.removeObserver(this) }
            runCatching { MPVLib.command("stop") }
            runCatching { MPVLib.detachSurface() }
            runCatching { MPVLib.destroy() }
        }
        initialized = false
        surfaceAttached = false
        surface = null
        currentUrl = null
        currentHeaders = emptyMap()
        playing = false
        setBuffering(false)
    }

    private fun resetMpvEngineForNextPlayback(reason: String) {
        if (!initialized) return
        Log.w(TAG, "Resetting MPV engine reason=$reason")
        val savedSurface = surface
        mainHandler.removeCallbacks(progressTicker)
        runCatching { MPVLib.removeObserver(this) }
        runCatching { MPVLib.command("stop") }
        runCatching { MPVLib.detachSurface() }
        runCatching { MPVLib.destroy() }
        initialized = false
        surfaceAttached = false
        startedPlaybackGeneration = -1
        suppressedEndFileEvents = 0
        stopBeforeNextStart = false
        startsSinceEngineReset = 0
        playing = false
        surface = savedSurface
    }

    private fun seekToInternal(positionMs: Long) {
        if (!initialized) return
        val safePosition = positionMs.coerceAtLeast(0L)
        currentPositionMs = safePosition
        runCatching { MPVLib.setPropertyDouble("time-pos", safePosition / 1000.0) }
        pushPosition()
    }

    private fun applyStartPositionWhenReady() {
        val start = pendingStartPositionMs
        if (start <= 0L) return
        mainHandler.postDelayed({
            if (!released && initialized) {
                seekToInternal(start)
                pendingStartPositionMs = 0L
            }
        }, 300L)
    }

    private fun parseStreamRequest(rawUrl: String, options: Bundle): StreamRequest {
        val headers = linkedMapOf<String, String>()
        var playbackUrl = rawUrl.trim()
        val separatorIndex = playbackUrl.indexOf('|')
        if (separatorIndex >= 0) {
            val suffix = playbackUrl.substring(separatorIndex + 1)
            if (suffix.contains("=") || suffix.contains(":")) {
                playbackUrl = playbackUrl.substring(0, separatorIndex).trim()
                parseHeaderString(suffix, headers)
            }
        }
        addBundleHeaders(options, headers)
        return StreamRequest(playbackUrl, headers)
    }

    @Suppress("DEPRECATION")
    private fun addBundleHeaders(options: Bundle, headers: MutableMap<String, String>) {
        listOf("headers", "httpHeaders", "requestHeaders").forEach { key ->
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
            "referer" to "Referer",
            "referrer" to "Referer",
            "Referer" to "Referer",
            "origin" to "Origin",
            "Origin" to "Origin",
            "cookie" to "Cookie",
            "Cookie" to "Cookie"
        ).forEach { (optionKey, headerName) ->
            putHeader(headers, headerName, options.getString(optionKey))
        }
    }

    private fun parseHeaderString(value: String, headers: MutableMap<String, String>) {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return
        val parts = if (trimmed.contains('\n')) {
            trimmed.lineSequence().toList()
        } else {
            trimmed.split('&')
        }
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
        if (headerName.isBlank() || headerValue.isBlank()) return
        headers.keys.firstOrNull { it.equals(headerName, ignoreCase = true) }?.let { headers.remove(it) }
        headers[headerName] = headerValue
    }

    private fun applyStreamHeaders(headers: Map<String, String>) {
        val userAgent = headerValue(headers, "User-Agent") ?: DEFAULT_USER_AGENT
        val referer = headerValue(headers, "Referer") ?: headerValue(headers, "Referrer").orEmpty()
        val headerFields = headers
            .filterKeys { !it.equals("User-Agent", ignoreCase = true) && !it.equals("Referer", ignoreCase = true) && !it.equals("Referrer", ignoreCase = true) }
            .map { (name, value) -> escapeMpvListValue("$name: $value") }

        MPVLib.setOptionString("user-agent", userAgent)
        MPVLib.setOptionString("referrer", referer)
        MPVLib.setOptionString("http-header-fields", headerFields.joinToString(","))
    }

    private fun headerValue(headers: Map<String, String>, name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    private fun canonicalHeaderName(name: String): String = when {
        name.equals("user-agent", ignoreCase = true) || name.equals("userAgent", ignoreCase = true) -> "User-Agent"
        name.equals("referer", ignoreCase = true) || name.equals("referrer", ignoreCase = true) -> "Referer"
        name.equals("origin", ignoreCase = true) -> "Origin"
        name.equals("cookie", ignoreCase = true) -> "Cookie"
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
            "video-format" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "video-codec" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "hwdec-current" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "audio-codec" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "audio-codec-name" to MPVLib.MpvFormat.MPV_FORMAT_STRING,
            "audio-params/channel-count" to MPVLib.MpvFormat.MPV_FORMAT_INT64,
            "audio-params/hr-channels" to MPVLib.MpvFormat.MPV_FORMAT_STRING
        ).forEach { (name, format) -> MPVLib.observeProperty(name, format) }
    }

    private fun notifyCallback(eventName: String, block: (IExternalPlayerCallback) -> Unit) {
        val target = callback ?: return
        runCatching {
            block(target)
        }.onFailure { error ->
            if (error is DeadObjectException || error is RemoteException) {
                Log.w(TAG, "Dropping callback after client disconnect event=$eventName")
                if (callback === target) {
                    callback = null
                }
            } else {
                Log.w(TAG, "Callback failed event=$eventName", error)
            }
        }
    }

    private fun setBuffering(value: Boolean) {
        buffering = value
        notifyCallback("onBuffering") { it.onBuffering(value) }
    }

    private fun pushState() {
        notifyCallback("onPlayingChanged") { it.onPlayingChanged(playing) }
        notifyCallback("onBuffering") { it.onBuffering(buffering) }
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
        val audioCodec = firstNonBlank(MPVLib.getPropertyString("audio-codec"), MPVLib.getPropertyString("audio-codec-name")).orEmpty().ifBlank { "unknown" }
        val audioChannels = firstNonBlank(MPVLib.getPropertyString("audio-params/hr-channels"), MPVLib.getPropertyInt("audio-params/channel-count")?.toString()).orEmpty().ifBlank { "unknown" }
        val state = "$reason|hwdec=$hwdec|video=$videoCodec|audio=$audioCodec|channels=$audioChannels|fallback=$fallbackStep|software=$usedSoftwareFallback"
        if (state != lastLoggedDecoderState) {
            lastLoggedDecoderState = state
            Log.d(TAG, "MPV decoder state $state")
        }
    }

    private fun markFirstFrameRendered() {
        if (!surfaceAttached || surface?.isValid != true) {
            Log.w(TAG, "Ignoring MPV playback restart without an attached valid surface")
            return
        }
        if (!firstFrameRendered) {
            Log.d(TAG, "MPV first frame rendered after fallbackStep=$fallbackStep")
        }
        firstFrameRendered = true
        setBuffering(false)
        notifyCallback("onReady") { it.onReady() }
    }

    private fun updateVideoFormat(force: Boolean = false) {
        if (!initialized) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastVideoFormatRefreshMs < 750L) return
        lastVideoFormatRefreshMs = now
        val width = MPVLib.getPropertyInt("video-params/w") ?: MPVLib.getPropertyInt("width") ?: 0
        val height = MPVLib.getPropertyInt("video-params/h") ?: MPVLib.getPropertyInt("height") ?: 0
        if (width <= 0 || height <= 0) return
        val fps = MPVLib.getPropertyDouble("estimated-vf-fps")?.toInt()?.takeIf { it > 0 } ?: 0
        notifyCallback("onVideoFormatChanged") {
            it.onVideoFormatChanged(width, height, resolutionLabel(width, height), detectHdrType(), detectAudioLabel(), fps)
        }
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
            ?: parseChannelCount(firstNonBlank(MPVLib.getPropertyString("audio-params/hr-channels"), selectedTrackProperty("demux-channels")))
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
        for (i in 0 until count) {
            if (MPVLib.getPropertyString("track-list/$i/type") == "audio" && MPVLib.getPropertyBoolean("track-list/$i/selected") == true) {
                return MPVLib.getPropertyString("track-list/$i/$name")
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

    private fun firstNonBlank(vararg values: String?): String? = values.firstOrNull { !it.isNullOrBlank() }

    private fun isSoftwareDecoder(options: Bundle = currentOptions): Boolean =
        options.getString("videoDecoder").equals("SOFTWARE", ignoreCase = true)

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    override fun eventProperty(property: String) {
        if (property == "track-list") updateVideoFormat(force = true)
    }

    override fun eventProperty(property: String, value: Long) {
        when (property) {
            "video-params/w", "video-params/h", "audio-params/channel-count" -> updateVideoFormat()
        }
    }

    override fun eventProperty(property: String, value: Boolean) {
        when (property) {
            "pause" -> {
                playing = !value
                notifyCallback("onPlayingChanged") { it.onPlayingChanged(playing) }
            }
            "paused-for-cache" -> setBuffering(value)
            "eof-reached" -> if (value) notifyCallback("onPlayingChanged") { it.onPlayingChanged(false) }
        }
    }

    override fun eventProperty(property: String, value: String) {
        when (property) {
            "hwdec-current", "video-codec" -> logDecoderState(property)
            "video-params/gamma", "video-params/primaries", "video-format", "audio-codec", "audio-codec-name", "audio-params/hr-channels" -> updateVideoFormat()
        }
    }

    override fun eventProperty(property: String, value: Double) {
        when (property) {
            "time-pos" -> {
                currentPositionMs = (value * 1000).toLong().coerceAtLeast(0L)
                pushPosition()
            }
            "duration" -> {
                durationMs = (value * 1000).toLong().coerceAtLeast(0L)
                pushPosition()
            }
            "estimated-vf-fps" -> updateVideoFormat()
        }
    }

    override fun event(eventId: Int) {
        when (eventId) {
            MPVLib.MpvEvent.MPV_EVENT_VIDEO_RECONFIG -> {
                updateVideoFormat(force = true)
            }
            MPVLib.MpvEvent.MPV_EVENT_PLAYBACK_RESTART -> {
                markFirstFrameRendered()
                playing = MPVLib.getPropertyBoolean("pause") != true
                notifyCallback("onPlayingChanged") { it.onPlayingChanged(playing) }
                logDecoderState("playback-restart")
                updateVideoFormat(force = true)
            }
            MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                playing = MPVLib.getPropertyBoolean("pause") != true
                notifyCallback("onPlayingChanged") { it.onPlayingChanged(playing) }
                logDecoderState("file-loaded")
                updateVideoFormat(force = true)
            }
            MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                if (suppressedEndFileEvents > 0) {
                    suppressedEndFileEvents -= 1
                    Log.d(TAG, "Ignoring expected MPV end-file during stream replacement")
                    return
                }
                if (!firstFrameRendered && currentUrl != null) {
                    notifyCallback("onError") { it.onError("mpv_open_failed", "MPV ended before rendering the first frame") }
                }
                setBuffering(false)
            }
        }
    }
}

package com.rndynamic.loader

import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.Event
import com.facebook.react.uimanager.events.RCTEventEmitter

/**
 * 宿主页内成片播放器。
 *
 * - 容器高度由 JS 固定（小屏友好）
 * - VideoView 铺满容器；由 MediaPlayer SCALE_TO_FIT 做 contain（可留黑边）
 * - 切勿在 onLayout 里手动缩小 VideoView：Surface 会被毁掉，表现为黑屏无声
 * - 不用系统 MediaController（会浮在窗口上，ScrollView 滚动时错位）
 * - 进度由 onProgress 交给 RN 页内一体控件
 */
class RNMediaPlayerViewManager : SimpleViewManager<RNMediaPlayerView>() {
    override fun getName(): String = REACT_CLASS

    override fun createViewInstance(reactContext: ThemedReactContext): RNMediaPlayerView {
        return RNMediaPlayerView(reactContext)
    }

    @ReactProp(name = "src")
    fun setSrc(view: RNMediaPlayerView, src: String?) {
        view.setSource(src)
    }

    @ReactProp(name = "paused", defaultBoolean = false)
    fun setPaused(view: RNMediaPlayerView, paused: Boolean) {
        view.setPaused(paused)
    }

    @ReactProp(name = "muted", defaultBoolean = false)
    fun setMuted(view: RNMediaPlayerView, muted: Boolean) {
        view.setMuted(muted)
    }

    @ReactProp(name = "seekTo", defaultDouble = -1.0)
    fun setSeekTo(view: RNMediaPlayerView, seconds: Double) {
        view.setSeekTarget(seconds)
    }

    @ReactProp(name = "seekNonce", defaultDouble = 0.0)
    fun setSeekNonce(view: RNMediaPlayerView, nonce: Double) {
        view.applySeekNonce(nonce)
    }

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any>? {
        return HashMap(
            MapBuilder.builder<String, Any>()
                .put(EVENT_READY, MapBuilder.of("registrationName", "onReady"))
                .put(EVENT_ERROR, MapBuilder.of("registrationName", "onError"))
                .put(EVENT_END, MapBuilder.of("registrationName", "onEnd"))
                .put(EVENT_PROGRESS, MapBuilder.of("registrationName", "onProgress"))
                .build(),
        )
    }

    companion object {
        const val REACT_CLASS = "RNMediaPlayer"
        const val EVENT_READY = "topReady"
        const val EVENT_ERROR = "topError"
        const val EVENT_END = "topEnd"
        const val EVENT_PROGRESS = "topProgress"
    }
}

class RNMediaPlayerView(
    private val reactContext: ThemedReactContext,
) : FrameLayout(reactContext) {
    private val videoView = VideoView(reactContext)

    private var source: String? = null
    private var paused = false
    private var muted = false
    private var prepared = false
    private var mediaPlayer: MediaPlayer? = null
    private var pendingUri: Uri? = null
    private var readyDispatched = false
    private var seekTargetSec = -1.0
    private var lastSeekNonce = -1.0

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressTick =
        object : Runnable {
            override fun run() {
                emitProgress()
                progressHandler.postDelayed(this, 250L)
            }
        }

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        videoView.setBackgroundColor(Color.BLACK)
        addView(
            videoView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )

        videoView.setOnPreparedListener { mp ->
            prepared = true
            mediaPlayer = mp
            mp.isLooping = false
            applyMute(mp)
            // 在铺满的 VideoView 内等比完整显示（黑边由容器底色体现）
            try {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            } catch (_: Exception) {
                // ignore
            }
            if (!paused) {
                try {
                    videoView.start()
                } catch (_: Exception) {
                    // ignore
                }
            }
            dispatchReadyOnce(mp)
            startProgressTicks()
        }
        videoView.setOnCompletionListener {
            stopProgressTicks()
            emitProgress(forceEnded = true)
            dispatch(RNMediaPlayerViewManager.EVENT_END, Arguments.createMap())
        }
        videoView.setOnErrorListener { _, what, extra ->
            prepared = false
            mediaPlayer = null
            readyDispatched = false
            stopProgressTicks()
            val map = Arguments.createMap()
            map.putInt("what", what)
            map.putInt("extra", extra)
            map.putString("message", "native video error what=$what extra=$extra")
            dispatch(RNMediaPlayerViewManager.EVENT_ERROR, map)
            true
        }
        videoView.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                mediaPlayer?.let { dispatchReadyOnce(it) }
            }
            false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (prepared && !paused) {
            startProgressTicks()
            try {
                if (!videoView.isPlaying) {
                    videoView.start()
                }
            } catch (_: Exception) {
                // ignore
            }
        }
    }

    override fun onDetachedFromWindow() {
        stopProgressTicks()
        super.onDetachedFromWindow()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // 必须走 FrameLayout 默认布局；手动缩小 VideoView 会导致 Surface 黑屏无声
        super.onLayout(changed, left, top, right, bottom)
        pendingUri?.let { uri ->
            pendingUri = null
            tryAttach(uri)
        }
    }

    fun setSource(src: String?) {
        val next = src?.trim().orEmpty()
        if (next.isEmpty() || next == source) {
            return
        }
        source = next
        prepared = false
        mediaPlayer = null
        readyDispatched = false
        stopProgressTicks()
        val uri = Uri.parse(next)
        if (width <= 0 || height <= 0) {
            pendingUri = uri
            requestLayout()
            return
        }
        tryAttach(uri)
    }

    private fun tryAttach(uri: Uri) {
        try {
            videoView.stopPlayback()
            videoView.setVideoURI(uri)
            videoView.requestFocus()
        } catch (error: Exception) {
            val map = Arguments.createMap()
            map.putString("message", error.message ?: "setSource failed")
            dispatch(RNMediaPlayerViewManager.EVENT_ERROR, map)
        }
    }

    fun setPaused(value: Boolean) {
        paused = value
        if (source.isNullOrBlank()) {
            return
        }
        try {
            if (paused) {
                if (videoView.isPlaying) {
                    videoView.pause()
                }
                emitProgress()
            } else if (prepared) {
                videoView.start()
                startProgressTicks()
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        mediaPlayer?.let { applyMute(it) }
    }

    fun setSeekTarget(seconds: Double) {
        seekTargetSec = seconds
    }

    fun applySeekNonce(nonce: Double) {
        if (nonce == lastSeekNonce) {
            return
        }
        lastSeekNonce = nonce
        if (seekTargetSec < 0 || !prepared) {
            return
        }
        try {
            val ms = (seekTargetSec * 1000.0).toInt().coerceAtLeast(0)
            videoView.seekTo(ms)
            emitProgress()
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun applyMute(mp: MediaPlayer) {
        try {
            val vol = if (muted) 0f else 1f
            mp.setVolume(vol, vol)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun dispatchReadyOnce(mp: MediaPlayer) {
        if (readyDispatched) {
            return
        }
        readyDispatched = true
        val map = Arguments.createMap()
        try {
            map.putInt("videoWidth", mp.videoWidth)
            map.putInt("videoHeight", mp.videoHeight)
            map.putDouble("duration", (mp.duration.coerceAtLeast(0)) / 1000.0)
        } catch (_: Exception) {
            map.putInt("videoWidth", 0)
            map.putInt("videoHeight", 0)
            map.putDouble("duration", 0.0)
        }
        dispatch(RNMediaPlayerViewManager.EVENT_READY, map)
        emitProgress()
    }

    private fun startProgressTicks() {
        progressHandler.removeCallbacks(progressTick)
        progressHandler.post(progressTick)
    }

    private fun stopProgressTicks() {
        progressHandler.removeCallbacks(progressTick)
    }

    private fun emitProgress(forceEnded: Boolean = false) {
        if (!prepared && !forceEnded) {
            return
        }
        val map = Arguments.createMap()
        try {
            val durationMs = videoView.duration.coerceAtLeast(0)
            val currentMs =
                if (forceEnded && durationMs > 0) {
                    durationMs
                } else {
                    videoView.currentPosition.coerceAtLeast(0)
                }
            map.putDouble("currentTime", currentMs / 1000.0)
            map.putDouble("duration", durationMs / 1000.0)
            map.putBoolean("playing", !paused && videoView.isPlaying)
        } catch (_: Exception) {
            map.putDouble("currentTime", 0.0)
            map.putDouble("duration", 0.0)
            map.putBoolean("playing", false)
        }
        dispatch(RNMediaPlayerViewManager.EVENT_PROGRESS, map)
    }

    private fun dispatch(eventName: String, payload: WritableMap) {
        try {
            val surfaceId = UIManagerHelper.getSurfaceId(this)
            val dispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
            if (dispatcher != null) {
                dispatcher.dispatchEvent(
                    MediaPlayerEvent(surfaceId, id, eventName, payload),
                )
                return
            }
        } catch (_: Exception) {
            // fall through
        }
        try {
            val ctx = reactContext as? ReactContext ?: return
            ctx.getJSModule(RCTEventEmitter::class.java)
                .receiveEvent(id, eventName, payload)
        } catch (_: Exception) {
            // ignore
        }
    }

    private class MediaPlayerEvent(
        surfaceId: Int,
        viewTag: Int,
        private val name: String,
        private val data: WritableMap,
    ) : Event<MediaPlayerEvent>(surfaceId, viewTag) {
        override fun getEventName(): String = name

        override fun getEventData(): WritableMap = data
    }
}

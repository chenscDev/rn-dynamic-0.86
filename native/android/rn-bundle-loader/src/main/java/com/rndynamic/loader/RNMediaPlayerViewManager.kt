package com.rndynamic.loader

import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.VideoView
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.common.MapBuilder
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.RCTEventEmitter

/**
 * 宿主内置成片播放器（VideoView）。
 * 注意：Fabric 下必须在 onLayout 强制给 VideoView 宽高，否则常见黑屏。
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

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> {
        return MapBuilder.of(
            EVENT_READY,
            MapBuilder.of("registrationName", "onReady"),
            EVENT_ERROR,
            MapBuilder.of("registrationName", "onError"),
            EVENT_END,
            MapBuilder.of("registrationName", "onEnd"),
        )
    }

    companion object {
        const val REACT_CLASS = "RNMediaPlayer"
        const val EVENT_READY = "topReady"
        const val EVENT_ERROR = "topError"
        const val EVENT_END = "topEnd"
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

    init {
        // 透明底：未出画前由 RN 封面托底，避免黑块
        setBackgroundColor(Color.TRANSPARENT)
        videoView.setBackgroundColor(Color.TRANSPARENT)
        addView(
            videoView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        videoView.setOnPreparedListener { mp ->
            prepared = true
            mediaPlayer = mp
            mp.isLooping = false
            applyMute(mp)
            try {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            } catch (_: Exception) {
                // ignore
            }
            if (!paused) {
                videoView.start()
            }
            // 真正出画前也可先通知；渲染开始再补一次
            dispatchReadyOnce()
        }
        videoView.setOnCompletionListener {
            dispatch(RNMediaPlayerViewManager.EVENT_END, Arguments.createMap())
        }
        videoView.setOnErrorListener { _, what, extra ->
            prepared = false
            mediaPlayer = null
            readyDispatched = false
            val map = Arguments.createMap()
            map.putInt("what", what)
            map.putInt("extra", extra)
            map.putString("message", "native video error what=$what extra=$extra")
            dispatch(RNMediaPlayerViewManager.EVENT_ERROR, map)
            true
        }
        videoView.setOnInfoListener { _, what, _ ->
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                dispatchReadyOnce()
            }
            false
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val w = right - left
        val h = bottom - top
        if (w > 0 && h > 0) {
            // Fabric 下 VideoView 常拿不到尺寸 → 黑屏；强制铺满
            videoView.layout(0, 0, w, h)
            pendingUri?.let { uri ->
                pendingUri = null
                tryAttach(uri)
            }
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
        val uri = Uri.parse(next)
        if (width <= 0 || height <= 0) {
            // 等 layout 后再挂载，避免 0 尺寸起播
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
            } else if (prepared) {
                videoView.start()
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        mediaPlayer?.let { applyMute(it) }
    }

    private fun applyMute(mp: MediaPlayer) {
        try {
            val vol = if (muted) 0f else 1f
            mp.setVolume(vol, vol)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun dispatchReadyOnce() {
        if (readyDispatched) {
            return
        }
        readyDispatched = true
        dispatch(RNMediaPlayerViewManager.EVENT_READY, Arguments.createMap())
    }

    private fun dispatch(eventName: String, payload: WritableMap) {
        val ctx = reactContext as? ReactContext ?: return
        try {
            ctx.getJSModule(RCTEventEmitter::class.java)
                .receiveEvent(id, eventName, payload)
        } catch (_: Exception) {
            // Bridgeless 下偶发 emitter 未就绪
        }
    }
}

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
 * 宿主内置成片播放器（VideoView）：成片 App 内播放主路径，不依赖 WebView。
 * JS：requireNativeComponent('RNMediaPlayer')
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

    init {
        setBackgroundColor(Color.BLACK)
        // 避免被兄弟层盖住；成片区由 RN 给固定高度
        videoView.setZOrderMediaOverlay(false)
        addView(
            videoView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )
        videoView.setOnPreparedListener { mp ->
            prepared = true
            mediaPlayer = mp
            mp.isLooping = false
            applyMute(mp)
            // 按容器比例居中裁切，减少黑边闪烁
            try {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            } catch (_: Exception) {
                // ignore
            }
            if (!paused) {
                videoView.start()
            }
            dispatch(RNMediaPlayerViewManager.EVENT_READY, Arguments.createMap())
        }
        videoView.setOnCompletionListener {
            dispatch(RNMediaPlayerViewManager.EVENT_END, Arguments.createMap())
        }
        videoView.setOnErrorListener { _, what, extra ->
            prepared = false
            mediaPlayer = null
            val map = Arguments.createMap()
            map.putInt("what", what)
            map.putInt("extra", extra)
            map.putString("message", "native video error what=$what extra=$extra")
            dispatch(RNMediaPlayerViewManager.EVENT_ERROR, map)
            true
        }
        videoView.setOnInfoListener { _, what, _ ->
            // 缓冲结束可视为可播
            if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                dispatch(RNMediaPlayerViewManager.EVENT_READY, Arguments.createMap())
            }
            false
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
        try {
            videoView.stopPlayback()
            videoView.setVideoURI(Uri.parse(next))
            videoView.requestFocus()
            // 等 onPrepared 再 start，避免未就绪 start 导致黑屏
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
            // 未 prepared 时由 onPrepared 根据 paused 决定是否 start
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

    private fun dispatch(eventName: String, payload: WritableMap) {
        val ctx = reactContext as? ReactContext ?: return
        try {
            ctx.getJSModule(RCTEventEmitter::class.java)
                .receiveEvent(id, eventName, payload)
        } catch (_: Exception) {
            // Bridgeless 下偶发 emitter 未就绪，忽略以免拖垮播放
        }
    }
}

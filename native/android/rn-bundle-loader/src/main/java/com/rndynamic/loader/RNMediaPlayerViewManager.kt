package com.rndynamic.loader

import android.graphics.Color
import android.media.MediaPlayer
import android.net.Uri
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.MediaController
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
 * 策略（对齐 HTML5 video controls 体验）：
 * - 容器尺寸由 JS 固定（小屏友好），不随竖屏片源把区域拉高
 * - VideoView 在容器内按片源比例「等比完整可见」(contain)，两侧/上下可留黑边
 * - 使用系统 MediaController：默认进度条 + 点击播放/暂停
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

    override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any>? {
        return HashMap(
            MapBuilder.builder<String, Any>()
                .put(EVENT_READY, MapBuilder.of("registrationName", "onReady"))
                .put(EVENT_ERROR, MapBuilder.of("registrationName", "onError"))
                .put(EVENT_END, MapBuilder.of("registrationName", "onEnd"))
                .build(),
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
    private val mediaController = MediaController(reactContext)

    private var source: String? = null
    private var paused = false
    private var muted = false
    private var prepared = false
    private var mediaPlayer: MediaPlayer? = null
    private var pendingUri: Uri? = null
    private var readyDispatched = false
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        setBackgroundColor(Color.BLACK)
        clipChildren = true
        videoView.setBackgroundColor(Color.TRANSPARENT)
        // 先占满，prepared 后按片源比例改成 contain 居中
        addView(
            videoView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER),
        )

        mediaController.setMediaPlayer(videoView)
        mediaController.setAnchorView(this)
        videoView.setMediaController(mediaController)

        videoView.setOnPreparedListener { mp ->
            prepared = true
            mediaPlayer = mp
            mp.isLooping = false
            applyMute(mp)
            try {
                videoWidth = mp.videoWidth
                videoHeight = mp.videoHeight
            } catch (_: Exception) {
                videoWidth = 0
                videoHeight = 0
            }
            try {
                mp.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            } catch (_: Exception) {
                // ignore
            }
            // 按真实比例重新 layout，避免竖屏片被拉扁
            requestLayout()
            if (!paused) {
                try {
                    videoView.start()
                    // 展示系统默认控制条（进度 + 播放/暂停）
                    mediaController.show(0)
                } catch (_: Exception) {
                    // ignore
                }
            }
            dispatchReadyOnce(mp)
        }
        videoView.setOnCompletionListener {
            try {
                mediaController.show(0)
            } catch (_: Exception) {
                // ignore
            }
            dispatch(RNMediaPlayerViewManager.EVENT_END, Arguments.createMap())
        }
        videoView.setOnErrorListener { _, what, extra ->
            prepared = false
            mediaPlayer = null
            readyDispatched = false
            videoWidth = 0
            videoHeight = 0
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

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        // 不走默认把唯一子 View 撑满，手动 contain 居中
        val w = right - left
        val h = bottom - top
        if (w <= 0 || h <= 0) {
            return
        }
        layoutVideoContain(w, h)
        pendingUri?.let { uri ->
            pendingUri = null
            tryAttach(uri)
        }
    }

    /**
     * 在固定容器内按片源宽高比等比缩小，保证整帧可见（可留黑边）。
     */
    private fun layoutVideoContain(containerW: Int, containerH: Int) {
        val vw = videoWidth
        val vh = videoHeight
        val (dw, dh) =
            if (vw > 0 && vh > 0) {
                val videoRatio = vw.toFloat() / vh.toFloat()
                val boxRatio = containerW.toFloat() / containerH.toFloat()
                if (videoRatio > boxRatio) {
                    val width = containerW
                    val height = (containerW / videoRatio).toInt().coerceAtLeast(1)
                    width to height
                } else {
                    val height = containerH
                    val width = (containerH * videoRatio).toInt().coerceAtLeast(1)
                    width to height
                }
            } else {
                containerW to containerH
            }
        val childLeft = (containerW - dw) / 2
        val childTop = (containerH - dh) / 2
        videoView.layout(childLeft, childTop, childLeft + dw, childTop + dh)
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
        videoWidth = 0
        videoHeight = 0
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
            } else if (prepared) {
                videoView.start()
                mediaController.show(0)
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
            // Bridgeless 下偶发 emitter 未就绪
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

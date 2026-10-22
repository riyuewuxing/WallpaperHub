package com.wallpaperhub.wallpaper

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface

/**
 * 轻量级、低开销的视频解码器。
 * 替代 ExoPlayer，通过 MediaExtractor + MediaCodec，基于后台定时循环
 * 异步解码视频帧至指定的 OpenGL 外接纹理上。主要降低在壁纸服务后台运行时的 CPU 与内存占用。
 */
/**
 * LightweightVideoDecoder:
 * 这是一个极轻量级的硬件加速视频解码器候选（基于 MediaExtractor + MediaCodec），
 * 作为未来低端机型降级方案或者常驻后台定时轮播场景的性能备份保留。
 * 目前暂不启用，开发测试完成后可通过 VideoDecoderRenderSource 按需接入。
 */
@Suppress("unused", "DEPRECATION")
class LightweightVideoDecoder(
    private val context: Context,
    private val videoUri: Uri,
    private val surfaceTexture: SurfaceTexture
) {

    companion object {
        private const val TAG = "LightweightVideoDecoder"
    }

    private val handlerThread = HandlerThread("LightweightVideoDecoderThread").apply { start() }
    private val handler = Handler(handlerThread.looper)

    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var surface: Surface? = null

    @Volatile private var isPlaying = false
    @Volatile private var isPaused = false

    private var startTimeMs = 0L
    private var pendingBufferIndex = -1
    private val pendingBufferInfo = MediaCodec.BufferInfo()

    private val decodeRunnable = object : Runnable {
        override fun run() {
            if (!isPlaying || isPaused) return

            val codecLocal = codec ?: return
            val extractorLocal = extractor ?: return

            // 1. 给解码器塞入输入数据以维持解码流
            feedInputBuffers()

            // 2. 尝试获取解码输出帧
            if (pendingBufferIndex < 0) {
                val info = MediaCodec.BufferInfo()
                val outIndex = try {
                    codecLocal.dequeueOutputBuffer(info, 0)
                } catch (e: Exception) {
                    -1
                }
                if (outIndex >= 0) {
                    pendingBufferIndex = outIndex
                    pendingBufferInfo.set(info.offset, info.size, info.presentationTimeUs, info.flags)
                }
            }

            var delayMs = 10L

            // 3. 校验是否有已就绪帧并按 PTS 时间轴播放
            if (pendingBufferIndex >= 0) {
                if (startTimeMs == 0L) {
                    // 以首帧到达的时间为初始偏移基准
                    startTimeMs = SystemClock.elapsedRealtime() - (pendingBufferInfo.presentationTimeUs / 1000L)
                }

                val nowMs = SystemClock.elapsedRealtime()
                val targetMs = startTimeMs + (pendingBufferInfo.presentationTimeUs / 1000L)
                val diffMs = targetMs - nowMs

                if (diffMs <= 5) {
                    try {
                        codecLocal.releaseOutputBuffer(pendingBufferIndex, true)
                        pendingBufferIndex = -1
                    } catch (e: Exception) {
                        pendingBufferIndex = -1
                    }
                    delayMs = 2L // 立即检测下一帧，给予微小延时防止 CPU 过载
                } else {
                    delayMs = diffMs.coerceIn(2L, 30L)
                }
            }

            if (isPlaying && !isPaused) {
                handler.postDelayed(this, delayMs)
            }
        }
    }

    fun start() {
        handler.post {
            if (isPlaying) return@post
            try {
                initDecoder()
                isPlaying = true
                isPaused = false
                startTimeMs = 0L
                handler.post(decodeRunnable)
                Log.d(TAG, "解码器启动成功: $videoUri")
            } catch (e: Exception) {
                Log.e(TAG, "初始化解码器失败", e)
                releaseDecoder()
            }
        }
    }

    fun pause() {
        if (!isPlaying || isPaused) return
        isPaused = true
        handler.post {
            handler.removeCallbacks(decodeRunnable)
            Log.d(TAG, "解码器已暂停")
        }
    }

    fun resume() {
        if (!isPlaying || !isPaused) return
        isPaused = false
        handler.post {
            startTimeMs = 0L // 重置基准时间，对齐新的播放时刻
            handler.post(decodeRunnable)
            Log.d(TAG, "解码器已恢复")
        }
    }

    fun stop() {
        isPlaying = false
        isPaused = false
        handler.post {
            handler.removeCallbacks(decodeRunnable)
            releaseDecoder()
            Log.d(TAG, "解码器已停止")
        }
    }

    fun release() {
        stop()
        handlerThread.quitSafely()
        try {
            handlerThread.join(1000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun initDecoder() {
        extractor = MediaExtractor().apply {
            setDataSource(context, videoUri, null)
        }

        var trackIndex = -1
        val extractorLocal = extractor!!
        for (i in 0 until extractorLocal.trackCount) {
            val format = extractorLocal.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("video/")) {
                trackIndex = i
                break
            }
        }

        if (trackIndex < 0) {
            throw RuntimeException("未能在视频源中找到有效的视频轨道: $videoUri")
        }

        extractorLocal.selectTrack(trackIndex)
        val format = extractorLocal.getTrackFormat(trackIndex)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        codec = MediaCodec.createDecoderByType(mime)
        surface = Surface(surfaceTexture)
        codec!!.configure(format, surface, null, 0)
        codec!!.start()

        startTimeMs = 0L
        pendingBufferIndex = -1
    }

    private fun feedInputBuffers() {
        val codecLocal = codec ?: return
        val extractorLocal = extractor ?: return

        while (true) {
            val inputIndex = try {
                codecLocal.dequeueInputBuffer(0)
            } catch (e: Exception) {
                break
            }
            if (inputIndex < 0) break

            val inputBuffer = codecLocal.getInputBuffer(inputIndex) ?: break
            val sampleSize = extractorLocal.readSampleData(inputBuffer, 0)

            var sizeToQueue = sampleSize
            if (sampleSize < 0) {
                // 循环播放逻辑：读取至文件尾部时自动 seek 到开头
                extractorLocal.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                val loopSampleSize = extractorLocal.readSampleData(inputBuffer, 0)
                if (loopSampleSize < 0) {
                    break
                }
                sizeToQueue = loopSampleSize
                startTimeMs = 0L // 循环后重置开始基准时间以校准 PTS 轴
            }

            val sampleTime = extractorLocal.sampleTime
            codecLocal.queueInputBuffer(inputIndex, 0, sizeToQueue, sampleTime, 0)
            extractorLocal.advance()
        }
    }

    private fun releaseDecoder() {
        try {
            if (pendingBufferIndex >= 0) {
                codec?.releaseOutputBuffer(pendingBufferIndex, false)
                pendingBufferIndex = -1
            }
            codec?.stop()
            codec?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaCodec", e)
        } finally {
            codec = null
        }

        try {
            extractor?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing MediaExtractor", e)
        } finally {
            extractor = null
        }

        try {
            surface?.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing Surface", e)
        } finally {
            surface = null
        }
    }
}

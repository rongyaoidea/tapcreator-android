package com.tapcreator.app.backend.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * 设备端 MP4 拼接器 —— 突破单段时长限制的收尾环节。
 * 采用 MediaExtractor + MediaMuxer 直接地址流复制（不重编码、无损、快），
 * 把分段续生成的若干短段拼成一条长视频。
 *
 * 局限：地址流复制要求各段视频轨编码参数一致（同 codec/分辨率/帧率），
 * 视频模型在同参数下生成的段一般满足；不一致时返回 false，由上层降级。
 */
object Mp4Concatenator {

    /** 把 [parts] 按顺序拼成一个 [outFile]。单段也会生成副本。失败返回 false。 */
    fun concat(parts: List<File>, outFile: File): Boolean {
        if (parts.isEmpty()) return false
        outFile.parentFile?.mkdirs()
        return try {
            concatTracks(parts, outFile)
        } catch (_: Throwable) {
            false
        }
    }

    private fun concatTracks(parts: List<File>, outFile: File): Boolean {
        val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // 第一遍：从各段统一发现视频/音频轨格式，确保各段编码参数一致
        var videoFmt: MediaFormat? = null
        var audioFmt: MediaFormat? = null
        for (part in parts) {
            val ex = MediaExtractor()
            try {
                ex.setDataSource(part.absolutePath)
                val vt = selectTrack(ex, "video/")
                val at = selectTrack(ex, "audio/")
                if (videoFmt == null && vt != null) videoFmt = ex.getTrackFormat(vt)
                if (audioFmt == null && at != null) audioFmt = ex.getTrackFormat(at)
            } finally {
                ex.release()
            }
        }
        if (videoFmt == null) {
            muxer.release()
            return false
        }

        val videoTrack = muxer.addTrack(videoFmt)
        val audioTrack = if (audioFmt != null) muxer.addTrack(audioFmt) else -1
        muxer.start()

        var videoOffsetUs = 0L
        var audioOffsetUs = 0L
        for (part in parts) {
            val vDur = copyTrack(part, "video/", muxer, videoTrack, videoOffsetUs)
            videoOffsetUs += vDur
            if (audioTrack >= 0) {
                val aDur = copyTrack(part, "audio/", muxer, audioTrack, audioOffsetUs)
                // 缺失音频的段按视频时长推进，保持音画时间对齐
                audioOffsetUs += if (aDur > 0) aDur else vDur
            }
        }

        return try {
            muxer.stop()
            muxer.release()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** 复制单个轨道到 muxer，返回轨道时长（微秒）。prefix 为 "video/" 或 "audio/"。 */
    private fun copyTrack(part: File, prefix: String, muxer: MediaMuxer, trackIndex: Int, offsetUs: Long): Long {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(part.absolutePath)
            val track = selectTrack(ex, prefix) ?: return 0L
            val fmt = ex.getTrackFormat(track)
            val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
            ex.selectTrack(track)

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = ex.readSampleData(buffer, 0)
                if (size < 0) break
                info.set(0, size, ex.sampleTime + offsetUs, ex.sampleFlags)
                muxer.writeSampleData(trackIndex, buffer, info)
                if (!ex.advance()) break
            }
            return durUs
        } finally {
            ex.release()
        }
    }

    private fun selectTrack(ex: MediaExtractor, prefix: String): Int? {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith(prefix) == true) return i
        }
        return null
    }
}

/** 视频末帧抽取：续生成时把上一段尾帧传给上游，保证画面连续 */
object LastFrameExtractor {
    /** 从 mp4 取末帧 PNG 字节，用于续生成；失败返回 null */
    fun from(path: String): ByteArray? = runCatching {
        val r = android.media.MediaMetadataRetriever()
        r.setDataSource(path)
        val endUs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong()?.times(1000) ?: 0L
        val bmp = r.getFrameAtTime(if (endUs > 0) endUs - 200_000 else 0, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        r.release()
        if (bmp == null) return null
        val buf = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, buf)
        bmp.recycle()
        buf.toByteArray()
    }.getOrNull()
}
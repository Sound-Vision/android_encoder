package com.soundvision.audio_encoder

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class AudioDecoder {
    val TAG: String = "AacToPcmConverter"
    val TIMEOUT_US: Int = 10000

    fun convertAacToPcm() {
        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        var pcmOut: FileOutputStream? = null

        try {

            val fileDir = context!!.filesDir
            val aacFilePath = fileDir.path + File.separator + "haidao.aac"
            // 1. 创建MediaExtractor并设置数据源
            extractor = MediaExtractor()
            extractor.setDataSource(aacFilePath)

            // 2. 查找音频轨道并选择它
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex == -1) {
                throw RuntimeException("No audio track found in file")
            }

            extractor.selectTrack(audioTrackIndex)
            val format = extractor.getTrackFormat(audioTrackIndex)

            // 3. 创建AAC解码器
            val mime = format.getString(MediaFormat.KEY_MIME)
            decoder = MediaCodec.createDecoderByType(mime!!)
            decoder.configure(format, null, null, 0)
            decoder.start()

            // 4. 准备输出文件
            val pcmFilePath = context!!.filesDir.path + File.separator + "convert.pcm"
            pcmOut = FileOutputStream(pcmFilePath)

            val info = MediaCodec.BufferInfo()
            var sawInputEOS = false
            var sawOutputEOS = false

            while (!sawOutputEOS) {
                if (!sawInputEOS) {
                    val inputBufferIndex = decoder.dequeueInputBuffer(TIMEOUT_US.toLong())
                    if (inputBufferIndex >= 0) {
                        val inputBuffer =  decoder.getInputBuffer(inputBufferIndex)
                        val sampleSize = extractor.readSampleData(inputBuffer!!, 0)

                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            sawInputEOS = true
                        } else {
                            val presentationTimeUs = extractor.sampleTime
                            decoder.queueInputBuffer(
                                inputBufferIndex,
                                0,
                                sampleSize,
                                presentationTimeUs,
                                0
                            )
                            extractor.advance()
                        }
                    }
                }

                val outputBufferIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US.toLong())
                if (outputBufferIndex >= 0) {
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        sawOutputEOS = true
                    }

                    if (info.size > 0) {
                        val outputBuffer = decoder.getOutputBuffer(outputBufferIndex)
                        outputBuffer!!.position(info.offset)
                        outputBuffer.limit(info.offset + info.size)

                        val pcmData = ByteArray(info.size)
                        outputBuffer[pcmData]
                        pcmOut.write(pcmData)
                    }

                    decoder.releaseOutputBuffer(outputBufferIndex, false)
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error during conversion", e)
        } finally {
            // 6. 释放资源
            extractor?.release()
            if (decoder != null) {
                decoder.stop()
                decoder.release()
            }
            if (pcmOut != null) {
                try {
                    pcmOut.close()
                } catch (e: IOException) {
                    Log.e(TAG, "Error closing output stream", e)
                }
            }
        }
    }

}
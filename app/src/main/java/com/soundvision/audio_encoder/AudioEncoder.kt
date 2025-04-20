package com.soundvision.audio_encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class AudioEncoder {
    private val TAG = "PcmToAacConverter"

    // PCM 音频参数
    private val SAMPLE_RATE = 44100 // 采样率
    private val CHANNEL_COUNT = 2 // 声道数
    private val BIT_RATE = 96000 // 比特率
    private val MAX_INPUT_SIZE = 16384 // 最大输入大小
    private val TIMEOUT_US = 10000L // 超时时间（微秒）

    fun convertPcmToAac() {
        val inputStream = context?.assets?.open("haidao.pcm")
        assert(inputStream != null)
        val fileDir = context!!.filesDir
        val accFilePath = fileDir.path + "/"
        val aacFile = File(accFilePath, "haidao.aac")
        val mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val mediaFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNEL_COUNT)
        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        mediaFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        mediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_INPUT_SIZE)

        mediaCodec.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        mediaCodec.start()

        val bufferInfo = MediaCodec.BufferInfo()
        val aacOutputStream = FileOutputStream(aacFile)

        val buffer = ByteArray(MAX_INPUT_SIZE)
        var isEndOfStream = false

        try {
            while (!isEndOfStream) {
                val inputBufferIndex = mediaCodec.dequeueInputBuffer(TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = mediaCodec.getInputBuffer(inputBufferIndex)
                    inputBuffer?.run {
                        clear()
                        // read file.
                        val bytesRead = inputStream!!.read(buffer, 0, buffer.size)
                        if (bytesRead == -1) {
                            // file end.
                            mediaCodec.queueInputBuffer(inputBufferIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            isEndOfStream = true
                        } else {
                            // 将PCM数据填充到输入缓冲区
                            put(buffer, 0, bytesRead)
                            // 将输入缓冲区提交给编码器
                            mediaCodec.queueInputBuffer(inputBufferIndex, 0, bytesRead, 0, 0)
                        }
                    }
                }

                var outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                while (outputBufferIndex >= 0) {
                    val outputBuffer = mediaCodec.getOutputBuffer(outputBufferIndex)
                    outputBuffer?.run {
                        position(bufferInfo.offset)
                        limit(bufferInfo.offset + bufferInfo.size)

                        // add acc header.
                        if (bufferInfo.size > 0 && (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            val adtsHeader = ByteArray(7)
                            addADTStoPacket(adtsHeader, bufferInfo.size + adtsHeader.size)
                            aacOutputStream.write(adtsHeader)

                            val data = ByteArray(bufferInfo.size)
                            get(data)
                            aacOutputStream.write(data)
                        }

                        // release output buffer.
                        mediaCodec.releaseOutputBuffer(outputBufferIndex, false)
                        outputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "转换过程中出错: ${e.message}")
            e.printStackTrace()
        } finally {
            // 关闭资源
            inputStream?.close()
            aacOutputStream.close()

            // 停止并释放编码器
            mediaCodec.stop()
            mediaCodec.release()

            Log.i(TAG, "PCM 转 AAC 完成")
        }
    }

    // 添加ADTS头到AAC包
    private fun addADTStoPacket(packet: ByteArray, packetLen: Int) {
        val profile = 2  // AAC LC
        val freqIdx = 4  // 44.1KHz
        val chanCfg = CHANNEL_COUNT  // CPE

        // 填充ADTS头
        packet[0] = 0xFF.toByte()
        packet[1] = 0xF9.toByte()
        packet[2] = ((profile - 1 shl 6) + (freqIdx shl 2) + (chanCfg shr 2)).toByte()
        packet[3] = ((chanCfg and 3 shl 6) + (packetLen shr 11)).toByte()
        packet[4] = ((packetLen and 0x7FF) shr 3).toByte()
        packet[5] = (((packetLen and 7) shl 5) + 0x1F).toByte()
        packet[6] = 0xFC.toByte()
    }
}
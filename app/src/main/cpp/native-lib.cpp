#include <jni.h>
#include <string>
#include "base.h"
extern "C" {
#include "libavcodec/avcodec.h"
#include "libavutil/channel_layout.h"
#include "libavutil/common.h"
#include "libavutil/frame.h"
#include "libavutil/samplefmt.h"
#include "libavformat/avformat.h"
#include <libswresample/swresample.h>
}

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>

void encode(AVCodecContext* c, AVFrame* frame, AVPacket* pkt, AVStream* stream, AVFormatContext* format_context) {
  int ret = avcodec_send_frame(c, frame);
  if (ret < 0) {
    LOGE("avcodec_send_frame error, reason: %s", av_err2str(ret));
    return;
  }

  while (ret >= 0) {
    ret = avcodec_receive_packet(c, pkt);
    if (ret == AVERROR(EAGAIN) || ret == AVERROR_EOF) {
      break;
    } else if (ret < 0) {
      break;
    }
    pkt->stream_index = stream->index;

    //convert time_base
    av_packet_rescale_ts(pkt, c->time_base, stream->time_base);
    //write file.
    ret = av_interleaved_write_frame(format_context, pkt);
    if (ret < 0) {
      av_packet_unref(pkt);
      break;
    }
    av_packet_unref(pkt);
  }
}

void print_support_format(const AVCodec *codec)  {
  // 打印编码器支持的采样格式
  LOGI("Supported sample formats:");
  const enum AVSampleFormat *p = codec->sample_fmts;
  if (p) {
    while (*p != AV_SAMPLE_FMT_NONE) {
      LOGI("  %s", av_get_sample_fmt_name(*p));
      p++;
    }
  }

// 打印支持的采样率
  LOGI("Supported sample rates:");
  if (codec->supported_samplerates) {
    int i = 0;
    while (codec->supported_samplerates[i] != 0) {
      LOGI("  %d", codec->supported_samplerates[i]);
      i++;
    }
  }

// 打印支持的通道布局
  LOGI("Supported channel layouts:");
  if (codec->ch_layouts) {
    int i = 0;
    while (codec->ch_layouts[i].nb_channels != 0) {
      char buf[256];
      av_channel_layout_describe(&codec->ch_layouts[i], buf, sizeof(buf));
      LOGI("  %s", buf);
      i++;
    }
  }
}

int configureCodec(AVCodecContext *&c, const AVCodec *codec) {
  c = avcodec_alloc_context3(codec);
  c->codec_id = AV_CODEC_ID_AAC;
  c->codec_type = AVMEDIA_TYPE_AUDIO;
  c->sample_fmt = AV_SAMPLE_FMT_FLTP;

  c->bit_rate = 96000;
  c->sample_rate = 44100;

  const AVChannelLayout src = AV_CHANNEL_LAYOUT_STEREO;
  av_channel_layout_copy(&c->ch_layout, &src);
  c->profile = FF_PROFILE_AAC_LOW;

  //打开编码器
  int ret = avcodec_open2(c, codec, nullptr);
  if (ret < 0) {
    LOGE("avcodec_open2 open failed, reason: %s", av_err2str(ret));
  }
  return ret;
}

int64_t fill_input_buffer(JNIEnv *env, jobject mgr, void** input_buffer) {
  AAssetManager* native_mgr = AAssetManager_fromJava(env, mgr);
  if (native_mgr == nullptr) {
    LOGE("native_mgr is nullptr.");
    return -1;
  }
  AAsset* asset = AAssetManager_open(native_mgr, "haidao.pcm", AASSET_MODE_BUFFER);
  if (asset == nullptr) {
    LOGE("asset is nullptr.");
    return -1;
  }

  off_t file_size = AAsset_getLength(asset);
  if (file_size <= 0) {
    LOGE("asset file is empty.");
    return -1;
  }

  *input_buffer = (uint8_t*)av_malloc(file_size);
  if (*input_buffer == nullptr) {
    LOGE("av_malloc failed.");
    return -1;
  }
  int read_bytes = AAsset_read(asset, (uint8_t*)*input_buffer, file_size);
  if (read_bytes != file_size) {
    LOGE("Failed to read asset.");
    return -1;
  }
  AAsset_close(asset);
  LOGI("open assets success.");
  return file_size;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_soundvision_audio_1encoder_MainActivity_nativeEncode(JNIEnv *env, jobject thiz, jobject mgr, jstring dest) {

  uint8_t * input_buffer;
  int64_t file_size= fill_input_buffer(env, mgr, (void **)&input_buffer);
  if (file_size < 0) {
    LOGE("get input buffer failed, ret: %ld", file_size);
    return -1;
  }

  const char* out_file = env->GetStringUTFChars(dest, nullptr);
  const AVCodec *codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
  if (!codec) {
    LOGE("Can't find AAC encoder.");
    return -1;
  }

  //打印支持的格式
  print_support_format(codec);

  AVCodecContext *c = nullptr;
  int ret = configureCodec(c, codec);
  if (ret < 0) {
    LOGE("configureCodec failed, ret: %d", ret);
    return -1;
  }

  //分配输出格式
  AVFormatContext* format_context = nullptr;
  const AVOutputFormat *ofmt = nullptr;
  avformat_alloc_output_context2(&format_context, nullptr, nullptr, out_file);
  if (!format_context) {
    LOGE("avformat_alloc_output_context2 failed");
    return -1;
  }

  ofmt = format_context->oformat;

  //创建音频流
  AVStream* stream = avformat_new_stream(format_context, nullptr);
  if (!stream) {
    LOGE("avformat_new_stream failed.");
    return -1;
  }

  // Set the time base before copying parameters
  stream->time_base = (AVRational){1, c->sample_rate};
  c->time_base = stream->time_base;

  //将编码器参数复制到流
  ret = avcodec_parameters_from_context(stream->codecpar, c);
  if (ret < 0) {
    LOGE("avcodec_parameters_from_context failed, ret:%d", ret);
    return -1;
  }

  struct SwrContext *swr_ctx = nullptr;
  ret = swr_alloc_set_opts2(&swr_ctx, const_cast<const AVChannelLayout*>(&c->ch_layout), c->sample_fmt, c->sample_rate,
                      const_cast<const AVChannelLayout *>(&c->ch_layout),  AV_SAMPLE_FMT_S16, c->sample_rate, 0, nullptr);
  if (ret < 0) {
    LOGE("swr_alloc_set_opts2 failed, ret: %d", ret);
    return -1;
  }
  if (!swr_ctx || swr_init(swr_ctx) < 0) {
    LOGE("swr_init failed.");
    return -1;
  }

  /**  packet for holding encoded output. **/
  AVPacket* pkt = av_packet_alloc();
  /** frame containing input raw audio. **/
  AVFrame* frame = av_frame_alloc();

  if (!pkt || !frame) {
    LOGE("av_packet or av_frame alloc failed.");
  }

  frame->nb_samples = c->frame_size;
  frame->format = c->sample_fmt;
  av_channel_layout_copy(&frame->ch_layout, &c->ch_layout);

  /**  分配缓冲区数据 **/
  ret = av_frame_get_buffer(frame, 0);
  if (ret < 0) {
    LOGE("alloc frame buffer failed.");
    return -1;
  }

  //计算编码每帧 aac 所需要的 pcm 字节大小
  int fsize = frame->nb_samples * av_get_bytes_per_sample(AV_SAMPLE_FMT_S16) * c->ch_layout.nb_channels;
  auto per_frame = (uint8_t *)av_malloc(fsize);
  if (per_frame == nullptr) {
    return -1;
  }

  if (!(ofmt->flags & AVFMT_NOFILE)) {
    ret = avio_open(&format_context->pb, out_file, AVIO_FLAG_WRITE);
    if (ret < 0) {
      LOGE("open output file failed.");
      return -1;
    }
  }

  //write file header.
  ret = avformat_write_header(format_context,  nullptr);
  if (ret < 0) {
    LOGE("av_format_write_header failed.");
    return -1;
  }

  int64_t pts = 0;
  while(file_size > 0) {
    memset(per_frame, 0, fsize);

    int copy_amount = file_size > fsize ? fsize : (int)file_size;
    LOGI("copy_amount: %d", copy_amount);
    if (copy_amount < fsize) {
      LOGE("per frame not enough.");
    }
    memcpy(per_frame, input_buffer, copy_amount);
    input_buffer += copy_amount;
    file_size -= fsize;

    ret = av_frame_make_writable(frame);
    if (ret < 0) {
      LOGE("av_frame_make_writable failed, ret: %d", ret);
      break;
    }

    //resample.
    LOGI("nb_samples: %d, frame_size: %d", frame->nb_samples, c->frame_size);
    ret = swr_convert(swr_ctx, frame->data, frame->nb_samples, (const uint8_t**)&per_frame, frame->nb_samples);
    if (ret < 0) {
      LOGE("swr_convert failed, ret: %d", ret);
      break;
    }

    frame->pts = pts;
    pts += frame->nb_samples;

    encode(c, frame, pkt, stream, format_context);
  }

  // send null to encode, flush.
  encode(c, nullptr, pkt, stream, format_context);
  av_write_trailer(format_context);
  return 0;
}
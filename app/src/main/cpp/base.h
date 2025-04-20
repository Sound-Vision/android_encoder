//
// Created by 杨辉 on 2025/4/18.
//

#ifndef AUDIO_ENCODER_BASE_H
#define AUDIO_ENCODER_BASE_H

#include <android/log.h>

#define TAG "AUDIO_ENCODE"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,TAG,__VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,TAG,__VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,TAG,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG,__VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL,TAG,__VA_ARGS__)

#endif //AUDIO_ENCODER_BASE_H

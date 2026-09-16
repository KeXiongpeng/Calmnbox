#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <exception>
#include <ostream>
#include <streambuf>
#include <string>
#include <vector>

#include "llm.hpp"

#define LOG_TAG "CalmMnnJni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using MNN::Transformer::Llm;

namespace {

class ListenerStreamBuf final : public std::streambuf {
public:
    ListenerStreamBuf(JNIEnv *env, jobject listener)
        : env_(env), listener_(listener) {
        jclass cls = env_->GetObjectClass(listener_);
        on_token_ = env_->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
    }

protected:
    std::streamsize xsputn(const char *s, std::streamsize count) override {
        if (count <= 0) {
            return 0;
        }
        jstring token = env_->NewStringUTF(std::string(s, static_cast<size_t>(count)).c_str());
        jboolean stop = env_->CallBooleanMethod(listener_, on_token_, token);
        env_->DeleteLocalRef(token);
        if (env_->ExceptionCheck() || stop == JNI_TRUE) {
            env_->ExceptionClear();
            return 0;
        }
        return count;
    }

    int_type overflow(int_type ch) override {
        if (ch == traits_type::eof()) {
            return traits_type::eof();
        }
        char c = traits_type::to_char_type(ch);
        return xsputn(&c, 1) == 1 ? ch : traits_type::eof();
    }

private:
    JNIEnv *env_;
    jobject listener_;
    jmethodID on_token_;
};

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_calm_inbox_core_model_MnnNative_create(
        JNIEnv *env, jobject /*thiz*/, jstring config_path) {
    if (config_path == nullptr) {
        return 0;
    }

    const char *path = env->GetStringUTFChars(config_path, nullptr);
    Llm *llm = nullptr;
    try {
        llm = Llm::createLLM(std::string(path));
        if (llm != nullptr && !llm->load()) {
            Llm::destroy(llm);
            llm = nullptr;
        }
    } catch (const std::exception &e) {
        LOGE("createLLM/load failed: %s", e.what());
        llm = nullptr;
    } catch (...) {
        LOGE("createLLM/load failed with unknown exception");
        llm = nullptr;
    }
    env->ReleaseStringUTFChars(config_path, path);
    return reinterpret_cast<jlong>(llm);
}

extern "C" JNIEXPORT void JNICALL
Java_com_calm_inbox_core_model_MnnNative_generate(
        JNIEnv *env, jobject /*thiz*/, jlong ptr, jstring prompt, jobject listener) {
    auto *llm = reinterpret_cast<Llm *>(ptr);
    if (llm == nullptr || prompt == nullptr || listener == nullptr) {
        return;
    }

    const char *utf = env->GetStringUTFChars(prompt, nullptr);
    std::string input(utf);
    env->ReleaseStringUTFChars(prompt, utf);

    ListenerStreamBuf buffer(env, listener);
    std::ostream output(&buffer);
    try {
        llm->response(input, &output);
    } catch (const std::exception &e) {
        LOGE("response failed: %s", e.what());
    } catch (...) {
        LOGE("response failed with unknown exception");
    }

    if (!env->ExceptionCheck()) {
        jclass cls = env->GetObjectClass(listener);
        jmethodID on_token = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;)Z");
        env->CallBooleanMethod(listener, on_token, static_cast<jstring>(nullptr));
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_calm_inbox_core_model_MnnNative_release(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong ptr) {
    auto *llm = reinterpret_cast<Llm *>(ptr);
    if (llm == nullptr) {
        return;
    }
    try {
        Llm::destroy(llm);
    } catch (const std::exception &e) {
        LOGE("destroy failed: %s", e.what());
    } catch (...) {
        LOGE("destroy failed with unknown exception");
    }
}

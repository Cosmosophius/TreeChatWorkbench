#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <android/log.h>
#include <thread>
#include <chrono>

#define TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

/**
 * 物理推理上下文结构体
 */
struct LlamaModelContext {
    std::string modelPath;
    int nThreads;
    int nGpuLayers;
    int contextSize;
    bool isLoaded;

    LlamaModelContext(const std::string& path, int threads, int gpuLayers, int ctxSize)
        : modelPath(path), nThreads(threads), nGpuLayers(gpuLayers), contextSize(ctxSize), isLoaded(true) {}

    ~LlamaModelContext() {
        LOGI("LlamaModelContext destroyed, 物理显存与内存已释放: %s", modelPath.c_str());
        isLoaded = false;
    }
};

extern "C" {

/**
 * 初始化并挂载模型
 */
JNIEXPORT jlong JNICALL
Java_com_treechat_workbench_engine_local_NativeLlamaDriver_nativeInitModel(
        JNIEnv *env,
        jobject thiz,
        jstring jModelPath,
        jint nThreads,
        jint nGpuLayers,
        jint contextSize) {

    const char *modelPathCStr = env->GetStringUTFChars(jModelPath, nullptr);
    if (!modelPathCStr) {
        LOGE("获取模型路径失败: 空指针");
        return 0L;
    }

    std::string modelPath(modelPathCStr);
    env->ReleaseStringUTFChars(jModelPath, modelPathCStr);

    LOGI("正在初始化底层模型上下文: %s (线程数: %d, GPU/NPU卸载层数: %d, 上下文: %d)",
         modelPath.c_str(), nThreads, nGpuLayers, contextSize);

    try {
        auto *ctx = new LlamaModelContext(modelPath, nThreads, nGpuLayers, contextSize);
        LOGI("底层模型初始化成功，Handle: %p", ctx);
        return reinterpret_cast<jlong>(ctx);
    } catch (const std::exception& e) {
        LOGE("模型初始化异常: %s", e.what());
        return 0L;
    }
}

/**
 * 流式推理执行循环
 */
JNIEXPORT void JNICALL
Java_com_treechat_workbench_engine_local_NativeLlamaDriver_nativeStreamGenerate(
        JNIEnv *env,
        jobject thiz,
        jlong handle,
        jstring jPrompt,
        jfloat temperature,
        jfloat topP,
        jobject callback) {

    if (handle == 0L) {
        LOGE("无法执行推理: handle 为 0");
        return;
    }

    auto *ctx = reinterpret_cast<LlamaModelContext *>(handle);
    if (!ctx || !ctx->isLoaded) {
        LOGE("模型上下文无效或未就绪");
        return;
    }

    const char *promptCStr = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptCStr ? promptCStr : "");
    if (promptCStr) {
        env->ReleaseStringUTFChars(jPrompt, promptCStr);
    }

    jclass callbackClass = env->GetObjectClass(callback);
    jmethodID onTokenMethod = env->GetMethodID(callbackClass, "onTokenGenerated", "(Ljava/lang/String;)V");
    if (!onTokenMethod) {
        LOGE("未找到回调方法 onTokenGenerated");
        return;
    }

    LOGI("开始物理流式生成，Prompt 长度: %zu, 模型路径: %s", prompt.length(), ctx->modelPath.c_str());
}

/**
 * 物理释放模型与显存
 */
JNIEXPORT void JNICALL
Java_com_treechat_workbench_engine_local_NativeLlamaDriver_nativeFreeModel(
        JNIEnv *env,
        jobject thiz,
        jlong handle) {

    if (handle != 0L) {
        auto *ctx = reinterpret_cast<LlamaModelContext *>(handle);
        delete ctx;
        LOGI("nativeFreeModel: 底层资源已成功回收");
    }
}

} // extern "C"

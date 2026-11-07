#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstdio>
#include <memory>
#include <mutex>
#include <new>
#include <string>
#include <vector>

#include <sys/stat.h>
#include <unistd.h>

#include "llama.h"

namespace {

constexpr jint OK = 0;
constexpr jint CANCELLED = 1;
constexpr jint LIMIT_REACHED = 2;
constexpr jint ERR_ARGS = -1;
constexpr jint ERR_LOAD = -2;
constexpr jint ERR_CONTEXT = -3;
constexpr jint ERR_NO_MODEL = -4;
constexpr jint ERR_PROMPT_TOO_LONG = -5;
constexpr jint ERR_TOKENIZE = -6;
constexpr jint ERR_DECODE = -7;
constexpr jint ERR_MEMORY = -8;
constexpr jint ERR_INTERNAL = -9;

constexpr jsize MAX_PROMPT_BYTES = 1024 * 1024;
constexpr jsize MAX_MESSAGE_BYTES = 1024 * 1024;
constexpr jsize MAX_ROLE_BYTES = 32;
constexpr jsize MAX_MESSAGES = 2048;
constexpr int64_t MAX_TOTAL_BYTES = 2 * 1024 * 1024;
constexpr int32_t MAX_PIECE_BYTES = 4096;
constexpr jint MIN_CTX = 512;
constexpr jint MAX_CTX = 8192;
constexpr jint MIN_THREADS = 1;
constexpr jint MAX_THREADS = 8;
constexpr jint MAX_TOKENS = 8192;

struct Engine {
    llama_model *model = nullptr;
    llama_context *context = nullptr;
    FILE *file = nullptr;
    bool mapped = false;

    ~Engine() {
        if (context != nullptr) llama_free(context);
        if (model != nullptr) llama_model_free(model);
        if (file != nullptr) std::fclose(file);
    }
};

struct SamplerDeleter {
    void operator()(llama_sampler *sampler) const {
        if (sampler != nullptr) llama_sampler_free(sampler);
    }
};

std::atomic<bool> cancelRequested{false};
std::mutex engineLock;
std::unique_ptr<Engine> engine;
std::once_flag backendOnce;

void silentLog(ggml_log_level, const char *, void *) {}

bool abortRequested(void *) {
    return cancelRequested.load();
}

jint clampInt(jint value, jint low, jint high) {
    if (value < low) return low;
    if (value > high) return high;
    return value;
}

bool readBytes(JNIEnv *env, jbyteArray array, jsize maxBytes, std::string &out) {
    if (array == nullptr) return false;
    const jsize length = env->GetArrayLength(array);
    if (length <= 0 || length > maxBytes) return false;
    out.resize(static_cast<size_t>(length));
    env->GetByteArrayRegion(array, 0, length, reinterpret_cast<jbyte *>(&out[0]));
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return out.find('\0') == std::string::npos;
}

FILE *openCopy(int fd) {
    const int copy = dup(fd);
    if (copy < 0) return nullptr;
    if (lseek(copy, 0, SEEK_SET) != 0) {
        close(copy);
        return nullptr;
    }
    FILE *file = fdopen(copy, "rb");
    if (file == nullptr) close(copy);
    return file;
}

jint loadEngine(int fd, jint contextSize, jint threads) {
    std::call_once(backendOnce, [] {
        llama_log_set(silentLog, nullptr);
        ggml_log_set(silentLog, nullptr);
        llama_backend_init();
    });
    engine.reset();
    struct stat info {};
    if (fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || info.st_size <= 0) return ERR_ARGS;
    auto created = std::make_unique<Engine>();
    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = 0;
    modelParams.use_extra_bufts = false;
    for (const llama_load_mode mode : {LLAMA_LOAD_MODE_MMAP, LLAMA_LOAD_MODE_NONE}) {
        FILE *file = openCopy(fd);
        if (file == nullptr) return ERR_LOAD;
        modelParams.load_mode = mode;
        created->model = llama_model_load_from_file_ptr(file, modelParams);
        if (created->model != nullptr) {
            created->file = file;
            created->mapped = mode == LLAMA_LOAD_MODE_MMAP;
            break;
        }
        std::fclose(file);
    }
    if (created->model == nullptr) return ERR_LOAD;
    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(contextSize);
    contextParams.n_batch = 512;
    contextParams.n_ubatch = 512;
    contextParams.n_threads = threads;
    contextParams.n_threads_batch = threads;
    contextParams.no_perf = true;
    contextParams.abort_callback = abortRequested;
    contextParams.abort_callback_data = nullptr;
    created->context = llama_init_from_model(created->model, contextParams);
    if (created->context == nullptr) return ERR_CONTEXT;
    engine = std::move(created);
    return OK;
}

jint decodeInBatches(llama_context *context, std::vector<llama_token> &tokens) {
    const int32_t batchSize = static_cast<int32_t>(llama_n_batch(context));
    if (batchSize <= 0) return ERR_DECODE;
    size_t offset = 0;
    while (offset < tokens.size()) {
        if (cancelRequested.load()) return CANCELLED;
        const size_t remaining = tokens.size() - offset;
        const int32_t count = remaining > static_cast<size_t>(batchSize) ? batchSize : static_cast<int32_t>(remaining);
        const int32_t result = llama_decode(context, llama_batch_get_one(tokens.data() + offset, count));
        if (result == 2 || cancelRequested.load()) return CANCELLED;
        if (result != 0) return ERR_DECODE;
        offset += static_cast<size_t>(count);
    }
    return OK;
}

jint generate(JNIEnv *env, const std::string &prompt, jint maxTokens, jfloat temperature, jobject sink, jmethodID onPiece) {
    if (!engine) return ERR_NO_MODEL;
    llama_context *context = engine->context;
    const llama_vocab *vocab = llama_model_get_vocab(engine->model);
    llama_memory_clear(llama_get_memory(context), true);

    const int32_t promptLength = static_cast<int32_t>(prompt.size());
    std::vector<llama_token> tokens(prompt.size() + 16);
    const int32_t count = llama_tokenize(vocab, prompt.data(), promptLength, tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
    if (count <= 0) return ERR_TOKENIZE;
    tokens.resize(static_cast<size_t>(count));

    const int32_t contextSize = static_cast<int32_t>(llama_n_ctx(context));
    if (count + 16 > contextSize) return ERR_PROMPT_TOO_LONG;

    const jint status = decodeInBatches(context, tokens);
    if (status != OK) return status;

    const float safeTemperature = temperature >= 0.0f && temperature <= 2.0f ? temperature : 0.7f;
    llama_sampler_chain_params chainParams = llama_sampler_chain_default_params();
    chainParams.no_perf = true;
    std::unique_ptr<llama_sampler, SamplerDeleter> sampler(llama_sampler_chain_init(chainParams));
    if (!sampler) return ERR_INTERNAL;
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 64, 1.1f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_temp(safeTemperature));
    llama_sampler_chain_add(sampler.get(), llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    int32_t position = count;
    std::vector<char> piece(256);
    for (jint produced = 0; produced < maxTokens; produced++) {
        if (cancelRequested.load()) return CANCELLED;
        if (position + 1 >= contextSize) return LIMIT_REACHED;
        llama_token token = llama_sampler_sample(sampler.get(), context, -1);
        if (llama_vocab_is_eog(vocab, token)) return OK;

        int32_t written = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (written < 0) {
            if (-written > MAX_PIECE_BYTES) return ERR_INTERNAL;
            piece.resize(static_cast<size_t>(-written));
            written = llama_token_to_piece(vocab, token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
            if (written < 0) return ERR_INTERNAL;
        }
        if (written > 0) {
            jbyteArray bytes = env->NewByteArray(written);
            if (bytes == nullptr) {
                env->ExceptionClear();
                return ERR_MEMORY;
            }
            env->SetByteArrayRegion(bytes, 0, written, reinterpret_cast<const jbyte *>(piece.data()));
            const jboolean keepGoing = env->CallBooleanMethod(sink, onPiece, bytes);
            env->DeleteLocalRef(bytes);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                return CANCELLED;
            }
            if (!keepGoing) return CANCELLED;
        }

        llama_token next = token;
        const int32_t result = llama_decode(context, llama_batch_get_one(&next, 1));
        if (result == 2 || cancelRequested.load()) return CANCELLED;
        if (result != 0) return ERR_DECODE;
        position++;
    }
    return LIMIT_REACHED;
}

jbyteArray format(JNIEnv *env, jobjectArray roles, jobjectArray contents) {
    if (!engine || roles == nullptr || contents == nullptr) return nullptr;
    const jsize count = env->GetArrayLength(roles);
    if (count <= 0 || count > MAX_MESSAGES || env->GetArrayLength(contents) != count) return nullptr;

    std::vector<std::string> roleTexts(static_cast<size_t>(count));
    std::vector<std::string> contentTexts(static_cast<size_t>(count));
    std::vector<llama_chat_message> messages(static_cast<size_t>(count));
    int64_t total = 0;
    for (jsize index = 0; index < count; index++) {
        auto role = static_cast<jbyteArray>(env->GetObjectArrayElement(roles, index));
        auto content = static_cast<jbyteArray>(env->GetObjectArrayElement(contents, index));
        const bool valid = readBytes(env, role, MAX_ROLE_BYTES, roleTexts[index]) &&
                           readBytes(env, content, MAX_MESSAGE_BYTES, contentTexts[index]);
        if (role != nullptr) env->DeleteLocalRef(role);
        if (content != nullptr) env->DeleteLocalRef(content);
        if (!valid) return nullptr;
        total += static_cast<int64_t>(contentTexts[index].size());
        if (total > MAX_TOTAL_BYTES) return nullptr;
        messages[index].role = roleTexts[index].c_str();
        messages[index].content = contentTexts[index].c_str();
    }

    const char *modelTemplate = llama_model_chat_template(engine->model, nullptr);
    const size_t capacity = static_cast<size_t>(total) * 2 + 4096;
    std::vector<char> buffer(capacity);
    const char *used = modelTemplate;
    int32_t length = -1;
    if (used != nullptr) {
        length = llama_chat_apply_template(used, messages.data(), messages.size(), true, buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    if (length < 0) {
        used = "chatml";
        length = llama_chat_apply_template(used, messages.data(), messages.size(), true, buffer.data(), static_cast<int32_t>(buffer.size()));
    }
    if (length < 0 || length > MAX_PROMPT_BYTES) return nullptr;
    if (static_cast<size_t>(length) > buffer.size()) {
        buffer.resize(static_cast<size_t>(length));
        const int32_t again = llama_chat_apply_template(used, messages.data(), messages.size(), true, buffer.data(), length);
        if (again < 0 || again > length) return nullptr;
        length = again;
    }
    jbyteArray result = env->NewByteArray(length);
    if (result == nullptr) {
        env->ExceptionClear();
        return nullptr;
    }
    env->SetByteArrayRegion(result, 0, length, reinterpret_cast<const jbyte *>(buffer.data()));
    return result;
}

}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nekuz_localmind_LlamaNative_nativeLoad(JNIEnv *, jobject, jint fd, jint contextSize, jint threads) {
    try {
        if (fd < 0) return ERR_ARGS;
        std::lock_guard<std::mutex> guard(engineLock);
        return loadEngine(fd, clampInt(contextSize, MIN_CTX, MAX_CTX), clampInt(threads, MIN_THREADS, MAX_THREADS));
    } catch (const std::bad_alloc &) {
        return ERR_MEMORY;
    } catch (...) {
        return ERR_LOAD;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_nekuz_localmind_LlamaNative_nativeMapped(JNIEnv *, jobject) {
    try {
        std::lock_guard<std::mutex> guard(engineLock);
        return engine && engine->mapped ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nekuz_localmind_LlamaNative_nativeFree(JNIEnv *, jobject) {
    try {
        std::lock_guard<std::mutex> guard(engineLock);
        engine.reset();
    } catch (...) {
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_dev_nekuz_localmind_LlamaNative_nativeFormat(JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents) {
    try {
        std::lock_guard<std::mutex> guard(engineLock);
        return format(env, roles, contents);
    } catch (...) {
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_nekuz_localmind_LlamaNative_nativeGenerate(JNIEnv *env, jobject, jbyteArray prompt, jint maxTokens, jfloat temperature, jobject sink) {
    try {
        if (sink == nullptr) return ERR_ARGS;
        std::string text;
        if (!readBytes(env, prompt, MAX_PROMPT_BYTES, text)) return ERR_ARGS;
        jclass sinkClass = env->GetObjectClass(sink);
        if (sinkClass == nullptr) return ERR_ARGS;
        jmethodID onPiece = env->GetMethodID(sinkClass, "onPiece", "([B)Z");
        env->DeleteLocalRef(sinkClass);
        if (onPiece == nullptr) {
            env->ExceptionClear();
            return ERR_ARGS;
        }
        std::lock_guard<std::mutex> guard(engineLock);
        return generate(env, text, clampInt(maxTokens, 1, MAX_TOKENS), temperature, sink, onPiece);
    } catch (const std::bad_alloc &) {
        return ERR_MEMORY;
    } catch (...) {
        return ERR_INTERNAL;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nekuz_localmind_LlamaNative_nativeCancel(JNIEnv *, jobject) {
    cancelRequested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_dev_nekuz_localmind_LlamaNative_nativeClearCancel(JNIEnv *, jobject) {
    cancelRequested.store(false);
}

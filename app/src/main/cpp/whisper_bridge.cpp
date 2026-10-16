
#include <jni.h>
#include <whisper.h>
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

static void throwError(
    JNIEnv* env,
    const char* message
) {
    jclass cls = env->FindClass(
        "java/lang/IllegalStateException"
    );
    if (cls != nullptr) {
        env->ThrowNew(cls, message);
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_novaplayer_ai_NativeWhisper_transcribe(
    JNIEnv* env,
    jobject,
    jstring modelPath,
    jfloatArray audio,
    jstring language
) {
    if (!modelPath || !audio || !language) {
        throwError(env, "Missing Whisper input");
        return nullptr;
    }

    const char* path = env->GetStringUTFChars(
        modelPath, nullptr
    );
    if (!path) return nullptr;

    std::string model(path);
    env->ReleaseStringUTFChars(modelPath, path);

    const char* lang = env->GetStringUTFChars(
        language, nullptr
    );
    if (!lang) return nullptr;

    std::string selectedLanguage(lang);
    env->ReleaseStringUTFChars(language, lang);

    jsize count = env->GetArrayLength(audio);

    if (count < 16000) {
        throwError(
            env,
            "Audio must contain at least one second"
        );
        return nullptr;
    }

    std::vector<float> samples(
        static_cast<size_t>(count)
    );

    env->GetFloatArrayRegion(
        audio, 0, count, samples.data()
    );

    if (env->ExceptionCheck()) return nullptr;

    whisper_context_params contextParams =
        whisper_context_default_params();

    contextParams.use_gpu = false;

    whisper_context* ctx =
        whisper_init_from_file_with_params(
            model.c_str(), contextParams
        );

    if (!ctx) {
        throwError(env, "Cannot load Whisper model");
        return nullptr;
    }

    whisper_full_params params =
        whisper_full_default_params(
            WHISPER_SAMPLING_GREEDY
        );

    params.n_threads = 4;
    params.translate = false;
    params.no_context = true;
    params.no_timestamps = false;
    params.print_progress = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.language =
        selectedLanguage == "auto"
            ? nullptr
            : selectedLanguage.c_str();

    int status = whisper_full(
        ctx,
        params,
        samples.data(),
        static_cast<int>(samples.size())
    );

    if (status != 0) {
        whisper_free(ctx);
        throwError(env, "Whisper transcription failed");
        return nullptr;
    }

    std::string output;
    int segments = whisper_full_n_segments(ctx);

    for (int i = 0; i < segments; ++i) {
        int64_t start =
            whisper_full_get_segment_t0(ctx, i);
        int64_t end =
            whisper_full_get_segment_t1(ctx, i);

        const char* text =
            whisper_full_get_segment_text(ctx, i);

        output += std::to_string(start);
        output += "|";
        output += std::to_string(end);
        output += "|";
        output += text ? text : "";
        output += "\n";
    }

    whisper_free(ctx);

    return env->NewStringUTF(output.c_str());
}


#include <jni.h>

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_novaplayer_ai_NativeWhisper_isNativeReady(
    JNIEnv* env,
    jobject instance
) {
    return JNI_TRUE;
}

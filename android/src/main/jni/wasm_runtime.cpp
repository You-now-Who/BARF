// WASM runtime JNI bridge — connects Java WasmRuntime to WAMR C API.
// Host functions (move, rotate, stop, log) call back into Java via JNI.
//
// Build with -DWAMR_ENABLED=ON once WAMR is integrated into CMakeLists.txt.
// Without it, all operations log and return failure gracefully.

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <string>
#include <vector>

#define TAG "wasm_runtime"

// ---------------------------------------------------------------------------
// Per-instance state
// ---------------------------------------------------------------------------
struct WasmInstance {
#ifdef WAMR_ENABLED
    wasm_module_t module = nullptr;
    wasm_module_inst_t instance = nullptr;
    wasm_exec_env_t exec_env = nullptr;
#endif
    JavaVM* jvm = nullptr;
    jobject java_ref = nullptr;
    jmethodID mid_move = nullptr;
    jmethodID mid_rotate = nullptr;
    jmethodID mid_stop = nullptr;
    jmethodID mid_log = nullptr;
    bool setup_called = false;
};

static void cache_method_ids(JNIEnv* env, jobject thiz, WasmInstance* inst) {
    jclass cls = env->GetObjectClass(thiz);
    if (!cls) return;
    // These are set via WasmRuntime directly — here for future expansion
    env->DeleteLocalRef(cls);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_barf_runtime_WasmRuntime_nativeInit(JNIEnv* env, jobject thiz) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "nativeInit (stub — WAMR not enabled)");

    WasmInstance* inst = new WasmInstance();
    JavaVM* jvm = nullptr;
    env->GetJavaVM(&jvm);
    inst->jvm = jvm;
    inst->java_ref = env->NewGlobalRef(thiz);
    cache_method_ids(env, thiz, inst);

#ifdef WAMR_ENABLED
    static bool runtime_inited = false;
    if (!runtime_inited) {
        RuntimeInitArgs init_args;
        memset(&init_args, 0, sizeof(RuntimeInitArgs));
        init_args.mem_alloc_type = Alloc_With_Pool;
        init_args.pool_size = 64 * 1024;
        init_args.max_heap_size = 256 * 1024;
        if (!wasm_runtime_full_init(&init_args)) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "WAMR init failed");
            delete inst;
            return 0;
        }
        runtime_inited = true;
    }
#endif

    return (jlong)inst;
}

JNIEXPORT jboolean JNICALL
Java_com_barf_runtime_WasmRuntime_nativeLoad(JNIEnv* env, jobject thiz,
                                              jlong handle, jbyteArray wasmBytes) {
    WasmInstance* inst = (WasmInstance*)handle;
    if (!inst) return JNI_FALSE;

#ifndef WAMR_ENABLED
    __android_log_print(ANDROID_LOG_WARN, TAG, "nativeLoad: WAMR not enabled");
    return JNI_FALSE;
#else
    jsize len = env->GetArrayLength(wasmBytes);
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(wasmBytes, 0, len, (jbyte*)buf.data());

    char error_buf[128];
    inst->module = wasm_runtime_load(buf.data(), len, error_buf, sizeof(error_buf));
    if (!inst->module) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "load failed: %s", error_buf);
        return JNI_FALSE;
    }

    inst->instance = wasm_runtime_instantiate(inst->module, 8*1024, 0, error_buf, sizeof(error_buf));
    if (!inst->instance) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "instantiate failed: %s", error_buf);
        wasm_runtime_unload(inst->module);
        inst->module = nullptr;
        return JNI_FALSE;
    }

    wasm_runtime_set_instance_data(inst->instance, inst);

    inst->exec_env = wasm_runtime_create_exec_env(inst->instance, 8*1024);
    if (!inst->exec_env) {
        wasm_runtime_deinstantiate(inst->instance);
        wasm_runtime_unload(inst->module);
        inst->instance = nullptr;
        inst->module = nullptr;
        return JNI_FALSE;
    }

    NativeSymbol ns[] = {
        {"host_move",   (void*)host_move,   "(IIII)V", nullptr},
        {"host_rotate", (void*)host_rotate, "(I)V",    nullptr},
        {"host_stop",   (void*)host_stop,   "()V",     nullptr},
        {"host_log",    (void*)host_log,    "($)",     nullptr},
    };
    wasm_runtime_register_natives(inst->instance, "env", ns, sizeof(ns)/sizeof(NativeSymbol));

    __android_log_print(ANDROID_LOG_INFO, TAG, "WASM loaded (%d bytes)", len);
    return JNI_TRUE;
#endif
}

JNIEXPORT jboolean JNICALL
Java_com_barf_runtime_WasmRuntime_nativeCallSetup(JNIEnv* env, jobject thiz,
                                                   jlong handle) {
    WasmInstance* inst = (WasmInstance*)handle;
    if (!inst) return JNI_FALSE;
#ifndef WAMR_ENABLED
    return JNI_FALSE;
#else
    wasm_function_inst_t func = wasm_runtime_lookup_function(inst->instance, "setup", nullptr);
    if (func) {
        if (!wasm_runtime_call_wasm(inst->exec_env, func, 0, nullptr))
            return JNI_FALSE;
    }
    inst->setup_called = true;
    return JNI_TRUE;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_barf_runtime_WasmRuntime_nativeCallOnFrame(JNIEnv* env, jobject thiz,
                                                     jlong handle, jstring detectionsJson) {
    WasmInstance* inst = (WasmInstance*)handle;
    if (!inst) return nullptr;
#ifndef WAMR_ENABLED
    return nullptr;
#else
    wasm_function_inst_t func = wasm_runtime_lookup_function(inst->instance, "on_frame", nullptr);
    if (func) wasm_runtime_call_wasm(inst->exec_env, func, 0, nullptr);
    return nullptr;
#endif
}

JNIEXPORT void JNICALL
Java_com_barf_runtime_WasmRuntime_nativeDestroy(JNIEnv* env, jobject thiz,
                                                 jlong handle) {
    WasmInstance* inst = (WasmInstance*)handle;
    if (!inst) return;

#ifdef WAMR_ENABLED
    if (inst->exec_env) wasm_runtime_destroy_exec_env(inst->exec_env);
    if (inst->instance) wasm_runtime_deinstantiate(inst->instance);
    if (inst->module) wasm_runtime_unload(inst->module);
#endif

    if (inst->java_ref) env->DeleteGlobalRef(inst->java_ref);
    delete inst;
}

#ifdef WAMR_ENABLED
// ---------------------------------------------------------------------------
// WAMR host function implementations (only compile when WAMR is enabled)
// ---------------------------------------------------------------------------
static void host_move(wasm_exec_env_t exec_env, int32_t x, int32_t y, int32_t r, int32_t e) {
    WasmInstance* inst = (WasmInstance*)wasm_runtime_get_instance_data(
        wasm_runtime_get_module_inst(exec_env));
    if (!inst || !inst->jvm || !inst->java_ref) return;
    JNIEnv* env; bool att = false;
    if (inst->jvm->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_EDETACHED) {
        if (inst->jvm->AttachCurrentThread(&env, nullptr) == 0) att = true; else return;
    }
    if (env && inst->mid_move) env->CallVoidMethod(inst->java_ref, inst->mid_move, x, y, r, e);
    if (att) inst->jvm->DetachCurrentThread();
}

static void host_rotate(wasm_exec_env_t exec_env, int32_t r) {
    WasmInstance* inst = (WasmInstance*)wasm_runtime_get_instance_data(
        wasm_runtime_get_module_inst(exec_env));
    if (!inst || !inst->jvm || !inst->java_ref) return;
    JNIEnv* env; bool att = false;
    if (inst->jvm->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_EDETACHED) {
        if (inst->jvm->AttachCurrentThread(&env, nullptr) == 0) att = true; else return;
    }
    if (env && inst->mid_rotate) env->CallVoidMethod(inst->java_ref, inst->mid_rotate, r);
    if (att) inst->jvm->DetachCurrentThread();
}

static void host_stop(wasm_exec_env_t exec_env) {
    WasmInstance* inst = (WasmInstance*)wasm_runtime_get_instance_data(
        wasm_runtime_get_module_inst(exec_env));
    if (!inst || !inst->jvm || !inst->java_ref) return;
    JNIEnv* env; bool att = false;
    if (inst->jvm->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_EDETACHED) {
        if (inst->jvm->AttachCurrentThread(&env, nullptr) == 0) att = true; else return;
    }
    if (env && inst->mid_stop) env->CallVoidMethod(inst->java_ref, inst->mid_stop);
    if (att) inst->jvm->DetachCurrentThread();
}

static void host_log(wasm_exec_env_t exec_env, const char* msg) {
    WasmInstance* inst = (WasmInstance*)wasm_runtime_get_instance_data(
        wasm_runtime_get_module_inst(exec_env));
    if (!inst) return;
    __android_log_print(ANDROID_LOG_INFO, TAG, "[WASM] %s", msg ? msg : "");
    if (!inst->jvm || !inst->java_ref) return;
    JNIEnv* env; bool att = false;
    if (inst->jvm->GetEnv((void**)&env, JNI_VERSION_1_4) == JNI_EDETACHED) {
        if (inst->jvm->AttachCurrentThread(&env, nullptr) == 0) att = true; else return;
    }
    if (env && inst->mid_log && msg) {
        jstring jmsg = env->NewStringUTF(msg);
        env->CallVoidMethod(inst->java_ref, inst->mid_log, jmsg);
        env->DeleteLocalRef(jmsg);
    }
    if (att) inst->jvm->DetachCurrentThread();
}
#endif // WAMR_ENABLED

} // extern "C"

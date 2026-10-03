#define _POSIX_C_SOURCE 200809L

#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct ores_carrier_pool ores_carrier_pool;

typedef struct {
    ores_carrier_pool *pool;
    int slot;
    char *name;
} ores_carrier_arg;

struct ores_carrier_pool {
    JavaVM *jvm;
    jobject executor;
    jmethodID carrier_loop;
    pthread_t *threads;
    ores_carrier_arg *args;
    int max_threads;
    int desired_threads;
    int started;
    int shutdown;
    pthread_mutex_t mutex;
    pthread_cond_t condition;
};

static void throw_illegal_state(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, message);
}

static char *copy_thread_name(const char *prefix, int slot) {
    size_t prefix_len = strlen(prefix);
    size_t size = prefix_len + 32;
    char *name = (char *)calloc(size, 1);
    if (name == NULL) return NULL;
    snprintf(name, size, "%s%d", prefix, slot + 1);
    return name;
}

static void *carrier_main(void *raw) {
    ores_carrier_arg *arg = (ores_carrier_arg *)raw;
    ores_carrier_pool *pool = arg->pool;

    pthread_mutex_lock(&pool->mutex);
    while (!pool->started && !pool->shutdown) {
        pthread_cond_wait(&pool->condition, &pool->mutex);
    }
    int should_stop = pool->shutdown;
    pthread_mutex_unlock(&pool->mutex);
    if (should_stop) return NULL;

    JNIEnv *env = NULL;
    JavaVMAttachArgs attach;
    memset(&attach, 0, sizeof(attach));
    attach.version = JNI_VERSION_1_8;
    attach.name = arg->name;
    attach.group = NULL;

    jint status = (*pool->jvm)->AttachCurrentThreadAsDaemon(
            pool->jvm, (void **)&env, &attach);
    if (status != JNI_OK || env == NULL) return NULL;

    (*env)->CallVoidMethod(env, pool->executor, pool->carrier_loop, (jint)arg->slot);

    if ((*env)->ExceptionCheck(env)) {
        // Surface an unexpected executor-boundary failure before detaching.
        // Actor turn failures should normally be contained in Java.
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }

    (*pool->jvm)->DetachCurrentThread(pool->jvm);
    return NULL;
}

static void free_pool(JNIEnv *env, ores_carrier_pool *pool) {
    if (pool == NULL) return;
    if (pool->executor != NULL) (*env)->DeleteGlobalRef(env, pool->executor);
    if (pool->args != NULL) {
        for (int i = 0; i < pool->max_threads; i++) free(pool->args[i].name);
    }
    free(pool->args);
    free(pool->threads);
    pthread_cond_destroy(&pool->condition);
    pthread_mutex_destroy(&pool->mutex);
    free(pool);
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeCreate(
        JNIEnv *env,
        jclass cls,
        jobject executor,
        jint max_threads,
        jint desired_threads,
        jstring thread_prefix) {
    (void)cls;
    if (executor == NULL || thread_prefix == NULL) {
        throw_illegal_state(env, "native carrier executor/prefix cannot be null");
        return 0;
    }
    if (max_threads <= 0 || desired_threads <= 0 || desired_threads > max_threads) {
        throw_illegal_state(env, "invalid native carrier thread counts");
        return 0;
    }

    ores_carrier_pool *pool = (ores_carrier_pool *)calloc(1, sizeof(*pool));
    if (pool == NULL) {
        throw_illegal_state(env, "failed to allocate native carrier pool");
        return 0;
    }
    pool->max_threads = (int)max_threads;
    pool->desired_threads = (int)desired_threads;
    pthread_mutex_init(&pool->mutex, NULL);
    pthread_cond_init(&pool->condition, NULL);

    if ((*env)->GetJavaVM(env, &pool->jvm) != JNI_OK || pool->jvm == NULL) {
        free_pool(env, pool);
        throw_illegal_state(env, "JNI GetJavaVM failed");
        return 0;
    }

    pool->executor = (*env)->NewGlobalRef(env, executor);
    if (pool->executor == NULL) {
        free_pool(env, pool);
        throw_illegal_state(env, "failed to root native carrier executor");
        return 0;
    }

    jclass executor_class = (*env)->GetObjectClass(env, executor);
    if (executor_class == NULL) {
        free_pool(env, pool);
        return 0;
    }
    pool->carrier_loop = (*env)->GetMethodID(env, executor_class, "nativeCarrierLoop", "(I)V");
    (*env)->DeleteLocalRef(env, executor_class);
    if (pool->carrier_loop == NULL) {
        free_pool(env, pool);
        throw_illegal_state(env, "nativeCarrierLoop(int) JNI callback is missing");
        return 0;
    }

    const char *prefix = (*env)->GetStringUTFChars(env, thread_prefix, NULL);
    if (prefix == NULL) {
        free_pool(env, pool);
        return 0;
    }

    pool->threads = (pthread_t *)calloc((size_t)max_threads, sizeof(pthread_t));
    pool->args = (ores_carrier_arg *)calloc((size_t)max_threads, sizeof(ores_carrier_arg));
    if (pool->threads == NULL || pool->args == NULL) {
        (*env)->ReleaseStringUTFChars(env, thread_prefix, prefix);
        free_pool(env, pool);
        throw_illegal_state(env, "failed to allocate native carrier slots");
        return 0;
    }

    int created = 0;
    for (int i = 0; i < max_threads; i++) {
        pool->args[i].pool = pool;
        pool->args[i].slot = i;
        pool->args[i].name = copy_thread_name(prefix, i);
        if (pool->args[i].name == NULL
                || pthread_create(&pool->threads[i], NULL, carrier_main, &pool->args[i]) != 0) {
            pthread_mutex_lock(&pool->mutex);
            pool->shutdown = 1;
            pool->started = 1;
            pthread_cond_broadcast(&pool->condition);
            pthread_mutex_unlock(&pool->mutex);
            for (int j = 0; j < created; j++) pthread_join(pool->threads[j], NULL);
            (*env)->ReleaseStringUTFChars(env, thread_prefix, prefix);
            free_pool(env, pool);
            throw_illegal_state(env, "pthread_create failed for Oreslang carrier");
            return 0;
        }
        created++;
    }

    (*env)->ReleaseStringUTFChars(env, thread_prefix, prefix);
    return (jlong)(intptr_t)pool;
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeStart(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)env;
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL) return;
    pthread_mutex_lock(&pool->mutex);
    pool->started = 1;
    pthread_cond_broadcast(&pool->condition);
    pthread_mutex_unlock(&pool->mutex);
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeSetDesired(
        JNIEnv *env, jclass cls, jlong handle, jint desired_threads) {
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL) return;
    if (desired_threads <= 0 || desired_threads > pool->max_threads) {
        throw_illegal_state(env, "native desired carrier count is out of bounds");
        return;
    }
    pthread_mutex_lock(&pool->mutex);
    pool->desired_threads = (int)desired_threads;
    pthread_cond_broadcast(&pool->condition);
    pthread_mutex_unlock(&pool->mutex);
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeAwaitEnabled(
        JNIEnv *env, jclass cls, jlong handle, jint slot) {
    (void)env;
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL) return;
    pthread_mutex_lock(&pool->mutex);
    while (!pool->shutdown && slot >= pool->desired_threads) {
        pthread_cond_wait(&pool->condition, &pool->mutex);
    }
    pthread_mutex_unlock(&pool->mutex);
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeShutdown(
        JNIEnv *env, jclass cls, jlong handle) {
    (void)cls;
    ores_carrier_pool *pool = (ores_carrier_pool *)(intptr_t)handle;
    if (pool == NULL) return;

    pthread_mutex_lock(&pool->mutex);
    if (!pool->shutdown) {
        pool->shutdown = 1;
        pool->started = 1;
        pthread_cond_broadcast(&pool->condition);
    }
    pthread_mutex_unlock(&pool->mutex);

    pthread_t self = pthread_self();
    for (int i = 0; i < pool->max_threads; i++) {
        if (!pthread_equal(self, pool->threads[i])) {
            pthread_join(pool->threads[i], NULL);
        }
    }
    free_pool(env, pool);
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_NativeCarrierExecutor_nativeCurrentThreadId(
        JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jlong)(uintptr_t)pthread_self();
}


/* ------------------------------------------------------------------------- */
/* Privileged standalone OresThread support.                                 */
/* ------------------------------------------------------------------------- */

typedef struct {
    JavaVM *jvm;
    jobject thread_object;
    jmethodID run_method;
    char *name;
} ores_standalone_thread;

static void *standalone_thread_main(void *raw) {
    ores_standalone_thread *thread = (ores_standalone_thread *)raw;
    JNIEnv *env = NULL;
    JavaVMAttachArgs attach;
    memset(&attach, 0, sizeof(attach));
    attach.version = JNI_VERSION_1_8;
    attach.name = thread->name;
    attach.group = NULL;

    // Explicit OresThread instances are platform threads, not actor carriers,
    // so attach as a normal (non-daemon) JVM thread.
    jint status = (*thread->jvm)->AttachCurrentThread(
            thread->jvm, (void **)&env, &attach);
    if (status == JNI_OK && env != NULL) {
        (*env)->CallVoidMethod(env, thread->thread_object, thread->run_method);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionDescribe(env);
            (*env)->ExceptionClear(env);
        }
        (*env)->DeleteGlobalRef(env, thread->thread_object);
        (*thread->jvm)->DetachCurrentThread(thread->jvm);
    }

    free(thread->name);
    free(thread);
    return NULL;
}

JNIEXPORT void JNICALL
Java_dev_oreslang_runtime_OresThread_nativeStart(
        JNIEnv *env, jclass cls, jobject thread_object, jstring name) {
    (void)cls;
    if (thread_object == NULL || name == NULL) {
        throw_illegal_state(env, "OresThread native start requires a thread object and name");
        return;
    }

    ores_standalone_thread *thread =
            (ores_standalone_thread *)calloc(1, sizeof(*thread));
    if (thread == NULL) {
        throw_illegal_state(env, "failed to allocate OresThread native state");
        return;
    }

    if ((*env)->GetJavaVM(env, &thread->jvm) != JNI_OK || thread->jvm == NULL) {
        free(thread);
        throw_illegal_state(env, "JNI GetJavaVM failed for OresThread");
        return;
    }

    thread->thread_object = (*env)->NewGlobalRef(env, thread_object);
    if (thread->thread_object == NULL) {
        free(thread);
        throw_illegal_state(env, "failed to root OresThread while native pthread is running");
        return;
    }

    jclass thread_class = (*env)->GetObjectClass(env, thread_object);
    if (thread_class == NULL) {
        (*env)->DeleteGlobalRef(env, thread->thread_object);
        free(thread);
        return;
    }
    thread->run_method = (*env)->GetMethodID(env, thread_class, "nativeRun", "()V");
    (*env)->DeleteLocalRef(env, thread_class);
    if (thread->run_method == NULL) {
        (*env)->DeleteGlobalRef(env, thread->thread_object);
        free(thread);
        throw_illegal_state(env, "OresThread.nativeRun() JNI callback is missing");
        return;
    }

    const char *raw_name = (*env)->GetStringUTFChars(env, name, NULL);
    if (raw_name == NULL) {
        (*env)->DeleteGlobalRef(env, thread->thread_object);
        free(thread);
        return;
    }
    thread->name = strdup(raw_name);
    (*env)->ReleaseStringUTFChars(env, name, raw_name);
    if (thread->name == NULL) {
        (*env)->DeleteGlobalRef(env, thread->thread_object);
        free(thread);
        throw_illegal_state(env, "failed to copy OresThread name");
        return;
    }

    pthread_t native_thread;
    int create_status = pthread_create(
            &native_thread, NULL, standalone_thread_main, thread);
    if (create_status != 0) {
        (*env)->DeleteGlobalRef(env, thread->thread_object);
        free(thread->name);
        free(thread);
        throw_illegal_state(env, "pthread_create failed for OresThread");
        return;
    }
    // Completion/join is tracked by the Java peer's CompletableFuture. Detach
    // the pthread so native resources are reclaimed even if user code never
    // calls join().
    pthread_detach(native_thread);
}

JNIEXPORT jlong JNICALL
Java_dev_oreslang_runtime_OresThread_nativeCurrentThreadId(
        JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    return (jlong)(uintptr_t)pthread_self();
}

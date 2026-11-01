// The other exact way (2026-11-01): a music player with its own DAC inside (FiiO's, HiBy's) has no USB DAC to
// drive, so its headphone or line output is asked for exclusively by AAudio, at the song's own rate: no mixer
// between, as BitChord's "built-in DAC" does. Android may say no, or give another rate: then it's closed and the
// player goes Android's shared way, and the signal path says which it was.

#include <jni.h>
#include <aaudio/AAudio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "ink-exclusive", __VA_ARGS__)

typedef struct {
    AAudioStream *s;
    int channels, in_float;
    float gain;
    float *scratch; int scratch_frames;
    long long base;      // frames read before the last flush
} ex_t;

static ex_t *of(jlong h) { return (ex_t *) (intptr_t) h; }

// opened exclusively at exactly this rate, or nothing (0)
JNIEXPORT jlong JNICALL Java_app_ink_music_Exclusive_open(JNIEnv *e, jclass c, jint rate, jint channels, jboolean inFloat, jint device) {
    AAudioStreamBuilder *b;
    if (AAudio_createStreamBuilder(&b) != AAUDIO_OK) return 0;
    AAudioStreamBuilder_setSharingMode(b, AAUDIO_SHARING_MODE_EXCLUSIVE);
    AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    AAudioStreamBuilder_setSampleRate(b, rate);
    AAudioStreamBuilder_setChannelCount(b, channels);
    AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_FLOAT);
    AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_MEDIA);
    AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_MUSIC);
    if (device > 0) AAudioStreamBuilder_setDeviceId(b, device);
    AAudioStream *s = NULL;
    aaudio_result_t r = AAudioStreamBuilder_openStream(b, &s);
    AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK || !s) { LOG("open %d Hz: %s", rate, AAudio_convertResultToText(r)); return 0; }
    int got = AAudioStream_getSampleRate(s), mode = AAudioStream_getSharingMode(s);
    if (mode != AAUDIO_SHARING_MODE_EXCLUSIVE || got != rate) {
        LOG("asked %d Hz exclusive, given %d Hz %s: not taken", rate, got, mode == AAUDIO_SHARING_MODE_EXCLUSIVE ? "exclusive" : "shared");
        AAudioStream_close(s);
        return 0;
    }
    // all the room it has (the player writes ahead)
    AAudioStream_setBufferSizeInFrames(s, AAudioStream_getBufferCapacityInFrames(s));
    ex_t *x = calloc(1, sizeof(ex_t));
    x->s = s; x->channels = channels; x->in_float = inFloat; x->gain = 1.0f;
    LOG("exclusive at %d Hz, %d ch, device %d, buffer %d frames", rate, channels, AAudioStream_getDeviceId(s), AAudioStream_getBufferCapacityInFrames(s));
    return (jlong) (intptr_t) x;
}

// as much of len bytes as fits now (never waiting): bytes taken; negative an error
JNIEXPORT jint JNICALL Java_app_ink_music_Exclusive_write(JNIEnv *e, jclass c, jlong h, jobject buf, jint off, jint len) {
    ex_t *x = of(h);
    unsigned char *src = (*e)->GetDirectBufferAddress(e, buf);
    if (!src) return -1;
    src += off;
    int in_frame = (x->in_float ? 4 : 2) * x->channels;
    int frames = len / in_frame;
    if (frames <= 0) return 0;
    if (frames > x->scratch_frames) { free(x->scratch); x->scratch = malloc((size_t) frames * x->channels * sizeof(float)); x->scratch_frames = frames; }
    // to float, the same value (16 bit exactly, float as it is), times the player's volume (1: untouched)
    for (int i = 0; i < frames * x->channels; i++) {
        float v = x->in_float ? ((float *) src)[i] : ((short *) src)[i] / 32768.0f;
        x->scratch[i] = x->gain == 1.0f ? v : v * x->gain;
    }
    aaudio_result_t n = AAudioStream_write(x->s, x->scratch, frames, 0);
    if (n < 0) { LOG("write: %s", AAudio_convertResultToText(n)); return n; }
    return n * in_frame;
}

JNIEXPORT void JNICALL Java_app_ink_music_Exclusive_play(JNIEnv *e, jclass c, jlong h, jboolean on) {
    if (on) AAudioStream_requestStart(of(h)->s); else AAudioStream_requestPause(of(h)->s);
}
JNIEXPORT void JNICALL Java_app_ink_music_Exclusive_flush(JNIEnv *e, jclass c, jlong h) {
    ex_t *x = of(h);
    AAudioStream_requestFlush(x->s);
    x->base = AAudioStream_getFramesRead(x->s);
}
// frames the output has taken since the last flush
JNIEXPORT jlong JNICALL Java_app_ink_music_Exclusive_played(JNIEnv *e, jclass c, jlong h) {
    ex_t *x = of(h); long long n = AAudioStream_getFramesRead(x->s) - x->base; return n < 0 ? 0 : n;
}
JNIEXPORT jint JNICALL Java_app_ink_music_Exclusive_capacity(JNIEnv *e, jclass c, jlong h) { return AAudioStream_getBufferCapacityInFrames(of(h)->s); }
JNIEXPORT jint JNICALL Java_app_ink_music_Exclusive_underruns(JNIEnv *e, jclass c, jlong h) { return AAudioStream_getXRunCount(of(h)->s); }
JNIEXPORT void JNICALL Java_app_ink_music_Exclusive_gain(JNIEnv *e, jclass c, jlong h, jfloat g) { of(h)->gain = g; }
JNIEXPORT void JNICALL Java_app_ink_music_Exclusive_close(JNIEnv *e, jclass c, jlong h) {
    ex_t *x = of(h);
    AAudioStream_requestStop(x->s);
    AAudioStream_close(x->s);
    free(x->scratch);
    free(x);
}

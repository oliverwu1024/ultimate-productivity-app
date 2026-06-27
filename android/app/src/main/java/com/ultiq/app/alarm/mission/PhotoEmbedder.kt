package com.ultiq.app.alarm.mission

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder.ImageEmbedderOptions
import kotlin.math.sqrt

/**
 * On-device image embedder for the photo dismiss mission (§8.9, v2.18+).
 *
 * Replaces the old 64-bit DCT perceptual hash. pHash is a *near-duplicate*
 * detector — it assumes the same image in the same layout, so re-photographing
 * a physical scene at a slightly different angle / distance / brightness flipped
 * enough bits to blow past the Hamming tolerance and reject a legitimate frame.
 *
 * MobileNet-V3-small (via MediaPipe Tasks Vision) instead maps each frame to a
 * 1024-d L2-normalised feature vector; two views of the same scene land close
 * together, so we match by **cosine similarity**, which is robust to moderate
 * viewpoint and lighting changes. Inference is on-device (CPU) — imagery never
 * leaves the phone, same posture as the YAMNet sleep classifier.
 *
 * The model `mobilenet_v3_small.tflite` (~4 MB) lives in app/src/main/assets/.
 */
object PhotoEmbedder {

    private const val MODEL_ASSET = "mobilenet_v3_small.tflite"

    // Lazily built, then reused for the lifetime of the process. Creation loads
    // the ~4 MB model, so we avoid paying that per capture. embed() calls within
    // a single mission are sequential, so the shared instance is never hit
    // concurrently.
    @Volatile
    private var embedder: ImageEmbedder? = null

    private fun instance(context: Context): ImageEmbedder =
        embedder ?: synchronized(this) {
            embedder ?: ImageEmbedder.createFromOptions(
                context.applicationContext,
                ImageEmbedderOptions.builder()
                    .setBaseOptions(
                        BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build(),
                    )
                    .setL2Normalize(true) // unit vectors → stable cosine similarity
                    .setQuantize(false)
                    .build(),
            ).also { embedder = it }
        }

    /**
     * Compute the L2-normalised embedding of [bitmap]. Call off the main thread.
     * Throws if the model can't be loaded; callers fall back to the math mission.
     */
    fun embed(context: Context, bitmap: Bitmap): FloatArray {
        val image = BitmapImageBuilder(bitmap).build()
        val result = instance(context).embed(image)
        return result.embeddingResult().embeddings().first().floatEmbedding()
    }

    /**
     * Cosine similarity of two embeddings, in [-1, 1] (1 = identical direction).
     * Returns -1 for empty / mismatched vectors so they never pass a threshold.
     */
    fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return -1f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        if (na == 0.0 || nb == 0.0) return -1f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }
}

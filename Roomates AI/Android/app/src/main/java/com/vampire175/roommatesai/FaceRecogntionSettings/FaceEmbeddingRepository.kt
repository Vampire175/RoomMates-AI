package com.vampire175.roommatesai.FaceRecognition

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists face embeddings as JSON in SharedPreferences.
 *
 * ═══════════════════════════════════════════════════════
 *  ROOT CAUSE FIX — Bug that made face recognition never run:
 *
 *  The broken version had this import:
 *    import android.provider.ContactsContract.CommonDataKinds.StructuredName.PREFIX
 *
 *  ContactsContract.CommonDataKinds.StructuredName.PREFIX == "data4"
 *  (it is a contacts database column name, nothing to do with faces).
 *
 *  So every face was saved under "data4Alice" instead of "face_Alice".
 *  ensureCacheLoaded() filtered for keys starting with "data4", found nothing,
 *  hasFaces() ALWAYS returned false, and CameraFragment's shouldRunFace was
 *  ALWAYS false → face recognition code never executed even once.
 *
 *  Fix: removed the wrong import. Companion object now correctly defines
 *       PREFIX = "face_"  (was accidentally commented out in the broken version).
 * ═══════════════════════════════════════════════════════
 *
 * Other features preserved:
 *  1. Singleton cache in companion object → shared across all instances.
 *  2. Multi-sample storage (up to 5 per person), averaged at query time.
 *  3. Backward-compatible loader for old flat-array format.
 */
class FaceEmbeddingRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext
            .getSharedPreferences("roommates_ai_faces", Context.MODE_PRIVATE)

    companion object {
        // ── IMPORTANT: Define PREFIX here. Never import it from ContactsContract.
        private const val PREFIX = "face_"

        // Singleton cache – shared across CameraFragment and FaceRegistrationActivity
        private val sampleCache = mutableMapOf<String, MutableList<FloatArray>>()

        @Volatile private var cacheLoaded = false
        private val lock = Any()
    }

    // ------------------------------------------------------------------
    // Write
    // ------------------------------------------------------------------

    fun saveEmbedding(
        name: String,
        embedding: FloatArray,
        maxSamplesPerPerson: Int = 5
    ) {
        synchronized(lock) {
            ensureCacheLoaded()
            val list = sampleCache.getOrPut(name) { mutableListOf() }
            if (list.size >= maxSamplesPerPerson) list.removeAt(0)
            list.add(embedding)
            persistName(name, list)
        }
    }

    // ------------------------------------------------------------------
    // Read
    // ------------------------------------------------------------------

    fun getAllEmbeddings(): Map<String, FloatArray> {
        synchronized(lock) {
            ensureCacheLoaded()
            return sampleCache.mapValues { (_, samples) -> average(samples) }
        }
    }

    fun getSampleCount(name: String): Int {
        synchronized(lock) {
            ensureCacheLoaded()
            return sampleCache[name]?.size ?: 0
        }
    }

    fun getAllNames(): List<String> {
        synchronized(lock) {
            ensureCacheLoaded()
            return sampleCache.keys.sorted()
        }
    }

    fun hasFaces(): Boolean {
        synchronized(lock) {
            ensureCacheLoaded()
            return sampleCache.isNotEmpty()
        }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    fun deleteFace(name: String) {
        synchronized(lock) {
            ensureCacheLoaded()
            prefs.edit().remove(prefKey(name)).apply()
            sampleCache.remove(name)
        }
    }

    fun deleteAll() {
        synchronized(lock) {
            prefs.edit().clear().apply()
            sampleCache.clear()
            cacheLoaded = false
        }
    }

    // ------------------------------------------------------------------
    // Export (debug)
    // ------------------------------------------------------------------

    fun exportJson(): String {
        synchronized(lock) {
            ensureCacheLoaded()
            val root = JSONObject()
            sampleCache.forEach { (name, samples) ->
                val outer = JSONArray()
                samples.forEach { emb ->
                    outer.put(JSONArray().apply { emb.forEach { put(it.toDouble()) } })
                }
                root.put(name, outer)
            }
            return root.toString(2)
        }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------

    private fun ensureCacheLoaded() {
        if (cacheLoaded) return
        sampleCache.clear()

        prefs.all
            .filter { it.key.startsWith(PREFIX) }
            .forEach { (key, value) ->
                val name = key.removePrefix(PREFIX)
                try {
                    val outer = JSONArray(value as String)
                    if (outer.length() == 0) return@forEach

                    val samples = mutableListOf<FloatArray>()

                    if (outer.get(0) is JSONArray) {
                        for (i in 0 until outer.length()) {
                            val inner = outer.getJSONArray(i)
                            samples.add(FloatArray(inner.length()) { j ->
                                inner.getDouble(j).toFloat()
                            })
                        }
                    } else {
                        samples.add(FloatArray(outer.length()) { i ->
                            outer.getDouble(i).toFloat()
                        })
                    }

                    sampleCache[name] = samples
                } catch (_: Exception) { }
            }

        cacheLoaded = true
    }

    private fun persistName(name: String, samples: List<FloatArray>) {
        val outer = JSONArray()
        samples.forEach { emb ->
            outer.put(JSONArray().apply { emb.forEach { put(it.toDouble()) } })
        }
        prefs.edit().putString(prefKey(name), outer.toString()).apply()
    }

    private fun average(samples: List<FloatArray>): FloatArray {
        if (samples.size == 1) return samples[0]
        val size   = samples[0].size
        val result = FloatArray(size)
        for (emb in samples) {
            for (i in 0 until size) result[i] += emb[i]
        }
        val n = samples.size.toFloat()
        for (i in 0 until size) result[i] /= n
        return result
    }

    private fun prefKey(name: String) = "$PREFIX$name"
}
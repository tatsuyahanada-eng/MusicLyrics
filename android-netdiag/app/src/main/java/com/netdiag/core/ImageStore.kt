package com.netdiag.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Stores on-site device photos as files in the app's private storage and keeps
 * a persisted list of their paths. Shown in the Records screen and embedded in
 * the PDF export.
 */
object ImageStore {

    private var prefs: android.content.SharedPreferences? = null
    private val _paths = MutableStateFlow<List<String>>(emptyList())
    val paths: StateFlow<List<String>> = _paths.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("netdiag_images", Context.MODE_PRIVATE)
        prefs = p
        // Keep only files that still exist.
        _paths.value = (p.getString("paths", "") ?: "")
            .lineSequence().filter { it.isNotBlank() && File(it).exists() }.toList()
    }

    fun imagesDir(context: Context): File =
        File(context.applicationContext.filesDir, "device_images").apply { mkdirs() }

    fun newImageFile(context: Context): File =
        File(imagesDir(context), "img_${System.currentTimeMillis()}.jpg")

    fun add(path: String) {
        if (!File(path).exists()) return
        _paths.value = _paths.value + path
        persist()
    }

    fun remove(path: String) {
        runCatching { File(path).delete() }
        _paths.value = _paths.value.filterNot { it == path }
        persist()
    }

    private fun persist() {
        prefs?.edit()?.putString("paths", _paths.value.joinToString("\n"))?.apply()
    }
}

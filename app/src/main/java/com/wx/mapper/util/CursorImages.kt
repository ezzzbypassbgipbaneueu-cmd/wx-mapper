package com.wx.mapper.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Galeria de cursores guardada dentro do app (filesDir/cursors). As imagens ficam salvas
 * mesmo depois de fechar o app; é possível guardar várias e trocar entre elas.
 */
object CursorImages {
    private const val MAX_SIDE = 512

    private fun dir(context: Context): File =
        File(context.filesDir, "cursors").apply { mkdirs() }

    fun list(context: Context): List<File> {
        val gallery = dir(context).listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty()
        // imagens da versão anterior (ficavam direto em filesDir)
        val legacy = context.filesDir.listFiles { f ->
            f.isFile && f.name.startsWith("cursor_custom_") && f.name.endsWith(".png")
        }.orEmpty()
        return (gallery + legacy).sortedBy { it.lastModified() }
    }

    /** Copia a imagem escolhida (reduzida e em PNG) para a galeria e devolve o arquivo. */
    fun import(context: Context, uri: Uri): File? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > MAX_SIDE || bounds.outHeight / sample > MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        val file = File(dir(context), "cursor_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file
    }.getOrNull()

    fun delete(file: File): Boolean = runCatching { file.delete() }.getOrDefault(false)
}

package com.yijian.translate

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AppStore(private val context: Context) {
    companion object { private val stateLock = Any() }
    private val file = AtomicFile(File(context.filesDir, "state.json"))
    private val secrets = context.getSharedPreferences("encrypted_model_keys", Context.MODE_PRIVATE)
    var recoveryMessage: String? = null
        private set

    fun load(): AppData = synchronized(stateLock) {
        if (!file.baseFile.exists()) return@synchronized AppData()
        try { AppData.from(JSONObject(file.openRead().bufferedReader().use { it.readText() })) }
        catch (_: Exception) {
            file.baseFile.copyTo(File(context.filesDir, "state-recovery-${System.currentTimeMillis()}.json"))
            recoveryMessage = "本地数据读取失败，原文件已保留恢复副本。"
            AppData()
        }
    }

    fun save(data: AppData) = synchronized(stateLock) {
        val stream = file.startWrite()
        try { stream.write(data.json().toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey("yijian_api_keys", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("yijian_api_keys", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    fun saveKey(modelId: String, value: String) {
        if (value.isBlank()) { secrets.edit().remove(modelId).commit(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val text = Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        check(secrets.edit().putString(modelId, text).commit()) { "密钥保存失败" }
    }

    fun key(modelId: String): String {
        val text = secrets.getString(modelId, null) ?: return ""
        return try {
            val bytes = Base64.decode(text, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))) }
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) { throw IllegalStateException("已保存的密钥无法解密，请在模型设置中重新填写") }
    }

    fun removeKey(modelId: String) { secrets.edit().remove(modelId).commit() }

    fun importImage(uri: Uri): String {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "无法读取这张图片，请选择常见的 JPG、PNG 或 WebP 图片" }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2400) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
            ?: throw IllegalStateException("图片读取失败")
        val orientation = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) } }.getOrNull()
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(270f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(270f)
        }
        val rotated = if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        val scale = minOf(1f, 1800f / maxOf(rotated.width, rotated.height))
        val resized = if (scale == 1f) rotated else Bitmap.createScaledBitmap(rotated, (rotated.width * scale).toInt(), (rotated.height * scale).toInt(), true)
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val destination = File(directory, "${newId()}.jpg")
        destination.outputStream().use { output -> check(resized.compress(Bitmap.CompressFormat.JPEG, 88, output)) }
        if (resized !== rotated) resized.recycle()
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        return destination.absolutePath
    }

    fun cleanAttachments(data: AppData) {
        val referenced = data.records.mapNotNull { it.request.image }.toSet() + listOfNotNull(data.draftImage)
        File(context.filesDir, "attachments").listFiles()?.filter { it.absolutePath !in referenced }?.forEach { it.delete() }
    }
}

package pet.toasty.vmpowerphone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Profile pictures are stored only on this phone, one small square JPEG per account. */
class AvatarStore(private val context: Context) {
    private val dir = File(context.filesDir, "avatars").apply { mkdirs() }
    private val syncPrefs = context.getSharedPreferences("avatar_sync", Context.MODE_PRIVATE)

    private fun file(key: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(dir, hash.take(32) + ".jpg")
    }

    fun load(key: String): ImageBitmap? {
        val f = file(key)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap()
    }

    fun delete(key: String) {
        file(key).delete()
    }

    fun readBytes(key: String): ByteArray? = file(key).takeIf { it.exists() }?.readBytes()

    fun saveBytes(key: String, bytes: ByteArray) {
        file(key).writeBytes(bytes)
    }

    /** True when the picture on this phone matches the server's copy. */
    fun isSynced(key: String): Boolean = syncPrefs.getBoolean(file(key).name, false)

    fun markSynced(key: String) {
        syncPrefs.edit().putBoolean(file(key).name, true).apply()
    }

    private fun markUnsynced(key: String) {
        syncPrefs.edit().putBoolean(file(key).name, false).apply()
    }

    fun save(key: String, uri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= 512 && bounds.outHeight / (sample * 2) >= 512) {
                sample *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val src = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return false

            val rotation = try {
                resolver.openInputStream(uri)?.use { stream ->
                    when (
                        ExifInterface(stream).getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                    ) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            } catch (e: Exception) {
                0f
            }

            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(
                src, (src.width - side) / 2, (src.height - side) / 2, side, side
            )
            val matrix = Matrix().apply {
                postRotate(rotation)
                postScale(256f / side, 256f / side)
            }
            val result = Bitmap.createBitmap(square, 0, 0, side, side, matrix, true)

            file(key).outputStream().use { out ->
                result.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
            markUnsynced(key)
            true
        } catch (e: Exception) {
            false
        }
    }
}

fun avatarKey(a: Account): String = a.server + "|" + a.label

@Composable
fun Avatar(label: String, image: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(
                label.firstOrNull()?.uppercase() ?: "?",
                style = MaterialTheme.typography.titleLarge.copy(fontSize = (size.value * 0.45f).sp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun AccountAvatar(
    store: AvatarStore,
    tick: Int,
    account: Account,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val key = avatarKey(account)
    val image = remember(key, tick) { store.load(key) }
    Avatar(account.label, image, size, modifier)
}

// ---------- Server sync ----------

private suspend fun avatarCall(
    method: String,
    server: String,
    token: String,
    body: ByteArray? = null
): Pair<Int, ByteArray> = withContext(Dispatchers.IO) {
    val conn = URL("$server/me/avatar").openConnection() as HttpURLConnection
    try {
        conn.requestMethod = method
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        code to (stream?.use { it.readBytes() } ?: ByteArray(0))
    } finally {
        conn.disconnect()
    }
}

suspend fun uploadAvatar(server: String, token: String, bytes: ByteArray): Boolean =
    try { avatarCall("PUT", server, token, bytes).first in 200..299 } catch (e: Exception) { false }

suspend fun deleteAvatarOnServer(server: String, token: String): Boolean =
    try { avatarCall("DELETE", server, token).first in 200..299 } catch (e: Exception) { false }

/**
 * Keeps this phone's copy in step with the server. The server copy wins,
 * except for a picture you picked here that hasn't been uploaded yet.
 * Servers that don't support avatars are left alone.
 */
suspend fun syncAvatar(store: AvatarStore, acc: Account) {
    try {
        val key = avatarKey(acc)
        val (code, bytes) = avatarCall("GET", acc.server, acc.token)
        val local = withContext(Dispatchers.IO) { store.readBytes(key) }
        when {
            code == 200 && bytes.isNotEmpty() -> {
                if (local != null && !store.isSynced(key)) {
                    if (uploadAvatar(acc.server, acc.token, local)) store.markSynced(key)
                } else {
                    withContext(Dispatchers.IO) { store.saveBytes(key, bytes) }
                    store.markSynced(key)
                }
            }
            code == 404 && String(bytes).contains("no avatar") -> {
                if (local != null && !store.isSynced(key)) {
                    if (uploadAvatar(acc.server, acc.token, local)) store.markSynced(key)
                } else {
                    store.delete(key)
                    store.markSynced(key)
                }
            }
        }
    } catch (e: Exception) {
        // offline or an older server: keep what we have
    }
}

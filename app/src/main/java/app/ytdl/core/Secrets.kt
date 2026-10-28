package app.ytdl.core

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Keeps the user's YouTube email and password so they can be copied when YouTube asks
 * for a sign-in. Encrypted with a key held in the Android Keystore (it can't be read out
 * of the phone, and it's removed when the app is uninstalled). Never sent anywhere.
 */
object Secrets {
    private const val ALIAS = "ytdl-credentials"
    private const val PREFS = "secrets"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    private fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val data = Base64.decode(enc, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data, 0, 12))
        return String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }

    fun save(ctx: Context, email: String, password: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("email", if (email.isBlank()) null else encrypt(email.trim()))
            .putString("password", if (password.isEmpty()) null else encrypt(password))
            .apply()
    }

    fun email(ctx: Context): String = read(ctx, "email")
    fun password(ctx: Context): String = read(ctx, "password")
    fun hasAny(ctx: Context) = email(ctx).isNotEmpty() || password(ctx).isNotEmpty()

    private fun read(ctx: Context, k: String): String = try {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(k, null)?.let { decrypt(it) } ?: ""
    } catch (_: Exception) { "" }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }

    /**
     * Copies text to the clipboard marked as sensitive (Android hides it from the clipboard
     * preview) and clears it again after a minute.
     */
    fun copy(ctx: Context, label: String, text: String, sensitive: Boolean) {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(label, text)
        if (sensitive) {
            clip.description.extras = PersistableBundle().apply {
                if (Build.VERSION.SDK_INT >= 33) putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                else putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        cm.setPrimaryClip(clip)
        if (sensitive) Handler(Looper.getMainLooper()).postDelayed({
            try {
                val current = cm.primaryClip?.getItemAt(0)?.text?.toString()
                if (current == text) {
                    if (Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip() else cm.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            } catch (_: Exception) { }
        }, 60_000)
    }
}

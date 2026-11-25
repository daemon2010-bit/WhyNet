package dev.whynet.core.engine

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Сквозное шифрование (AES-256-GCM) для сеанса помощи по 6-значному коду.
 * Relay-сервер видит только roomId (хэш кода) и зашифрованный блок,
 * но не может прочитать команды или диагностические отчёты.
 */
object RemoteSessionCrypto {

    private const val SALT_PREFIX = "WhyNet-RemoteHelp-v1:"
    private const val PBKDF2_ITERATIONS = 10_000
    private const val GCM_IV_LEN = 12
    private const val GCM_TAG_BITS = 128

    private val random = SecureRandom()

    fun generateCode(): String {
        val num = random.nextInt(1_000_000)
        return "%06d".format(num)
    }

    fun normalizeCode(raw: String): String =
        raw.filter { it.isDigit() }.take(6)

    fun formatCode(raw: String): String {
        val digits = normalizeCode(raw)
        return if (digits.length == 6) "${digits.substring(0, 3)}-${digits.substring(3)}" else digits
    }

    /**
     * Детерминированный идентификатор комнаты на relay-сервере (SHA-256 от кода с солью),
     * чтобы сам 6-значный код не передавался в URL открытым текстом.
     */
    fun deriveRoomId(code: String): String {
        val clean = normalizeCode(code)
        require(clean.length == 6) { "Код должен содержать 6 цифр" }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(("WhyNet-Room-v1:$clean").toByteArray(StandardCharsets.UTF_8))
        return digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun encrypt(code: String, plainText: String): String {
        val key = deriveAesKey(code)
        val iv = ByteArray(GCM_IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val cipherBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
        return Base64.getEncoder().encodeToString(combined)
    }

    fun decrypt(code: String, base64CipherText: String): String? = runCatching {
        val combined = Base64.getDecoder().decode(base64CipherText.trim())
        if (combined.size <= GCM_IV_LEN + 16) return null
        val iv = combined.copyOfRange(0, GCM_IV_LEN)
        val payload = combined.copyOfRange(GCM_IV_LEN, combined.size)
        val key = deriveAesKey(code)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(payload), StandardCharsets.UTF_8)
    }.getOrNull()

    private fun deriveAesKey(code: String): SecretKeySpec {
        val clean = normalizeCode(code)
        require(clean.length == 6) { "Код должен содержать 6 цифр" }
        val salt = (SALT_PREFIX + clean).toByteArray(StandardCharsets.UTF_8)
        val spec = PBEKeySpec(clean.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }
}

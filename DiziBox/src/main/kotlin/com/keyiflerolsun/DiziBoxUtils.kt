// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * CryptoJS OpenSSL-salted (`Salted__`) AES-256-CBC şifreleme / şifre çözme yardımcısı.
 *
 * Saf JVM + Android uyumludur (`android.util.Base64` kullanmaz; JUnit testlerinde
 * mock gerektirmeden doğrudan çalışır).
 */
@Suppress("unused", "SameParameterValue")
object CryptoJS {

    private const val KEY_SIZE    = 256
    private const val IV_SIZE     = 128
    private const val HASH_CIPHER = "AES/CBC/PKCS5Padding"
    private const val AES         = "AES"
    private const val KDF_DIGEST  = "MD5"
    private const val APPEND      = "Salted__"

    private const val BASE64_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encrypt(password: String, plainText: String): String {
        val saltBytes = generateSalt(8)
        val key       = ByteArray(KEY_SIZE / 8)
        val iv        = ByteArray(IV_SIZE / 8)
        evpkdf(password.toByteArray(Charsets.UTF_8), KEY_SIZE, IV_SIZE, saltBytes, key, iv)

        val keyS   = SecretKeySpec(key, AES)
        val cipher = Cipher.getInstance(HASH_CIPHER)
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, keyS, ivSpec)

        val cipherText = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val sBytes     = APPEND.toByteArray(Charsets.US_ASCII)
        val out        = ByteArray(sBytes.size + saltBytes.size + cipherText.size)
        System.arraycopy(sBytes, 0, out, 0, sBytes.size)
        System.arraycopy(saltBytes, 0, out, sBytes.size, saltBytes.size)
        System.arraycopy(cipherText, 0, out, sBytes.size + saltBytes.size, cipherText.size)

        return base64Encode(out)
    }

    fun decrypt(password: String, cipherText: String): String {
        val trimmed = cipherText.trim()
        if (trimmed.startsWith("{")) {
            return decryptJsonFormat(password, trimmed)
        }

        val ctBytes = base64Decode(trimmed)
        require(ctBytes.size >= 16) { "Ciphertext too short" }
        val header = String(ctBytes, 0, 8, Charsets.US_ASCII)
        require(header == APPEND) { "Missing Salted__ header" }

        val saltBytes       = Arrays.copyOfRange(ctBytes, 8, 16)
        val cipherTextBytes = Arrays.copyOfRange(ctBytes, 16, ctBytes.size)

        val key = ByteArray(KEY_SIZE / 8)
        val iv  = ByteArray(IV_SIZE / 8)
        evpkdf(password.toByteArray(Charsets.UTF_8), KEY_SIZE, IV_SIZE, saltBytes, key, iv)

        val cipher = Cipher.getInstance(HASH_CIPHER)
        val keyS   = SecretKeySpec(key, AES)
        cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(iv))

        val plainText = cipher.doFinal(cipherTextBytes)
        return unwrapOptionalJsonString(String(plainText, Charsets.UTF_8))
    }

    /** `{"ct":"...","iv":"...","s":"..."}` (CryptoJSAesJson) biçimini çözer. */
    private fun decryptJsonFormat(password: String, json: String): String {
        val unescaped = json.replace("\\\"", "\"").replace("\\/", "/")
        val ctBase64 = Regex(""""ct"\s*:\s*"([^"]+)"""").find(unescaped)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Missing ct field in CryptoJS JSON")
        val saltHex = Regex(""""s"\s*:\s*"([0-9a-fA-F]+)"""").find(unescaped)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Missing s field in CryptoJS JSON")
        val ivHex = Regex(""""iv"\s*:\s*"([0-9a-fA-F]+)"""").find(unescaped)?.groupValues?.get(1)

        val saltBytes       = hexDecode(saltHex)
        val cipherTextBytes = base64Decode(ctBase64)
        val key             = ByteArray(KEY_SIZE / 8)
        val derivedIv       = ByteArray(IV_SIZE / 8)
        evpkdf(password.toByteArray(Charsets.UTF_8), KEY_SIZE, IV_SIZE, saltBytes, key, derivedIv)
        val actualIv        = if (!ivHex.isNullOrBlank()) hexDecode(ivHex) else derivedIv

        val cipher = Cipher.getInstance(HASH_CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, AES), IvParameterSpec(actualIv))
        val plain = String(cipher.doFinal(cipherTextBytes), Charsets.UTF_8)
        return unwrapOptionalJsonString(plain)
    }

    private fun hexDecode(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0) { "Odd-length hex string" }
        return ByteArray(clean.length / 2) { idx ->
            clean.substring(idx * 2, idx * 2 + 2).toInt(16).toByte()
        }
    }

    private fun unwrapOptionalJsonString(value: String): String {
        val t = value.trim()
        if (t.length >= 2 && t.startsWith('"') && t.endsWith('"')) {
            return t.substring(1, t.length - 1)
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\")
        }
        return value
    }

    /** RFC 4648 + MIME / URL-safe uyumlu saf Kotlin Base64 çözücü (API 21 + JVM 11+). */
    fun base64Decode(input: String): ByteArray {
        val out = ByteArrayOutputStream((input.length * 3) / 4)
        var buffer = 0
        var bitsCollected = 0
        for (ch in input) {
            if (ch == '=') break
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-'    -> 62
                '/', '_'    -> 63
                else        -> continue // boşluk / satır sonu yoksayılır
            }
            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                out.write((buffer shr bitsCollected) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun base64Encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(((bytes.size + 2) / 3) * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
            val triple = (b0 shl 16) or (b1 shl 8) or b2

            sb.append(BASE64_ALPHABET[(triple ushr 18) and 0x3F])
            sb.append(BASE64_ALPHABET[(triple ushr 12) and 0x3F])
            sb.append(if (i + 1 < bytes.size) BASE64_ALPHABET[(triple ushr 6) and 0x3F] else '=')
            sb.append(if (i + 2 < bytes.size) BASE64_ALPHABET[triple and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    private fun evpkdf(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        resultKey: ByteArray,
        resultIv: ByteArray,
    ): ByteArray = evpkdf(password, keySize, ivSize, salt, 1, KDF_DIGEST, resultKey, resultIv)

    @Suppress("NAME_SHADOWING")
    private fun evpkdf(
        password: ByteArray,
        keySize: Int,
        ivSize: Int,
        salt: ByteArray,
        iterations: Int,
        hashAlgorithm: String,
        resultKey: ByteArray,
        resultIv: ByteArray,
    ): ByteArray {
        val keyWords            = keySize / 32
        val ivWords             = ivSize / 32
        val targetKeySize       = keyWords + ivWords
        val derivedBytes        = ByteArray(targetKeySize * 4)
        var numberOfDerivedWords = 0
        var block: ByteArray?   = null
        val hash                = MessageDigest.getInstance(hashAlgorithm)

        while (numberOfDerivedWords < targetKeySize) {
            if (block != null) {
                hash.update(block)
            }
            hash.update(password)
            block = hash.digest(salt)
            hash.reset()

            for (i in 1 until iterations) {
                block = hash.digest(block!!)
                hash.reset()
            }

            System.arraycopy(
                block!!,
                0,
                derivedBytes,
                numberOfDerivedWords * 4,
                min(block.size, (targetKeySize - numberOfDerivedWords) * 4),
            )
            numberOfDerivedWords += block.size / 4
        }

        System.arraycopy(derivedBytes, 0, resultKey, 0, keyWords * 4)
        System.arraycopy(derivedBytes, keyWords * 4, resultIv, 0, ivWords * 4)
        return derivedBytes
    }

    private fun generateSalt(length: Int): ByteArray {
        val bytes = ByteArray(length)
        SecureRandom().nextBytes(bytes)
        return bytes
    }
}

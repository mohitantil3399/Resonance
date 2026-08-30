package com.example.devicesync.crypto

import android.util.Base64
import android.util.Log
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages ECDHE P-256 key exchange and AES-256-GCM encryption for session payloads.
 *
 * Lifecycle:
 * 1. Call [generateKeyPair] at session start to create the local ECDHE keypair.
 * 2. Send [getPublicKeyBase64] to the remote peer.
 * 3. When the peer's public key arrives, call [deriveSharedSecret] to compute the AES-256 key.
 * 4. Use [encrypt] / [decrypt] for all subsequent sensitive payloads.
 */
class SessionCrypto {

    companion object {
        private const val TAG = "SessionCrypto"
        private const val EC_ALGORITHM = "EC"
        private const val EC_CURVE = "secp256r1"   // P-256
        private const val KEY_AGREEMENT_ALGORITHM = "ECDH"
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val GCM_IV_LENGTH_BYTES = 12
    }

    private var localKeyPair: java.security.KeyPair? = null
    private var sharedSecret: SecretKey? = null

    /** Whether a shared secret has been derived (key exchange is complete). */
    val isEstablished: Boolean
        get() = sharedSecret != null

    /**
     * Generate the local ECDHE P-256 key pair.
     * Call once when the WebSocket session is opened.
     */
    fun generateKeyPair() {
        val kpg = KeyPairGenerator.getInstance(EC_ALGORITHM)
        kpg.initialize(ECGenParameterSpec(EC_CURVE))
        localKeyPair = kpg.generateKeyPair()
        sharedSecret = null // reset in case of re-keying
        Log.d(TAG, "Generated ECDHE P-256 key pair")
    }

    /**
     * Returns the local public key as a Base64-encoded string (X.509 DER format).
     * This is what we send to the remote peer in the key_exchange message.
     */
    fun getPublicKeyBase64(): String {
        val pub = localKeyPair?.public
            ?: throw IllegalStateException("Key pair not generated. Call generateKeyPair() first.")
        return Base64.encodeToString(pub.encoded, Base64.NO_WRAP)
    }

    /**
     * Given the remote peer's Base64-encoded public key, derive the shared AES-256 secret.
     *
     * After this call, [isEstablished] returns true and [encrypt]/[decrypt] are usable.
     */
    fun deriveSharedSecret(remotePublicKeyBase64: String) {
        val remoteKeyBytes = Base64.decode(remotePublicKeyBase64, Base64.NO_WRAP)
        val keySpec = X509EncodedKeySpec(remoteKeyBytes)
        val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
        val remotePublicKey = keyFactory.generatePublic(keySpec) as ECPublicKey

        val keyAgreement = KeyAgreement.getInstance(KEY_AGREEMENT_ALGORITHM)
        keyAgreement.init(localKeyPair?.private
            ?: throw IllegalStateException("Key pair not generated."))
        keyAgreement.doPhase(remotePublicKey, true)

        // ECDH raw shared secret is 32 bytes for P-256; use directly as AES-256 key
        val rawSecret = keyAgreement.generateSecret()
        // Take first 32 bytes (SHA-256 of raw secret for uniform distribution)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val derivedKey = digest.digest(rawSecret)
        sharedSecret = SecretKeySpec(derivedKey, AES_ALGORITHM)

        Log.d(TAG, "ECDHE shared secret derived successfully")
    }

    /**
     * Encrypt a plaintext string using AES-256-GCM.
     *
     * @return Base64-encoded ciphertext in format: `iv_base64:ciphertext_base64`
     */
    fun encrypt(plaintext: String): String {
        val key = sharedSecret
            ?: throw IllegalStateException("Shared secret not established. Complete key exchange first.")

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv // auto-generated 12-byte IV
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

        val ivBase64 = Base64.encodeToString(iv, Base64.NO_WRAP)
        val ctBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        return "$ivBase64:$ctBase64"
    }

    /**
     * Decrypt a ciphertext string produced by [encrypt].
     *
     * @param encryptedPayload format: `iv_base64:ciphertext_base64`
     * @return the original plaintext
     */
    fun decrypt(encryptedPayload: String): String {
        val key = sharedSecret
            ?: throw IllegalStateException("Shared secret not established. Complete key exchange first.")

        val parts = encryptedPayload.split(":", limit = 2)
        if (parts.size != 2) throw IllegalArgumentException("Invalid encrypted payload format")

        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        val spec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        val plainBytes = cipher.doFinal(ciphertext)

        return String(plainBytes, Charsets.UTF_8)
    }

    /**
     * Reset the crypto state (e.g. on disconnect).
     */
    fun reset() {
        localKeyPair = null
        sharedSecret = null
        Log.d(TAG, "Session crypto state reset")
    }
}

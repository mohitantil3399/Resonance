using System;
using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;

namespace WindowsAgent
{
    /// <summary>
    /// Manages ECDHE P-256 key exchange and AES-256-GCM encryption for session payloads.
    ///
    /// Lifecycle:
    /// 1. Call <see cref="GenerateKeyPair"/> at session start.
    /// 2. Send <see cref="GetPublicKeyBase64"/> to the remote peer.
    /// 3. When the peer's public key arrives, call <see cref="DeriveSharedSecret"/>.
    /// 4. Use <see cref="Encrypt"/> / <see cref="Decrypt"/> for all sensitive payloads.
    /// </summary>
    public class SessionCrypto
    {
        private const int GcmTagLengthBytes = 16;   // 128 bits
        private const int GcmIvLengthBytes = 12;

        private ECDiffieHellman? _localKeyPair;
        private byte[]? _sharedSecret;

        /// <summary>
        /// Whether a shared secret has been derived (key exchange is complete).
        /// </summary>
        public bool IsEstablished => _sharedSecret != null;

        /// <summary>
        /// Generate the local ECDHE P-256 key pair. Call once per WebSocket session.
        /// </summary>
        public void GenerateKeyPair()
        {
            _localKeyPair?.Dispose();
            _localKeyPair = ECDiffieHellman.Create(ECCurve.NamedCurves.nistP256);
            _sharedSecret = null; // reset on re-key
            Debug.WriteLine("SessionCrypto: Generated ECDHE P-256 key pair");
        }

        /// <summary>
        /// Returns the local public key as a Base64-encoded string (X.509 SubjectPublicKeyInfo DER).
        /// This is what we send to the Android peer in the key_exchange message.
        /// </summary>
        public string GetPublicKeyBase64()
        {
            if (_localKeyPair == null)
                throw new InvalidOperationException("Key pair not generated. Call GenerateKeyPair() first.");

            var pubBytes = _localKeyPair.PublicKey.ExportSubjectPublicKeyInfo();
            return Convert.ToBase64String(pubBytes);
        }

        /// <summary>
        /// Given the remote peer's Base64-encoded public key (X.509 DER),
        /// derive the shared AES-256 secret.
        /// </summary>
        public void DeriveSharedSecret(string remotePublicKeyBase64)
        {
            if (_localKeyPair == null)
                throw new InvalidOperationException("Key pair not generated.");

            var remoteKeyBytes = Convert.FromBase64String(remotePublicKeyBase64);

            using var remoteKey = ECDiffieHellman.Create();
            remoteKey.ImportSubjectPublicKeyInfo(remoteKeyBytes, out _);

            // Raw ECDH shared secret
            var rawSecret = _localKeyPair.DeriveRawSecretAgreement(remoteKey.PublicKey);

            // SHA-256 hash for uniform key distribution (matches Android side)
            _sharedSecret = SHA256.HashData(rawSecret);

            Debug.WriteLine("SessionCrypto: ECDHE shared secret derived successfully");
        }

        /// <summary>
        /// Encrypt a plaintext string using AES-256-GCM.
        /// Returns Base64 in format: iv_base64:ciphertext_base64
        /// </summary>
        public string Encrypt(string plaintext)
        {
            if (_sharedSecret == null)
                throw new InvalidOperationException("Shared secret not established.");

            var plaintextBytes = Encoding.UTF8.GetBytes(plaintext);
            var iv = RandomNumberGenerator.GetBytes(GcmIvLengthBytes);
            var ciphertext = new byte[plaintextBytes.Length];
            var tag = new byte[GcmTagLengthBytes];

            using var aes = new AesGcm(_sharedSecret, GcmTagLengthBytes);
            aes.Encrypt(iv, plaintextBytes, ciphertext, tag);

            // Combine ciphertext + tag (Android's Cipher.doFinal appends the tag)
            var combined = new byte[ciphertext.Length + tag.Length];
            Buffer.BlockCopy(ciphertext, 0, combined, 0, ciphertext.Length);
            Buffer.BlockCopy(tag, 0, combined, ciphertext.Length, tag.Length);

            var ivBase64 = Convert.ToBase64String(iv);
            var ctBase64 = Convert.ToBase64String(combined);
            return $"{ivBase64}:{ctBase64}";
        }

        /// <summary>
        /// Decrypt a ciphertext string produced by Encrypt (or the Android equivalent).
        /// Input format: iv_base64:ciphertext_base64
        /// </summary>
        public string Decrypt(string encryptedPayload)
        {
            if (_sharedSecret == null)
                throw new InvalidOperationException("Shared secret not established.");

            var parts = encryptedPayload.Split(':', 2);
            if (parts.Length != 2)
                throw new ArgumentException("Invalid encrypted payload format");

            var iv = Convert.FromBase64String(parts[0]);
            var combined = Convert.FromBase64String(parts[1]);

            // Split combined into ciphertext + tag (last 16 bytes = tag)
            var ciphertext = new byte[combined.Length - GcmTagLengthBytes];
            var tag = new byte[GcmTagLengthBytes];
            Buffer.BlockCopy(combined, 0, ciphertext, 0, ciphertext.Length);
            Buffer.BlockCopy(combined, ciphertext.Length, tag, 0, GcmTagLengthBytes);

            var plaintext = new byte[ciphertext.Length];
            using var aes = new AesGcm(_sharedSecret, GcmTagLengthBytes);
            aes.Decrypt(iv, ciphertext, tag, plaintext);

            return Encoding.UTF8.GetString(plaintext);
        }

        /// <summary>
        /// Reset the crypto state (e.g. on disconnect).
        /// </summary>
        public void Reset()
        {
            _localKeyPair?.Dispose();
            _localKeyPair = null;
            _sharedSecret = null;
            Debug.WriteLine("SessionCrypto: Session crypto state reset");
        }
    }
}

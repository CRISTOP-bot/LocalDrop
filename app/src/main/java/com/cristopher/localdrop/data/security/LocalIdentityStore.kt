package com.cristopher.localdrop.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.cristopher.localdrop.domain.model.LocalIdentity
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

class LocalIdentityStore(context: Context, private val deviceId: String) {
    private val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    private val alias = "localdrop.identity.$deviceId"

    init { if (!keyStore.containsAlias(alias)) generateKeyPair() }

    val identity: LocalIdentity
        get() {
            val publicKey = keyStore.getCertificate(alias).publicKey.encoded
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(publicKey)
            return LocalIdentity(deviceId, encoded, fingerprint(publicKey))
        }

    fun sign(message: String): String {
        val signer = Signature.getInstance(SIGNATURE_ALGORITHM)
        signer.initSign(keyStore.getKey(alias, null) as java.security.PrivateKey)
        signer.update(message.toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign())
    }

    private fun generateKeyPair() {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
        generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build())
        generator.generateKeyPair()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        fun fingerprint(publicKeyDer: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(publicKeyDer).joinToString("") { "%02x".format(it) }
        fun verify(publicKeyEncoded: String, message: String, signatureEncoded: String): Boolean = runCatching {
            val publicKeyDer = Base64.getUrlDecoder().decode(publicKeyEncoded)
            val publicKey: PublicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publicKeyDer))
            require(fingerprint(publicKeyDer) == fingerprint(publicKey.encoded))
            val verifier = Signature.getInstance(SIGNATURE_ALGORITHM)
            verifier.initVerify(publicKey)
            verifier.update(message.toByteArray(Charsets.UTF_8))
            verifier.verify(Base64.getUrlDecoder().decode(signatureEncoded))
        }.getOrDefault(false)
    }
}

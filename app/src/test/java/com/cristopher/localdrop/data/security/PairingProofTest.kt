package com.cristopher.localdrop.data.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

class PairingProofTest {
    @Test fun nonceProofIsBoundToTheExactChallenge() {
        val generator = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }
        val pair = generator.generateKeyPair()
        val publicKey = Base64.getUrlEncoder().withoutPadding().encodeToString(pair.public.encoded)
        val nonce = "nonce-${System.nanoTime()}"
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(nonce.toByteArray()) }.sign()
        val encodedSignature = Base64.getUrlEncoder().withoutPadding().encodeToString(signature)
        assertTrue(LocalIdentityStore.verify(publicKey, nonce, encodedSignature))
        assertFalse(LocalIdentityStore.verify(publicKey, "$nonce-modified", encodedSignature))
        assertFalse(LocalIdentityStore.verify(publicKey, "another-nonce", encodedSignature))
    }
}

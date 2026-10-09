package com.shilapi.xcertplay.androidauto

import java.security.GeneralSecurityException
import java.security.interfaces.RSAPrivateKey
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AapTlsTest {
    private val head = TestIdentities.headUnit
    private val phoneIdentity = TestIdentities.phone

    private fun assertRejected(pem: ByteArray) {
        try {
            AapHeadUnitIdentity.parsePem(pem)
            fail("Expected the identity to be rejected")
        } catch (_: GeneralSecurityException) {
        }
    }

    @Test fun readsAPkcs8Identity() {
        val identity = AapHeadUnitIdentity.parsePem(head.pem())
        assertEquals(1, identity.chain.size)
        assertEquals(head.certificate, identity.chain.single())
        assertEquals((head.keyPair.private as RSAPrivateKey).modulus, (identity.privateKeyForTest() as RSAPrivateKey).modulus)
        assertTrue(identity.subject.contains("synthetic head unit"))
        assertFalse(identity.toString().contains("PRIVATE"))
    }

    @Test fun readsAPkcs1Identity() {
        val identity = AapHeadUnitIdentity.parsePem(head.pem(pkcs1Key = true))
        assertEquals((head.keyPair.private as RSAPrivateKey).privateExponent,
            (identity.privateKeyForTest() as RSAPrivateKey).privateExponent)
    }

    @Test fun wrappingPkcs1ProducesTheOriginalPkcs8() {
        assertArrayEquals(head.pkcs8, AapHeadUnitIdentity.wrapPkcs1(head.pkcs1))
    }

    @Test fun keepsTheChainOrderAndToleratesNoise() {
        val chainPem = (TestIdentity.block("CERTIFICATE", head.certificate.encoded) +
            TestIdentity.block("CERTIFICATE", phoneIdentity.certificate.encoded)).toByteArray()
        val noisy = "Bag Attributes\n  friendlyName: unit\n".toByteArray() + chainPem + (
            TestIdentity.block("PRIVATE KEY", head.pkcs8)).replace("\n", "\r\n").toByteArray()
        val identity = AapHeadUnitIdentity.parsePem(noisy)
        assertEquals(listOf(head.certificate, phoneIdentity.certificate), identity.chain)
    }

    @Test fun rejectsIdentitiesThatAreNotUsable() {
        val certificate = TestIdentity.block("CERTIFICATE", head.certificate.encoded)
        val key = TestIdentity.block("PRIVATE KEY", head.pkcs8)
        assertRejected(ByteArray(0))
        assertRejected(ByteArray(AapHeadUnitIdentity.MAX_PEM_BYTES + 1) { 'A'.code.toByte() })
        assertRejected(certificate.toByteArray()) // no key
        assertRejected(key.toByteArray()) // no certificate
        assertRejected((certificate + key + key).toByteArray()) // two keys
        assertRejected((certificate + TestIdentity.block("ENCRYPTED PRIVATE KEY", head.pkcs8)).toByteArray())
        assertRejected((certificate + TestIdentity.block("PRIVATE KEY", phoneIdentity.pkcs8)).toByteArray()) // mismatch
        assertRejected((certificate + "-----BEGIN PRIVATE KEY-----\n@@@@\n-----END PRIVATE KEY-----\n").toByteArray())
        assertRejected("not pem at all".toByteArray())
    }

    private fun AapHeadUnitIdentity.privateKeyForTest() = privateKey

    private fun handshake(client: AapTlsClient, phone: PhoneTls) {
        var toPhone = client.start().toSend
        assertTrue("ClientHello expected", toPhone.isNotEmpty())
        var complete = false
        var rounds = 0
        while (!complete) {
            assertTrue("handshake did not converge", ++rounds < 8)
            val toClient = phone.receive(toPhone)
            val step = client.receive(toClient)
            toPhone = step.toSend
            complete = step.complete
        }
    }

    @Test fun handshakePresentsTheProvisionedCertificateToTheTlsServer() {
        val client = AapTlsClient(AapHeadUnitIdentity.parsePem(head.pem()))
        val phone = PhoneTls(phoneIdentity)
        handshake(client, phone)
        assertTrue(client.isComplete)
        assertTrue(phone.finished)
        assertEquals(head.certificate, phone.clientCertificate)
        assertEquals("TLSv1.2", phone.engine.session.protocol)
    }

    @Test fun applicationDataFlowsBothWaysOncePerFramePayload() {
        val client = AapTlsClient(AapHeadUnitIdentity.parsePem(head.pem()))
        val phone = PhoneTls(phoneIdentity)
        handshake(client, phone)

        val toPhone = ByteArray(16_384) { (it % 251).toByte() }
        assertArrayEquals(toPhone, phone.decrypt(client.encrypt(toPhone)))
        val toClient = "from the phone".toByteArray()
        assertArrayEquals(toClient, client.decrypt(phone.encrypt(toClient)))
        // The ciphertext is not the plaintext.
        assertFalse(client.encrypt(toClient).contentEquals(toClient))
    }

    @Test fun encryptingBeforeTheHandshakeFails() {
        val client = AapTlsClient(AapHeadUnitIdentity.parsePem(head.pem()))
        try {
            client.encrypt(byteArrayOf(1))
            fail()
        } catch (_: javax.net.ssl.SSLException) {
        }
    }

    @Test fun aGarbledHandshakeEndsInAnException() {
        val client = AapTlsClient(AapHeadUnitIdentity.parsePem(head.pem()))
        client.start()
        try {
            client.receive(ByteArray(64) { 0x41 })
            fail()
        } catch (_: javax.net.ssl.SSLException) {
        }
    }

    @Test fun framesCarryTlsRecordsEndToEnd() {
        val client = AapTlsClient(AapHeadUnitIdentity.parsePem(head.pem()))
        val phone = PhoneTls(phoneIdentity)
        handshake(client, phone)
        val out = java.io.ByteArrayOutputStream()
        AapFrameWriter(out).apply { cipher = client }.write(AapMessage.build(4, true, false, AapMedia.DATA, ByteArray(30_000) { 7 }))
        val message = AapFrameReader(java.io.ByteArrayInputStream(out.toByteArray())).apply {
            cipher = object : AapCipher {
                override fun encrypt(plain: ByteArray) = phone.encrypt(plain)
                override fun decrypt(record: ByteArray) = phone.decrypt(record)
            }
        }.read()!!
        assertEquals(30_000, message.body().size)
        assertTrue(message.encrypted)
    }
}

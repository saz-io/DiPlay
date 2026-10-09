package com.shilapi.xcertplay.androidauto

import android.annotation.SuppressLint
import com.shilapi.xcertplay.compat.Base64Compat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * The certificate chain and RSA private key a head unit presents to the phone during the Android
 * Auto TLS handshake.
 *
 * DiPlay does not ship this identity. The owner provisions a PEM file at runtime, and it is kept
 * in app-private storage; see [AndroidAutoIdentityStore].
 */
class AapHeadUnitIdentity private constructor(
    val chain: List<X509Certificate>,
    internal val privateKey: PrivateKey,
) {
    /** Subject of the leaf certificate, for the Settings status line. */
    val subject: String get() = chain.first().subjectX500Principal.name

    /** Expiry of the leaf certificate in epoch milliseconds. */
    val notAfterMillis: Long get() = chain.first().notAfter.time

    override fun toString(): String = "AapHeadUnitIdentity(certificates=${chain.size}, key=<redacted>)"

    companion object {
        /** A PEM identity is a few kilobytes; anything larger is not one. */
        const val MAX_PEM_BYTES = 64 * 1024

        private val BLOCK = Regex("-----BEGIN ([A-Z0-9 ]+)-----([A-Za-z0-9+/=\\s]*)-----END \\1-----")

        /**
         * Parses PEM text holding one or more `CERTIFICATE` blocks (leaf first) and one
         * unencrypted RSA private key as `PRIVATE KEY` (PKCS#8) or `RSA PRIVATE KEY` (PKCS#1).
         *
         * @throws GeneralSecurityException when the data is not a usable identity.
         */
        @Throws(GeneralSecurityException::class)
        fun parsePem(pem: ByteArray): AapHeadUnitIdentity {
            if (pem.isEmpty() || pem.size > MAX_PEM_BYTES) {
                throw GeneralSecurityException("The identity file must be between 1 byte and ${MAX_PEM_BYTES / 1024} KB")
            }
            val text = String(pem, Charsets.US_ASCII)
            val certificates = ArrayList<X509Certificate>()
            var pkcs8: ByteArray? = null
            val factory = CertificateFactory.getInstance("X.509")
            for (block in BLOCK.findAll(text)) {
                val kind = block.groupValues[1]
                val der = try {
                    Base64Compat.decodeMime(block.groupValues[2].toByteArray(Charsets.US_ASCII))
                } catch (error: IllegalArgumentException) {
                    throw GeneralSecurityException("A PEM block is not valid Base64", error)
                }
                when (kind) {
                    "CERTIFICATE" -> certificates += factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                    "PRIVATE KEY" -> {
                        if (pkcs8 != null) throw GeneralSecurityException("The identity has more than one private key")
                        pkcs8 = der
                    }
                    "RSA PRIVATE KEY" -> {
                        if (pkcs8 != null) throw GeneralSecurityException("The identity has more than one private key")
                        pkcs8 = wrapPkcs1(der)
                    }
                    "ENCRYPTED PRIVATE KEY" -> throw GeneralSecurityException("Encrypted private keys are not supported")
                    else -> Unit
                }
            }
            if (certificates.isEmpty()) throw GeneralSecurityException("The identity has no certificate")
            val keyBytes = pkcs8 ?: throw GeneralSecurityException("The identity has no RSA private key")
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val publicKey = certificates.first().publicKey
            if (publicKey is RSAPublicKey && key is RSAPrivateKey && publicKey.modulus != key.modulus) {
                throw GeneralSecurityException("The private key does not belong to the first certificate")
            }
            return AapHeadUnitIdentity(certificates, key)
        }

        /** Wraps a PKCS#1 `RSAPrivateKey` in the PKCS#8 `PrivateKeyInfo` that Java's KeyFactory reads. */
        internal fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val rsaAlgorithm = byteArrayOf(
                0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(),
                0x0d, 0x01, 0x01, 0x01, 0x05, 0x00,
            )
            val key = derElement(0x04, pkcs1)
            return derElement(0x30, version + rsaAlgorithm + key)
        }

        private fun derElement(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            when {
                content.size < 0x80 -> out.write(content.size)
                content.size < 0x100 -> { out.write(0x81); out.write(content.size) }
                content.size < 0x10000 -> { out.write(0x82); out.write(content.size ushr 8); out.write(content.size) }
                else -> { out.write(0x83); out.write(content.size ushr 16); out.write(content.size ushr 8); out.write(content.size) }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}

/** One step of the TLS handshake that runs inside Android Auto control messages. */
class AapHandshakeStep(
    /** Bytes to send to the phone in one SSL control message; empty when nothing is pending. */
    val toSend: ByteArray,
    /** True once the handshake finished and [AapTlsClient.encrypt] may be used. */
    val complete: Boolean,
)

/**
 * The head unit's TLS 1.2 client for the Android Auto link.
 *
 * The phone is the TLS server, and it identifies the head unit by the client certificate. Android
 * Auto does not authenticate the phone's certificate, so this client does not either. That is
 * acceptable here because the peer is the phone itself, on a Wi-Fi network DiPlay created for it,
 * and the TLS layer only has to satisfy the phone's requirement for an encrypted channel. It must
 * not be reused for any other connection.
 */
class AapTlsClient(identity: AapHeadUnitIdentity) : AapCipher {
    private val engine: SSLEngine
    private val readLock = Any()
    private val writeLock = Any()
    private var pending = ByteArray(0)
    private var finished = false

    init {
        val keyManager = FixedIdentityKeyManager(identity.chain.toTypedArray(), identity.privateKey)
        val context = SSLContext.getInstance("TLS").apply {
            init(arrayOf(keyManager), arrayOf(AnyPhoneTrustManager), SecureRandom())
        }
        engine = context.createSSLEngine().apply {
            useClientMode = true
            val tls12 = supportedProtocols.filter { it == "TLSv1.2" }
            if (tls12.isNotEmpty()) enabledProtocols = tls12.toTypedArray()
            sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = null }
        }
    }

    /** Starts the handshake and returns the ClientHello for the first SSL control message. */
    @Throws(SSLException::class)
    fun start(): AapHandshakeStep {
        engine.beginHandshake()
        return pump(null)
    }

    /** Feeds the payload of one SSL control message from the phone. */
    @Throws(SSLException::class)
    fun receive(data: ByteArray): AapHandshakeStep = pump(data)

    val isComplete: Boolean get() = finished

    @Throws(SSLException::class)
    override fun encrypt(plain: ByteArray): ByteArray = synchronized(writeLock) {
        if (!finished) throw SSLException("TLS handshake is not complete")
        val source = ByteBuffer.wrap(plain)
        val out = ByteArrayOutputStream(plain.size + 64)
        while (source.hasRemaining()) {
            val target = ByteBuffer.allocate(engine.session.packetBufferSize)
            val result = engine.wrap(source, target)
            if (result.status != SSLEngineResult.Status.OK || (result.bytesConsumed() == 0 && result.bytesProduced() == 0)) {
                throw SSLException("TLS wrap failed: ${result.status}")
            }
            out.write(target.array(), 0, target.position())
        }
        out.toByteArray()
    }

    @Throws(SSLException::class)
    override fun decrypt(record: ByteArray): ByteArray = synchronized(readLock) {
        if (!finished) throw SSLException("TLS handshake is not complete")
        val source = ByteBuffer.wrap(record)
        val out = ByteArrayOutputStream(record.size)
        while (source.hasRemaining()) {
            val target = ByteBuffer.allocate(engine.session.applicationBufferSize)
            val result = engine.unwrap(source, target)
            if (result.status != SSLEngineResult.Status.OK || (result.bytesConsumed() == 0 && result.bytesProduced() == 0)) {
                throw SSLException("TLS unwrap failed: ${result.status}")
            }
            out.write(target.array(), 0, target.position())
        }
        out.toByteArray()
    }

    private fun pump(incoming: ByteArray?): AapHandshakeStep = synchronized(readLock) {
        if (incoming != null) pending += incoming
        val network = ByteBuffer.wrap(pending)
        val out = ByteArrayOutputStream()
        loop@ while (true) {
            val status = engine.handshakeStatus
            when {
                status == SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val target = ByteBuffer.allocate(engine.session.packetBufferSize)
                    val result = engine.wrap(EMPTY, target)
                    if (result.status != SSLEngineResult.Status.OK) throw SSLException("TLS handshake wrap failed: ${result.status}")
                    out.write(target.array(), 0, target.position())
                    markIfFinished(result)
                }
                status == SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!network.hasRemaining()) break@loop
                    val target = ByteBuffer.allocate(engine.session.applicationBufferSize)
                    val result = engine.unwrap(network, target)
                    when (result.status) {
                        SSLEngineResult.Status.OK -> markIfFinished(result)
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> break@loop
                        else -> throw SSLException("TLS handshake unwrap failed: ${result.status}")
                    }
                }
                status == SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) {
                        task.run()
                        task = engine.delegatedTask
                    }
                }
                else -> break@loop
            }
        }
        pending = ByteArray(network.remaining()).also { network.get(it) }
        AapHandshakeStep(out.toByteArray(), finished)
    }

    private fun markIfFinished(result: SSLEngineResult) {
        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) finished = true
    }

    /** Presents the provisioned identity whatever issuers the phone lists. */
    private class FixedIdentityKeyManager(
        private val chain: Array<X509Certificate>,
        private val key: PrivateKey,
    ) : X509ExtendedKeyManager() {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String> = arrayOf(ALIAS)

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String = ALIAS

        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String = ALIAS

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == ALIAS) chain.clone() else null

        override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == ALIAS) key else null
    }

    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object AnyPhoneTrustManager : X509ExtendedTrustManager() {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val ALIAS = "android-auto-head-unit"
        val EMPTY: ByteBuffer = ByteBuffer.allocate(0)
    }
}

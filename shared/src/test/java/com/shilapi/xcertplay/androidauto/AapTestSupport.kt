package com.shilapi.xcertplay.androidauto

import com.shilapi.xcertplay.compat.Base64Compat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.X509ExtendedTrustManager
import org.bouncycastle.asn1.ASN1Encodable
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.DERBitString
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.DERSequence
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.asn1.x509.Time
import org.bouncycastle.asn1.x509.V3TBSCertificateGenerator
import org.junit.Assert.assertEquals

/** Fresh RSA test material. Nothing here is, or resembles, a real head-unit identity. */
class TestIdentity(val keyPair: KeyPair, val certificate: X509Certificate) {
    val pkcs8: ByteArray get() = keyPair.private.encoded

    /** The unwrapped PKCS#1 `RSAPrivateKey` that `BEGIN RSA PRIVATE KEY` blocks carry. */
    val pkcs1: ByteArray get() = PrivateKeyInfo.getInstance(pkcs8).parsePrivateKey().toASN1Primitive().encoded

    fun pem(pkcs1Key: Boolean = false): ByteArray {
        val key = if (pkcs1Key) block("RSA PRIVATE KEY", pkcs1) else block("PRIVATE KEY", pkcs8)
        return (block("CERTIFICATE", certificate.encoded) + key).toByteArray(Charsets.US_ASCII)
    }

    companion object {
        private val keyPairs = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }

        fun generate(commonName: String): TestIdentity {
            val pair = keyPairs.generateKeyPair()
            val algorithm = AlgorithmIdentifier(PKCSObjectIdentifiers.sha256WithRSAEncryption, DERNull.INSTANCE)
            val name = X500Name("CN=$commonName")
            val tbs = V3TBSCertificateGenerator().apply {
                setSerialNumber(ASN1Integer(BigInteger.valueOf(System.nanoTime())))
                setSignature(algorithm)
                setIssuer(name)
                setSubject(name)
                setStartDate(Time(Date(0)))
                setEndDate(Time(Date(4102444800000L)))
                setSubjectPublicKeyInfo(SubjectPublicKeyInfo.getInstance(pair.public.encoded))
            }.generateTBSCertificate()
            val signer = Signature.getInstance("SHA256withRSA")
            signer.initSign(pair.private)
            signer.update(tbs.encoded)
            val der = DERSequence(arrayOf<ASN1Encodable>(tbs, algorithm, DERBitString(signer.sign()))).encoded
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return TestIdentity(pair, certificate)
        }

        fun block(kind: String, der: ByteArray): String =
            "-----BEGIN $kind-----\n${Base64Compat.encodeLines(der, 64, "\n")}\n-----END $kind-----\n"
    }
}

/** Test identities are expensive to generate and never modified, so all tests share two. */
object TestIdentities {
    val headUnit: TestIdentity by lazy { TestIdentity.generate("synthetic head unit") }
    val phone: TestIdentity by lazy { TestIdentity.generate("synthetic phone") }
}

/** A TLS 1.2 server engine standing in for the phone. It asks for, and records, the client certificate. */
class PhoneTls(identity: TestIdentity) {
    val engine: SSLEngine
    private var pending = ByteArray(0)
    var finished = false
        private set

    init {
        val password = "test".toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("phone", identity.keyPair.private, password, arrayOf(identity.certificate))
        }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }.keyManagers
        val trustAll = object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trustAll), null) }
        engine = context.createSSLEngine().apply {
            useClientMode = false
            needClientAuth = true
            enabledProtocols = arrayOf("TLSv1.2")
        }
        engine.beginHandshake()
    }

    /** The head unit's certificate once the handshake has finished. */
    val clientCertificate: X509Certificate?
        get() = engine.session.peerCertificates?.firstOrNull() as? X509Certificate

    /** Consumes one SSL control message payload and returns the bytes to send back. */
    fun receive(data: ByteArray): ByteArray {
        pending += data
        val network = ByteBuffer.wrap(pending)
        val out = ByteArrayOutputStream()
        loop@ while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> {
                    val target = ByteBuffer.allocate(engine.session.packetBufferSize)
                    val result = engine.wrap(ByteBuffer.allocate(0), target)
                    out.write(target.array(), 0, target.position())
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) finished = true
                }
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> {
                    if (!network.hasRemaining()) break@loop
                    val target = ByteBuffer.allocate(engine.session.applicationBufferSize)
                    val result = engine.unwrap(network, target)
                    if (result.status == SSLEngineResult.Status.BUFFER_UNDERFLOW) break@loop
                    if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) finished = true
                }
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    var task = engine.delegatedTask
                    while (task != null) { task.run(); task = engine.delegatedTask }
                }
                else -> break@loop
            }
        }
        pending = ByteArray(network.remaining()).also { network.get(it) }
        return out.toByteArray()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val source = ByteBuffer.wrap(plain)
        while (source.hasRemaining()) {
            val target = ByteBuffer.allocate(engine.session.packetBufferSize)
            engine.wrap(source, target)
            out.write(target.array(), 0, target.position())
        }
        return out.toByteArray()
    }

    fun decrypt(record: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val source = ByteBuffer.wrap(record)
        while (source.hasRemaining()) {
            val target = ByteBuffer.allocate(engine.session.applicationBufferSize)
            engine.unwrap(source, target)
            out.write(target.array(), 0, target.position())
        }
        return out.toByteArray()
    }
}

/**
 * The phone side of an Android Auto link, scripted by a test. It speaks the same framing and TLS
 * as a phone would, so the head-unit session can be exercised end to end over a loopback socket.
 */
class FakePhone(private val socket: Socket, val tls: PhoneTls) {
    private val reader = AapFrameReader(socket.getInputStream())
    private val writer = AapFrameWriter(socket.getOutputStream())

    private val cipher = object : AapCipher {
        override fun encrypt(plain: ByteArray) = tls.encrypt(plain)
        override fun decrypt(record: ByteArray) = tls.decrypt(record)
    }

    init {
        socket.soTimeout = 10_000
    }

    fun send(channel: Int, id: Int, body: ByteArray, encrypted: Boolean = true, control: Boolean = false) {
        writer.write(AapMessage.build(channel, encrypted, control, id, body))
    }

    fun read(): AapMessage = reader.read() ?: error("The head unit closed the link")

    fun readExpecting(channel: Int, id: Int): AapMessage {
        val message = read()
        assertEquals("channel of $message", channel, message.channel)
        assertEquals("id of $message", id, message.messageId)
        return message
    }

    /** Version exchange, TLS handshake and authentication, as a phone performs them. */
    fun connect(major: Int = 1, minor: Int = 7) {
        val request = readExpecting(AapChannel.CONTROL, AapControl.VERSION_REQUEST)
        assertEquals("version request is plain", false, request.encrypted)
        send(AapChannel.CONTROL, AapControl.VERSION_RESPONSE, byteArrayOf(0, major.toByte(), 0, minor.toByte(), 0, 0), encrypted = false)
        while (!tls.finished) {
            val message = readExpecting(AapChannel.CONTROL, AapControl.SSL_HANDSHAKE)
            val reply = tls.receive(message.body())
            if (reply.isNotEmpty()) send(AapChannel.CONTROL, AapControl.SSL_HANDSHAKE, reply, encrypted = false)
        }
        val auth = readExpecting(AapChannel.CONTROL, AapControl.AUTH_COMPLETE)
        assertEquals("auth complete is plain", false, auth.encrypted)
        reader.cipher = cipher
        writer.cipher = cipher
    }

    /** Opens a channel the way a phone does and checks that the head unit accepts it. */
    fun openChannel(channel: Int): AapMessage {
        send(channel, AapControl.CHANNEL_OPEN_REQUEST, ProtoWriter().sint32(1, 0).int32(2, channel).toByteArray(), control = true)
        val response = readExpecting(channel, AapControl.CHANNEL_OPEN_RESPONSE)
        assertEquals("channel open response carries the control flag", true, response.control)
        return response
    }

    fun close() {
        try { socket.close() } catch (_: java.io.IOException) { }
    }
}

/** A loopback socket pair: the head unit uses [head], the scripted phone uses [phone]. */
class LoopbackLink : AutoCloseable {
    private val server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    val head: Socket = Socket(java.net.InetAddress.getLoopbackAddress(), server.localPort)
    val phone: Socket = server.accept()

    init {
        head.soTimeout = 0
        phone.soTimeout = 10_000
    }

    override fun close() {
        listOf(head, phone).forEach { try { it.close() } catch (_: java.io.IOException) { } }
        server.close()
    }
}

/** Records everything the session hands to the platform. */
class RecordingHost(private val night: Boolean = false) : AapSessionHost {
    val logs = LinkedBlockingQueue<String>()
    val phoneNames = LinkedBlockingQueue<String>()
    val projection = LinkedBlockingQueue<Boolean>()
    val codecConfigs = LinkedBlockingQueue<ByteArray>()
    val frames = LinkedBlockingQueue<Pair<Long, ByteArray>>()
    val videoStops = LinkedBlockingQueue<Unit>()
    val audio = HashMap<Int, RecordingAudioSink>()
    val microphone = RecordingMicrophone()

    override fun videoSink(): AapVideoSink = object : AapVideoSink {
        override fun onCodecConfig(data: ByteArray) { codecConfigs.add(data.copyOf()) }
        override fun onFrame(timestampMicros: Long, data: ByteArray, offset: Int, length: Int) {
            frames.add(timestampMicros to data.copyOfRange(offset, offset + length))
        }
        override fun onStop() { videoStops.add(Unit) }
    }

    @Synchronized override fun audioSink(channel: Int): AapAudioSink = audio.getOrPut(channel) { RecordingAudioSink() }

    override fun microphone(): AapMicrophoneSource = microphone

    override fun isNightMode(): Boolean = night

    override fun onPhoneIdentified(name: String) { phoneNames.add(name) }

    override fun onProjectionRequested(projected: Boolean) { projection.add(projected) }

    override fun onLog(message: String) { logs.add(message) }

}

/** Waits for the next item. A member `take()` would hang a failing test, so this one times out. */
fun <T : Any> LinkedBlockingQueue<T>.awaitNext(): T =
    poll(10, TimeUnit.SECONDS) ?: throw AssertionError("timed out waiting for the platform")

class RecordingAudioSink : AapAudioSink {
    val formats = LinkedBlockingQueue<AapAudioFormat>()
    val written = LinkedBlockingQueue<ByteArray>()
    val stops = LinkedBlockingQueue<Unit>()

    override fun start(format: AapAudioFormat) { formats.add(format) }
    override fun write(data: ByteArray, offset: Int, length: Int) { written.add(data.copyOfRange(offset, offset + length)) }
    override fun stop() { stops.add(Unit) }
}

class RecordingMicrophone : AapMicrophoneSource {
    @Volatile var sink: ((ByteArray, Int) -> Unit)? = null
    val closed = LinkedBlockingQueue<Unit>()

    override fun open(onData: (ByteArray, Int) -> Unit) { sink = onData }
    override fun close() { sink = null; closed.add(Unit) }
}

package com.shilapi.xcertplay.androidauto

import java.util.concurrent.CopyOnWriteArrayList

/** What the Android Auto receiver is doing, for Settings, the notification and the projection screen. */
enum class AndroidAutoPhase {
    /** The feature is off or its service is not running. */
    OFF,

    /** Waiting for a paired phone to open the Bluetooth service. */
    LISTENING,

    /** A phone connected; the Wi-Fi network is being created. */
    PREPARING_NETWORK,

    /** The phone was told where to connect and is joining the Wi-Fi network. */
    WAITING_FOR_PHONE,

    /** TCP is up and the version, TLS and service discovery steps are running. */
    CONNECTING,

    /** The phone is projecting. */
    PROJECTING,

    /** The last attempt failed; [AndroidAutoSnapshot.failure] says why. */
    ERROR,
}

/** Why the last attempt failed, so the app can show a message in the driver's language. */
enum class AndroidAutoFailure {
    OTHER_PROJECTION_RUNNING,
    NO_IDENTITY,
    IDENTITY_UNUSABLE,
    BLUETOOTH_OFF,
    BLUETOOTH_PERMISSION,
    NETWORK_FAILED,
    PHONE_DID_NOT_JOIN,
    INCOMPATIBLE_PHONE,
    LINK_LOST,
    PROTOCOL_ERROR,
    UNKNOWN,
}

class AndroidAutoSnapshot(
    val phase: AndroidAutoPhase,
    val phoneName: String? = null,
    val failure: AndroidAutoFailure? = null,
    /** A technical note for the log or a diagnostic report, not for display. */
    val detail: String? = null,
)

/** Process-wide status of the receiver. Listeners are called on whichever thread changed it. */
object AndroidAutoState {
    private val listeners = CopyOnWriteArrayList<(AndroidAutoSnapshot) -> Unit>()

    @Volatile var snapshot: AndroidAutoSnapshot = AndroidAutoSnapshot(AndroidAutoPhase.OFF)
        private set

    fun update(
        phase: AndroidAutoPhase,
        phoneName: String? = null,
        failure: AndroidAutoFailure? = null,
        detail: String? = null,
    ) {
        val next = AndroidAutoSnapshot(phase, phoneName, failure, detail)
        snapshot = next
        listeners.forEach { it(next) }
    }

    /** Registers [listener] and returns the function that removes it. */
    fun addListener(listener: (AndroidAutoSnapshot) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }
}

/** Lets the app that hosts the receiver stop it from starting while another projection is running. */
object AndroidAutoGate {
    @Volatile var otherProjectionRunning: () -> Boolean = { false }
}

/** The live session, shared between the service that runs it and the activity that shows it. */
object AndroidAutoRuntime {
    class Active(
        val session: AapSession,
        val decoder: AapVideoDecoder,
        val video: AapVideoConfig,
    )

    @Volatile var active: Active? = null

    private val leaveListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** Registers [listener] for "the phone wants the head unit to leave the projection screen". */
    fun addLeaveListener(listener: () -> Unit): () -> Unit {
        leaveListeners += listener
        return { leaveListeners -= listener }
    }

    fun requestLeave() = leaveListeners.forEach { it() }
}

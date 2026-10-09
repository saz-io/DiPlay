package com.shilapi.xcertplay.androidauto

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.GeneralSecurityException

/** Whether a head-unit identity is available to the receiver. */
sealed class AndroidAutoIdentityStatus {
    data object NotImported : AndroidAutoIdentityStatus()

    class Ready(val subject: String, val expiresAtMillis: Long) : AndroidAutoIdentityStatus()

    /** The stored identity is no longer valid, for example its certificate has expired. */
    class Expired(val subject: String, val expiredAtMillis: Long) : AndroidAutoIdentityStatus()

    class Unusable(val reason: String) : AndroidAutoIdentityStatus()
}

/** The outcome of copying an identity file into the app. */
sealed class AndroidAutoImportResult {
    class Imported(val status: AndroidAutoIdentityStatus) : AndroidAutoImportResult()

    data object FileMissing : AndroidAutoImportResult()

    class Rejected(val reason: String) : AndroidAutoImportResult()
}

/**
 * Keeps the PEM identity that the phone's Android Auto accepts for a head unit.
 *
 * DiPlay does not ship an identity: the owner provides one. The file `headunit.pem` is placed in
 * [importFolder], which on Android is the app's own folder under `Android/data` and needs no
 * storage permission. [importFromFolder] validates it and copies it into private, non-backed-up
 * storage. The stored copy is what the receiver reads.
 */
class AndroidAutoIdentityStore internal constructor(
    private val privateDirectory: File,
    val importFolder: File?,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, DIRECTORY),
        context.getExternalFilesDir(null)?.let { File(it, DIRECTORY) },
    )

    private val stored: File get() = File(privateDirectory, FILE_NAME)

    /** Where the owner puts the file, for the Settings description. */
    val importPath: String get() = importFolder?.let { File(it, FILE_NAME).path } ?: "Android/data/<app>/files/$DIRECTORY/$FILE_NAME"

    fun status(): AndroidAutoIdentityStatus {
        val file = stored
        if (!file.isFile) return AndroidAutoIdentityStatus.NotImported
        val identity = try {
            AapHeadUnitIdentity.parsePem(file.readBytes())
        } catch (error: GeneralSecurityException) {
            return AndroidAutoIdentityStatus.Unusable(error.message ?: "The stored identity is not valid")
        } catch (error: IOException) {
            return AndroidAutoIdentityStatus.Unusable("The stored identity could not be read")
        }
        return statusOf(identity)
    }

    /** The identity for a connection, or null when none is stored or it is unusable. */
    fun load(): AapHeadUnitIdentity? {
        val file = stored
        if (!file.isFile) return null
        return try {
            AapHeadUnitIdentity.parsePem(file.readBytes())
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    fun importFromFolder(): AndroidAutoImportResult {
        val source = importFolder?.let { File(it, FILE_NAME) }
        if (source == null || !source.isFile) return AndroidAutoImportResult.FileMissing
        if (source.length() > AapHeadUnitIdentity.MAX_PEM_BYTES) {
            return AndroidAutoImportResult.Rejected("The file is larger than ${AapHeadUnitIdentity.MAX_PEM_BYTES / 1024} KB")
        }
        val bytes = try {
            source.readBytes()
        } catch (error: IOException) {
            return AndroidAutoImportResult.Rejected("The file could not be read")
        }
        try {
            val identity = try {
                AapHeadUnitIdentity.parsePem(bytes)
            } catch (error: GeneralSecurityException) {
                return AndroidAutoImportResult.Rejected(error.message ?: "The file is not a usable identity")
            }
            try {
                write(bytes)
            } catch (error: IOException) {
                return AndroidAutoImportResult.Rejected("The identity could not be stored")
            }
            return AndroidAutoImportResult.Imported(statusOf(identity))
        } finally {
            bytes.fill(0)
        }
    }

    fun clear() {
        stored.delete()
    }

    private fun statusOf(identity: AapHeadUnitIdentity): AndroidAutoIdentityStatus =
        if (identity.notAfterMillis < clockMillis()) {
            AndroidAutoIdentityStatus.Expired(identity.subject, identity.notAfterMillis)
        } else {
            AndroidAutoIdentityStatus.Ready(identity.subject, identity.notAfterMillis)
        }

    private fun write(bytes: ByteArray) {
        if (!privateDirectory.isDirectory && !privateDirectory.mkdirs()) throw IOException("Could not create the identity folder")
        val staging = File(privateDirectory, "$FILE_NAME.tmp")
        staging.writeBytes(bytes)
        staging.setReadable(false, false)
        staging.setReadable(true, true)
        staging.setWritable(false, false)
        staging.setWritable(true, true)
        if (!staging.renameTo(stored)) {
            staging.delete()
            throw IOException("Could not store the identity")
        }
    }

    companion object {
        const val DIRECTORY = "android-auto"
        const val FILE_NAME = "headunit.pem"
    }
}

/** The single setting that turns the receiver on. */
object AndroidAutoSettings {
    private const val PREFERENCES = "android_auto"
    private const val KEY_ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}

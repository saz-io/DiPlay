package com.shilapi.xcertplay.androidauto

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AndroidAutoIdentityStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    private val identity = TestIdentities.headUnit
    private var now = 1_700_000_000_000L

    private fun store(): Triple<AndroidAutoIdentityStore, File, File> {
        val private = File(temporary.root, "private")
        val import = File(temporary.root, "import").apply { mkdirs() }
        return Triple(AndroidAutoIdentityStore(private, import) { now }, private, import)
    }

    @Test fun startsWithoutAnIdentity() {
        val (store, _, _) = store()
        assertTrue(store.status() is AndroidAutoIdentityStatus.NotImported)
        assertNull(store.load())
        assertTrue(store.importFromFolder() is AndroidAutoImportResult.FileMissing)
    }

    @Test fun importsAValidIdentityAndKeepsAPrivateCopy() {
        val (store, private, import) = store()
        val source = File(import, AndroidAutoIdentityStore.FILE_NAME).apply { writeBytes(identity.pem()) }
        val result = store.importFromFolder()
        assertTrue(result is AndroidAutoImportResult.Imported)
        val status = (result as AndroidAutoImportResult.Imported).status as AndroidAutoIdentityStatus.Ready
        assertTrue(status.subject.contains("synthetic head unit"))
        assertEquals(identity.certificate.notAfter.time, status.expiresAtMillis)

        val stored = File(private, AndroidAutoIdentityStore.FILE_NAME)
        assertArrayEquals(source.readBytes(), stored.readBytes())
        assertNotNull(store.load())
        assertTrue(store.status() is AndroidAutoIdentityStatus.Ready)
        assertFalse("no staging file is left behind", File(private, "${AndroidAutoIdentityStore.FILE_NAME}.tmp").exists())
    }

    @Test fun rejectsFilesThatAreNotIdentitiesWithoutStoringThem() {
        val (store, private, import) = store()
        File(import, AndroidAutoIdentityStore.FILE_NAME).writeText("this is not an identity")
        val result = store.importFromFolder()
        assertTrue(result is AndroidAutoImportResult.Rejected)
        assertFalse(File(private, AndroidAutoIdentityStore.FILE_NAME).exists())
        assertTrue(store.status() is AndroidAutoIdentityStatus.NotImported)
    }

    @Test fun rejectsOversizedFiles() {
        val (store, _, import) = store()
        File(import, AndroidAutoIdentityStore.FILE_NAME).writeBytes(ByteArray(AapHeadUnitIdentity.MAX_PEM_BYTES + 1))
        assertTrue(store.importFromFolder() is AndroidAutoImportResult.Rejected)
    }

    @Test fun aFailedReplacementKeepsThePreviousIdentity() {
        val (store, _, import) = store()
        File(import, AndroidAutoIdentityStore.FILE_NAME).writeBytes(identity.pem())
        store.importFromFolder()
        File(import, AndroidAutoIdentityStore.FILE_NAME).writeText("garbage")
        assertTrue(store.importFromFolder() is AndroidAutoImportResult.Rejected)
        assertNotNull("the earlier identity still works", store.load())
    }

    @Test fun reportsAnExpiredCertificate() {
        val (store, _, import) = store()
        File(import, AndroidAutoIdentityStore.FILE_NAME).writeBytes(identity.pem())
        store.importFromFolder()
        now = identity.certificate.notAfter.time + 1
        val status = store.status()
        assertTrue(status is AndroidAutoIdentityStatus.Expired)
        assertNotNull("an expired identity is still loadable; the phone decides", store.load())
    }

    @Test fun reportsACorruptedStoredCopy() {
        val (store, private, import) = store()
        File(import, AndroidAutoIdentityStore.FILE_NAME).writeBytes(identity.pem())
        store.importFromFolder()
        File(private, AndroidAutoIdentityStore.FILE_NAME).writeText("corrupted")
        assertTrue(store.status() is AndroidAutoIdentityStatus.Unusable)
        assertNull(store.load())
    }

    @Test fun clearRemovesTheStoredIdentityOnly() {
        val (store, _, import) = store()
        val source = File(import, AndroidAutoIdentityStore.FILE_NAME).apply { writeBytes(identity.pem()) }
        store.importFromFolder()
        store.clear()
        assertTrue(store.status() is AndroidAutoIdentityStatus.NotImported)
        assertTrue("the owner's file is left alone", source.exists())
    }

    @Test fun withoutAnImportFolderTheStoreExplainsWhereToPutTheFile() {
        val store = AndroidAutoIdentityStore(File(temporary.root, "p"), null)
        assertTrue(store.importFromFolder() is AndroidAutoImportResult.FileMissing)
        assertTrue(store.importPath.endsWith(AndroidAutoIdentityStore.FILE_NAME))
    }
}

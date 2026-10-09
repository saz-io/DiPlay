package com.shilapi.xcertplay

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.androidauto.AndroidAutoIdentityStore
import com.shilapi.xcertplay.androidauto.AndroidAutoPhase
import com.shilapi.xcertplay.androidauto.AndroidAutoService
import com.shilapi.xcertplay.androidauto.AndroidAutoSettings
import com.shilapi.xcertplay.androidauto.AndroidAutoState
import com.shilapi.xcertplay.host.R
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en-w600dp-h700dp")
class AndroidAutoSettingsUiTest {
    private val application get() = RuntimeEnvironment.getApplication()
    private var activity: DiPlayActivity? = null

    @After fun tearDown() {
        activity?.finish()
        AppAppearanceRuntime.resetForTest()
        application.getSharedPreferences("diplay", 0).edit().clear().commit()
        application.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        application.getSharedPreferences("android_auto", 0).edit().clear().commit()
        AndroidAutoState.update(AndroidAutoPhase.OFF)
    }

    private fun openAdvanced(): DiPlayActivity {
        val screen = Robolectric.buildActivity(
            DiPlayActivity::class.java,
            Intent(application, DiPlayActivity::class.java).putExtra("page", "settings"),
        ).setup().get().also { activity = it }
        ReflectionHelpers.setField(screen, "settingsCategory", SettingsCategory.ADVANCED)
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "render")
        return screen
    }

    private fun receiverSwitch(screen: DiPlayActivity): Switch = descendants(screen.window.decorView)
        .filterIsInstance<Switch>()
        .single { it.contentDescription == screen.getString(R.string.settings_android_auto_receiver) }

    private fun texts(screen: DiPlayActivity) = descendants(screen.window.decorView).filterIsInstance<TextView>()

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun grantPermissions() {
        shadowOf(application).grantPermissions(*AndroidAutoSupport.requiredPermissions(application).toTypedArray())
    }

    @Test fun theReceiverIsOffByDefaultAndTheCardIsMarkedExperimental() {
        val screen = openAdvanced()

        assertTrue(texts(screen).any { it.text.toString() == screen.getString(R.string.settings_android_auto) })
        assertTrue(screen.getString(R.string.settings_android_auto).endsWith("(experimental)"))
        assertFalse(receiverSwitch(screen).isChecked)
        assertFalse(AndroidAutoSettings.enabled(application))
        val identity = screen.getString(R.string.settings_android_auto_identity) + " · " +
            screen.getString(R.string.settings_android_auto_identity_missing)
        assertTrue(texts(screen).any { it.text.toString() == identity })
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test fun turningItOnSavesTheChoiceAndStartsTheReceiverWithTheCarPlayWifiMode() {
        grantPermissions()
        val screen = openAdvanced()

        receiverSwitch(screen).performClick()

        assertTrue(AndroidAutoSettings.enabled(application))
        val started = shadowOf(application).nextStartedService
        assertNotNull(started)
        assertEquals(AndroidAutoService::class.java.name, started.component?.className)
        assertEquals(AirPlayPersistence.loadWirelessHotspotMode(application).name, started.getStringExtra("aa.mode"))
        assertEquals(screen.getString(R.string.settings_android_auto_notification_title), started.getStringExtra("aa.title"))
    }

    @Test fun withoutTheNeededPermissionsTheReceiverStaysOff() {
        val screen = openAdvanced()

        receiverSwitch(screen).performClick()

        assertFalse(AndroidAutoSettings.enabled(application))
        assertNull(shadowOf(application).nextStartedService)
    }

    @Test fun turningItOffSavesTheChoice() {
        AndroidAutoSettings.setEnabled(application, true)
        val screen = openAdvanced()
        val toggle = receiverSwitch(screen)
        assertTrue(toggle.isChecked)

        toggle.performClick()

        assertFalse(AndroidAutoSettings.enabled(application))
    }

    @Test fun importingWithoutAFileExplainsWhereToPutIt() {
        val screen = openAdvanced()
        val identity = screen.getString(R.string.settings_android_auto_identity)

        texts(screen).first { it.text.toString().startsWith(identity + " · ") }.performClick()

        val message = ShadowToast.getTextOfLatestToast()
        assertTrue(message, message.contains(AndroidAutoIdentityStore.FILE_NAME))
        assertTrue(message, message.contains(AndroidAutoIdentityStore(application).importPath))
    }

    @Test fun aFileThatIsNotAnIdentityIsRejected() {
        val screen = openAdvanced()
        val folder = AndroidAutoIdentityStore(application).importFolder!!.apply { mkdirs() }
        File(folder, AndroidAutoIdentityStore.FILE_NAME).writeText("not an identity")
        val identity = screen.getString(R.string.settings_android_auto_identity)

        texts(screen).first { it.text.toString().startsWith(identity + " · ") }.performClick()

        assertEquals(screen.getString(R.string.settings_android_auto_identity_rejected), ShadowToast.getTextOfLatestToast())
        assertEquals(
            screen.getString(R.string.settings_android_auto_identity_missing),
            AndroidAutoSupport.identityValue(application, AndroidAutoIdentityStore(application).status()),
        )
    }

    @Test fun theStatusLineFollowsTheReceiverState() {
        val screen = openAdvanced()
        val expectedOff = screen.getString(R.string.settings_android_auto_status, screen.getString(R.string.settings_android_auto_state_off))
        assertTrue(texts(screen).any { it.text.toString() == expectedOff })

        AndroidAutoState.update(AndroidAutoPhase.PROJECTING, phoneName = "Pixel 9")
        ReflectionHelpers.callInstanceMethod<Unit>(screen, "refreshStatus")

        val expectedConnected = screen.getString(
            R.string.settings_android_auto_status,
            screen.getString(R.string.settings_android_auto_state_projecting_named, "Pixel 9"),
        )
        assertTrue(texts(screen).any { it.text.toString() == expectedConnected })
    }
}

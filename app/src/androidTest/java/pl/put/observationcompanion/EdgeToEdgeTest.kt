package pl.put.observationcompanion

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Run in portrait/landscape with both gesture and three-button navigation. */
@RunWith(AndroidJUnit4::class)
class EdgeToEdgeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun systemIconsStayLightInTheFixedDarkTheme() {
        compose.runOnIdle {
            val window = compose.activity.window
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
            assertFalse(window.isNavigationBarContrastEnforced)
        }
    }

    @Test
    fun navigationControlsAvoidSystemBarsAndDisplayCutouts() {
        listOf("nav_stats_button", "nav_location_button", "nav_settings_button").forEach { tag ->
            assertTagInsideSafeArea(tag)
        }
        compose.onNodeWithTag("nav_stats_button").performClick()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("nav_settings_button").performClick()
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        compose.onNodeWithTag("nav_location_button").performClick()
        compose.onNodeWithTag("location_screen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
    }

    @Test
    fun locationFieldStaysAboveTheKeyboard() {
        compose.onNodeWithTag("nav_location_button").performClick()
        compose.waitUntil(30_000) {
            runCatching { compose.onNodeWithTag("latitude_textfield").fetchSemanticsNode() }.isSuccess
        }
        compose.onNodeWithTag("latitude_textfield").performScrollTo().performClick()
        compose.waitUntil(30_000) {
            var visible = false
            compose.runOnUiThread {
                visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.ime()) == true
            }
            visible
        }
        compose.waitForIdle()
        assertTagInsideSafeArea("latitude_textfield", includeIme = true)
    }

    private fun assertTagInsideSafeArea(tag: String, includeIme: Boolean = false) {
        val node = compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
        val bounds = node.boundsInWindow
        compose.runOnIdle {
            val decor = compose.activity.window.decorView
            val types = WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                (if (includeIme) WindowInsetsCompat.Type.ime() else 0)
            val insets = requireNotNull(ViewCompat.getRootWindowInsets(decor)).getInsets(types)
            val tolerance = 1f
            assertTrue("$tag overlaps left inset: $bounds", bounds.left >= insets.left - tolerance)
            assertTrue("$tag overlaps right inset: $bounds", bounds.right <= decor.width - insets.right + tolerance)
            assertTrue("$tag overlaps status bar: $bounds", bounds.top >= insets.top - tolerance)
            assertTrue("$tag overlaps bottom inset: $bounds", bounds.bottom <= decor.height - insets.bottom + tolerance)
        }
    }

    companion object {
        @JvmStatic
        @BeforeClass
        fun allowNotificationsOnTestDevice() {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
                automation.executeShellCommand(
                    "pm grant pl.put.observationcompanion android.permission.POST_NOTIFICATIONS"
                ).use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
            }
        }
    }
}

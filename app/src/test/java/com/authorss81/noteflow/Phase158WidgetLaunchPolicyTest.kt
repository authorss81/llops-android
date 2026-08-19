package com.authorss81.noteflow

import com.authorss81.noteflow.services.WidgetLaunchPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 158 (deferred ROADMAP 22.5b) — home-widget quick-capture intent
 * contract. The widget is a launcher shortcut ONLY (no vault access, no
 * content, no periodic refresh); every shared string/boolean lives in
 * [WidgetLaunchPolicy] so the intent parse is unit-pinned.
 */
class Phase158WidgetLaunchPolicyTest {

    @Test
    fun `the quick-capture extra key is the single contract string`() {
        assertEquals(
            "com.authorss81.noteflow.intent.extra.QUICK_CAPTURE",
            WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE
        )
    }

    @Test
    fun `quick-capture parses only from an explicit true boolean`() {
        assertTrue(
            WidgetLaunchPolicy.hasQuickCaptureExtra(
                mapOf(WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE to true)
            )
        )
        assertFalse(
            "absent extra does not fire",
            WidgetLaunchPolicy.hasQuickCaptureExtra(emptyMap())
        )
        assertFalse(
            "a false boolean does not fire",
            WidgetLaunchPolicy.hasQuickCaptureExtra(
                mapOf(WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE to false)
            )
        )
        assertFalse(
            "a null-wrapped value does not fire",
            WidgetLaunchPolicy.hasQuickCaptureExtra(
                mapOf(WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE to null)
            )
        )
        assertFalse(
            "an unrelated key does not fire",
            WidgetLaunchPolicy.hasQuickCaptureExtra(mapOf("other" to true))
        )
    }

    @Test
    fun `the widget is a flat launcher shortcut - no content, no refresh cadence`() {
        assertTrue(WidgetLaunchPolicy.WIDGET_ROOT_VIEW_ID.isNotBlank())
        assertTrue(
            "widget never shows note content",
            WidgetLaunchPolicy.WIDGET_DESCRIPTION.contains("no content")
        )
        val providerXml = java.io.File(repoRoot(),
            "app/src/main/res/xml/quick_capture_widget_info.xml")
        assertTrue("sanity: provider info XML exists", providerXml.isFile)
        val xml = providerXml.readText().replace(Regex("<!--[\\s\\S]*?-->"), "")
        assertTrue(
            "updatePeriodMillis must be 0 (launcher shortcut only)",
            xml.contains("android:updatePeriodMillis=\"0\"")
        )
        assertTrue(
            "widget category must be home_screen only",
            xml.contains("android:widgetCategory=\"home_screen\"")
        )
    }

    @Test
    fun `the widget never touches the vault - no permissions, no provider code`() {
        val widget = java.io.File(repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/widget/QuickCaptureWidget.kt")
        assertTrue("sanity: widget provider exists", widget.isFile)
        val code = widget.readText()
            .replace(Regex("//[^\\n]*"), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
        assertFalse(
            "widget code must not read or write any content",
            code.contains("readText") || code.contains("writeText") ||
                code.contains("ContentResolver") || code.contains("query(") ||
                code.contains("SharedPreferences")
        )
        assertFalse(
            "widget code must never reference a vault handle",
            code.contains("NoteRepository") || code.contains("EncryptionService") ||
                code.contains("NoteflowDatabase") || code.contains("VaultKeyHolder")
        )
        val manifest = java.io.File(repoRoot(), "app/src/main/AndroidManifest.xml").readText()
        assertTrue("provider must be registered in the manifest", manifest.contains("QuickCaptureWidget"))
    }

    @Test
    fun `the widget tap lands on MainActivity carrying only the boolean extra`() {
        val widget = java.io.File(repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/widget/QuickCaptureWidget.kt").readText()
        assertTrue(
            "widget fires an explicit MainActivity intent carrying the capture extra",
            widget.contains("MainActivity") &&
                widget.contains("WidgetLaunchPolicy.EXTRA_QUICK_CAPTURE")
        )
        val main = java.io.File(repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()
        assertTrue(
            "MainActivity reads the extra via the policy",
            main.contains("WidgetLaunchPolicy.hasQuickCaptureExtra")
        )
    }

    @Test
    fun `widget geometry is a modest flat tile`() {
        assertEquals(40, WidgetLaunchPolicy.MIN_WIDTH_DP)
        assertEquals(40, WidgetLaunchPolicy.MIN_HEIGHT_DP)
        assertTrue(WidgetLaunchPolicy.MAX_RESIZE_HEIGHT_DP < WidgetLaunchPolicy.MIN_WIDTH_DP * 3)
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
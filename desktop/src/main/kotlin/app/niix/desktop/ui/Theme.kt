package app.niix.desktop.ui

import app.niix.core.storage.StoragePaths
import java.io.File
import org.gnome.adw.ColorScheme
import org.gnome.adw.StyleManager
import org.gnome.gdk.Display
import org.gnome.gtk.CssProvider
import org.gnome.gtk.Gtk
import org.gnome.gtk.IconTheme
import org.gnome.gtk.Window

/**
 * NiiX's look on the desktop: the phone app's default dark theme (black, with its pink accent) and
 * its typeface, JetBrains Mono, which is bundled.
 */
object Theme {

    const val ACCENT = "#FF0A6C"

    private val css = """
        @define-color accent_color #FF4D94;
        @define-color accent_bg_color $ACCENT;
        @define-color accent_fg_color #FFFFFF;
        @define-color window_bg_color #000000;
        @define-color view_bg_color #000000;
        @define-color headerbar_bg_color #0B0B10;
        @define-color sidebar_bg_color #0B0B10;
        @define-color card_bg_color #121218;
        @define-color dialog_bg_color #121218;
        @define-color popover_bg_color #1A1A22;
        window, label, entry, textview, button, row { font-family: "JetBrains Mono", monospace; }
        .niix-brand { font-weight: 800; font-size: 28pt; color: $ACCENT; }
        .niix-subtitle { color: #A0A0AE; }
        .bubble { border-radius: 14px; padding: 8px 12px; }
        .bubble.incoming { background-color: #1A1A22; }
        .bubble.outgoing { background-color: alpha($ACCENT, 0.22); }
        .bubble.system { background-color: transparent; color: #A0A0AE; }
        .bubble-meta { font-size: 8pt; color: #7A7A88; }
        .bubble-sender { font-size: 9pt; font-weight: 700; color: #FF4D94; }
        .bubble-quote { border-left: 3px solid $ACCENT; padding-left: 8px; color: #A0A0AE; font-size: 9pt; }
        .hidden-message { color: #7A7A88; font-style: italic; }
        .composer { padding: 6px; }
        .composer textview { background: transparent; }
        .composer-frame { border-radius: 18px; background-color: #121218; padding: 6px 10px; }
        .qr-frame { background-color: #FFFFFF; border-radius: 12px; padding: 12px; }
        .console { font-size: 9pt; color: #A0A0AE; }
        .danger { color: #FF5370; }
        .call-panel { background-color: alpha($ACCENT, 0.14); padding: 12px; }
        .request-row { background-color: alpha($ACCENT, 0.08); }
    """.trimIndent()

    /** libadwaita 1.6 and later read colours from CSS variables, which GTK before 4.16 can't parse. */
    private val variables = """
        :root {
            --accent-color: #FF4D94;
            --accent-bg-color: $ACCENT;
            --accent-fg-color: #FFFFFF;
            --window-bg-color: #000000;
            --view-bg-color: #000000;
            --headerbar-bg-color: #0B0B10;
            --sidebar-bg-color: #0B0B10;
            --card-bg-color: #121218;
            --dialog-bg-color: #121218;
            --popover-bg-color: #1A1A22;
        }
    """.trimIndent()

    fun apply(fontCacheDir: File) {
        StyleManager.getDefault().setColorScheme(ColorScheme.FORCE_DARK)
        loadBundledFonts(fontCacheDir)
        val display = Display.getDefault() ?: return
        registerIcons(display, fontCacheDir)
        val sheets = if (Gtk.getMajorVersion() > 4 || Gtk.getMinorVersion() >= 16) listOf(variables, css) else listOf(css)
        sheets.forEach { sheet ->
            val provider = CssProvider()
            provider.loadFromString(sheet)
            Gtk.styleContextAddProviderForDisplay(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        }
    }

    /**
     * The app's icons. Installed with the Flatpak; run any other way they come from the jar, so
     * the window and the empty chat screen still show the NiiX logo.
     */
    private fun registerIcons(display: Display, cacheDir: File) {
        if (!File("/.flatpak-info").exists()) {
            runCatching {
                val dir = File(cacheDir, "icons")
                StoragePaths.ensurePrivateDirectory(dir)
                listOf("app.niix.NiiX.svg", "app.niix.NiiX-symbolic.svg").forEach { name ->
                    Theme::class.java.getResourceAsStream("/icons/$name")?.use { input ->
                        File(dir, name).outputStream().use { input.copyTo(it) }
                    }
                }
                IconTheme.getForDisplay(display).addSearchPath(dir.absolutePath)
            }
        }
        Window.setDefaultIconName("app.niix.NiiX")
    }

    /**
     * Inside the Flatpak the fonts are installed with the app and fontconfig finds them. Run any
     * other way, they're added to Pango directly, where its version allows that.
     */
    private fun loadBundledFonts(cacheDir: File) {
        if (File("/.flatpak-info").exists()) return
        runCatching {
            val dir = File(cacheDir, "fonts")
            StoragePaths.ensurePrivateDirectory(dir)
            FONTS.forEach { name ->
                val target = File(dir, name)
                if (!target.isFile) {
                    Theme::class.java.getResourceAsStream("/fonts/$name")?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                }
                if (target.isFile) {
                    runCatching { org.gnome.pango.cairo.FontMap.getDefault().addFontFile(target.absolutePath) }
                }
            }
        }
    }

    private val FONTS = listOf(
        "jetbrains_mono_regular.ttf",
        "jetbrains_mono_medium.ttf",
        "jetbrains_mono_bold.ttf",
        "jetbrains_mono_extrabold.ttf",
    )
}

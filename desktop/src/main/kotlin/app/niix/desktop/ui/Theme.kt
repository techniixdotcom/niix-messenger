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
 * The app's themes, the same six as on the phone and with the same colours. The choice is kept in
 * a file of its own, outside the encrypted database, because the lock screen needs it before
 * anything is unlocked, and it survives a wipe: a duress wipe that turned the app pink again would
 * show that something had happened.
 */
enum class NiixTheme(
    val key: String,
    val labelKey: String,
    val isLight: Boolean,
    val bg: String,
    val surface: String,
    val surfaceVariant: String,
    val raised: String,
    val onSurface: String,
    val muted: String,
    val faint: String,
    val primary: String,
    val onPrimary: String,
    val accentLight: String,
    val tint: Double,
    val edge: Double,
    val danger: String,
) {
    PINK("pink", "theme_pink", false, "#000000", "#121218", "#0B0B10", "#1A1A22", "#F2F2F5", "#A0A0AE", "#7A7A88", "#FF0A6C", "#FFFFFF", "#FF4D94", 0.16, 0.35, "#FF453A"),
    GREEN("green", "theme_green", false, "#000000", "#121218", "#0B0B10", "#1A1A22", "#F2F2F5", "#A0A0AE", "#7A7A88", "#00F782", "#000000", "#5CFFAE", 0.16, 0.35, "#FF453A"),
    ORANGE("orange", "theme_orange", false, "#000000", "#121218", "#0B0B10", "#1A1A22", "#F2F2F5", "#A0A0AE", "#7A7A88", "#FF8A00", "#000000", "#FFAD4D", 0.16, 0.35, "#FF453A"),
    ROYAL_BLUE("royal_blue", "theme_royal_blue", false, "#000000", "#121218", "#0B0B10", "#1A1A22", "#F2F2F5", "#A0A0AE", "#7A7A88", "#4169E1", "#FFFFFF", "#7B97EE", 0.20, 0.50, "#FF453A"),
    LIGHT("light", "theme_light", true, "#FFFFFF", "#F4F4F6", "#FAFAFB", "#E9E9EE", "#0B0B10", "#55555F", "#6B6B75", "#000000", "#FFFFFF", "#33333B", 0.08, 0.25, "#D70015"),
    DARK("dark", "theme_dark", false, "#000000", "#121218", "#0B0B10", "#1A1A22", "#F2F2F5", "#A0A0AE", "#7A7A88", "#FFFFFF", "#000000", "#D6D6DE", 0.14, 0.35, "#FF453A"),
    ;

    /** The CSS class of this theme's swatch in Settings. */
    val swatchClass: String get() = "theme-swatch-$key".replace('_', '-')
}

/**
 * NiiX's look on the desktop: the phone app's themes and its typeface, JetBrains Mono, which is
 * bundled.
 */
object Theme {

    private const val THEME_FILE = "theme"

    @Volatile
    var current: NiixTheme = NiixTheme.PINK
        private set

    private var themeFile: File? = null
    private val providers = ArrayList<CssProvider>()

    private fun css(t: NiixTheme): String = """
        @define-color accent_color ${t.accentLight};
        @define-color accent_bg_color ${t.primary};
        @define-color accent_fg_color ${t.onPrimary};
        @define-color window_bg_color ${t.bg};
        @define-color window_fg_color ${t.onSurface};
        @define-color view_bg_color ${t.bg};
        @define-color view_fg_color ${t.onSurface};
        @define-color headerbar_bg_color ${t.surfaceVariant};
        @define-color headerbar_fg_color ${t.onSurface};
        @define-color sidebar_bg_color ${t.surfaceVariant};
        @define-color sidebar_fg_color ${t.onSurface};
        @define-color card_bg_color ${t.surface};
        @define-color card_fg_color ${t.onSurface};
        @define-color dialog_bg_color ${t.surface};
        @define-color dialog_fg_color ${t.onSurface};
        @define-color popover_bg_color ${t.raised};
        @define-color popover_fg_color ${t.onSurface};
        @define-color destructive_color ${t.danger};
        window, label, entry, textview, button, row { font-family: "JetBrains Mono", monospace; }
        .niix-brand { font-weight: 800; font-size: 28pt; color: ${t.primary}; }
        .niix-subtitle { color: ${t.muted}; }
        .bubble { border-radius: 14px; padding: 8px 12px; }
        .bubble.incoming { background-color: ${t.surface}; }
        .bubble.outgoing { background-color: alpha(${t.primary}, ${t.tint}); }
        .bubble.system { background-color: transparent; color: ${t.muted}; }
        .bubble-meta { font-size: 8pt; color: ${t.faint}; }
        .bubble-sender { font-size: 9pt; font-weight: 700; color: ${t.accentLight}; }
        .bubble-quote { border-left: 3px solid ${t.primary}; padding-left: 8px; color: ${t.muted}; font-size: 9pt; }
        .hidden-message { color: ${t.faint}; font-style: italic; }
        .composer { padding: 6px; }
        .composer textview { background: transparent; }
        .composer-frame { border-radius: 18px; background-color: ${t.surface}; padding: 6px 10px; border: 1px solid alpha(${t.primary}, ${t.edge}); }
        .qr-frame { background-color: #FFFFFF; border-radius: 12px; padding: 12px; }
        .console { font-size: 9pt; color: ${t.muted}; }
        .danger { color: ${t.danger}; }
        .call-panel { background-color: alpha(${t.primary}, ${t.tint}); padding: 12px; }
        .request-row { background-color: alpha(${t.primary}, ${t.tint / 2}); }
        .theme-swatch { min-width: 22px; min-height: 22px; border-radius: 50%; border-width: 3px; border-style: solid; }
    """.trimIndent() + "\n" + NiixTheme.entries.joinToString("\n") {
        ".${it.swatchClass} { background-color: ${it.bg}; border-color: ${it.primary}; }"
    }

    /** libadwaita 1.6 and later read colours from CSS variables, which GTK before 4.16 can't parse. */
    private fun variables(t: NiixTheme): String = """
        :root {
            --accent-color: ${t.accentLight};
            --accent-bg-color: ${t.primary};
            --accent-fg-color: ${t.onPrimary};
            --window-bg-color: ${t.bg};
            --window-fg-color: ${t.onSurface};
            --view-bg-color: ${t.bg};
            --view-fg-color: ${t.onSurface};
            --headerbar-bg-color: ${t.surfaceVariant};
            --headerbar-fg-color: ${t.onSurface};
            --sidebar-bg-color: ${t.surfaceVariant};
            --sidebar-fg-color: ${t.onSurface};
            --card-bg-color: ${t.surface};
            --card-fg-color: ${t.onSurface};
            --dialog-bg-color: ${t.surface};
            --dialog-fg-color: ${t.onSurface};
            --popover-bg-color: ${t.raised};
            --popover-fg-color: ${t.onSurface};
            --destructive-color: ${t.danger};
        }
    """.trimIndent()

    /** Once, when GTK has started: the saved theme, the fonts and the icons. */
    fun apply(filesDir: File, fontCacheDir: File) {
        themeFile = File(filesDir, THEME_FILE)
        current = runCatching { themeFile?.takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            ?.let { saved -> NiixTheme.entries.firstOrNull { it.key == saved } } ?: NiixTheme.PINK
        loadBundledFonts(fontCacheDir)
        val display = Display.getDefault() ?: return
        registerIcons(display, fontCacheDir)
        restyle(display)
    }

    /** Saves [theme] and restyles everything that's open, at once. */
    fun select(theme: NiixTheme) {
        if (theme == current) return
        current = theme
        runCatching {
            themeFile?.let { file ->
                file.writeText(theme.key)
                StoragePaths.makePrivate(file)
            }
        }
        Display.getDefault()?.let(::restyle)
    }

    private fun restyle(display: Display) {
        val theme = current
        StyleManager.getDefault().setColorScheme(if (theme.isLight) ColorScheme.FORCE_LIGHT else ColorScheme.FORCE_DARK)
        val sheets = if (Gtk.getMajorVersion() > 4 || Gtk.getMinorVersion() >= 16) listOf(variables(theme), css(theme)) else listOf(css(theme))
        val fresh = sheets.map { sheet ->
            val provider = CssProvider()
            provider.loadFromString(sheet)
            Gtk.styleContextAddProviderForDisplay(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
            provider
        }
        // The new sheets go on before the old come off, so nothing is drawn unstyled in between.
        providers.forEach { Gtk.styleContextRemoveProviderForDisplay(display, it) }
        providers.clear()
        providers.addAll(fresh)
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

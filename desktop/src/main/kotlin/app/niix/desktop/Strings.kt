package app.niix.desktop

import java.io.InputStream
import java.util.Locale
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The app's text, looked up by the same names as on Android. The phone app's translations are
 * bundled as they are (see build.gradle.kts), and the few desktop-only strings live in
 * resources/strings in the same format. Lookups go from the desktop's strings to the phone's, in
 * the user's language first and then in English.
 */
object Strings {

    private class Table(
        val strings: Map<String, String>,
        val arrays: Map<String, List<String>>,
        val plurals: Map<String, Map<String, String>>,
    )

    private val locale: Locale = Locale.getDefault()
    private val language: String = locale.language.ifEmpty { "en" }

    private val chain: List<Table> by lazy {
        val folders = if (language == "en") listOf("values") else listOf("values-$language", "values")
        folders.flatMap { folder ->
            listOfNotNull(load("/strings/$folder/strings.xml"), load("/android-strings/$folder/strings.xml"))
        }
    }

    /** The string called [name], formatted with [args] the way Android's getString does. */
    fun get(name: String, vararg args: Any?): String {
        val raw = chain.firstNotNullOfOrNull { it.strings[name] } ?: return name
        return if (args.isEmpty()) raw else runCatching { String.format(locale, raw, *args) }.getOrDefault(raw)
    }

    fun array(name: String): List<String> = chain.firstNotNullOfOrNull { it.arrays[name] } ?: emptyList()

    /** A plural string for [count], chosen with the rules of the user's language. */
    fun plural(name: String, count: Int, vararg args: Any?): String {
        val category = pluralCategory(count)
        val raw = chain.firstNotNullOfOrNull { table ->
            table.plurals[name]?.let { forms -> forms[category] ?: forms["other"] }
        } ?: return name
        val formatArgs = if (args.isEmpty()) arrayOf<Any?>(count) else args
        return runCatching { String.format(locale, raw, *formatArgs) }.getOrDefault(raw)
    }

    /** CLDR plural categories for the languages NiiX is translated into. */
    private fun pluralCategory(n: Int): String {
        val mod10 = n % 10
        val mod100 = n % 100
        return when (language) {
            "fr", "fa" -> if (n == 0 || n == 1) "one" else "other"
            "ru" -> when {
                mod10 == 1 && mod100 != 11 -> "one"
                mod10 in 2..4 && mod100 !in 12..14 -> "few"
                else -> "many"
            }
            "ar" -> when {
                n == 0 -> "zero"
                n == 1 -> "one"
                n == 2 -> "two"
                mod100 in 3..10 -> "few"
                mod100 in 11..99 -> "many"
                else -> "other"
            }
            "zh", "ja", "ko", "th" -> "other"
            else -> if (n == 1) "one" else "other"
        }
    }

    private fun load(path: String): Table? {
        val stream: InputStream = Strings::class.java.getResourceAsStream(path) ?: return null
        return stream.use { input ->
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                isXIncludeAware = false
                isExpandEntityReferences = false
                isNamespaceAware = false
            }
            val root = factory.newDocumentBuilder().parse(input).documentElement
            val strings = HashMap<String, String>()
            val arrays = HashMap<String, List<String>>()
            val plurals = HashMap<String, Map<String, String>>()
            val children = root.childNodes
            for (i in 0 until children.length) {
                val element = children.item(i) as? Element ?: continue
                val name = element.getAttribute("name").takeIf { it.isNotEmpty() } ?: continue
                when (element.tagName) {
                    "string" -> strings[name] = unescape(element.textContent)
                    "string-array" -> arrays[name] = items(element).map { unescape(it.textContent) }
                    "plurals" -> plurals[name] = items(element).associate { it.getAttribute("quantity") to unescape(it.textContent) }
                }
            }
            Table(strings, arrays, plurals)
        }
    }

    private fun items(parent: Element): List<Element> {
        val nodes = parent.getElementsByTagName("item")
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    /** Android's resource string rules: escapes, quoting, and whitespace collapsing outside quotes. */
    internal fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var quoted = false
        var lastWasSpace = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    val next = raw[i + 1]
                    when (next) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> {
                            val hex = raw.substring(i + 2, minOf(i + 6, raw.length))
                            val code = hex.takeIf { it.length == 4 }?.toIntOrNull(16)
                            if (code != null) {
                                out.append(code.toChar())
                                i += 4
                            } else {
                                out.append('u')
                            }
                        }
                        else -> out.append(next)
                    }
                    lastWasSpace = false
                    i += 2
                    continue
                }
                c == '"' -> quoted = !quoted
                !quoted && c.isWhitespace() -> {
                    if (!lastWasSpace) out.append(' ')
                    lastWasSpace = true
                    i++
                    continue
                }
                else -> out.append(c)
            }
            lastWasSpace = false
            i++
        }
        return out.toString().trim()
    }
}

/** Shorthand used throughout the UI. */
fun tr(name: String, vararg args: Any?): String = Strings.get(name, *args)

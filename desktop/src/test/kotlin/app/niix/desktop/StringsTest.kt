package app.niix.desktop

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class StringsTest {

    @Test
    fun unescapesAndroidResourceText() {
        assertEquals("can't", Strings.unescape("can\\'t"))
        assertEquals("a\nb", Strings.unescape("a\\nb"))
        assertEquals("one two", Strings.unescape("  one \n   two  "))
        assertEquals("é", Strings.unescape("\\u00e9"))
    }

    @Test
    fun looksUpBundledStrings() {
        assertEquals("NiiX", Strings.get("app_name"))
        assertTrue(Strings.get("toast_failed", "Sam").contains("Sam"))
        assertNotEquals("decoy_names", Strings.array("decoy_names").firstOrNull() ?: "decoy_names")
        assertTrue(Strings.plural("duration_hours", 3).contains("3"))
        assertEquals("missing_key", Strings.get("missing_key"))
    }

    /** Every translation of a desktop string keeps the same placeholders as the English one. */
    @Test
    fun translationsKeepPlaceholders() {
        val root = File(javaClass.getResource("/strings/values/strings.xml")!!.toURI()).parentFile.parentFile
        val english = read(File(root, "values/strings.xml"))
        root.listFiles { f -> f.isDirectory && f.name.startsWith("values-") }!!.forEach { dir ->
            val translated = read(File(dir, "strings.xml"))
            assertEquals("${dir.name} has every desktop string", english.keys, translated.keys)
            english.forEach { (key, value) ->
                val expected = Regex("%\\d\\$[sd]").findAll(value).map { it.value }.toSet()
                val actual = Regex("%\\d\\$[sd]").findAll(translated.getValue(key)).map { it.value }.toSet()
                assertEquals("${dir.name}/$key placeholders", expected, actual)
            }
        }
    }

    private fun read(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val e = nodes.item(i) as Element
            e.getAttribute("name") to e.textContent
        }
    }
}

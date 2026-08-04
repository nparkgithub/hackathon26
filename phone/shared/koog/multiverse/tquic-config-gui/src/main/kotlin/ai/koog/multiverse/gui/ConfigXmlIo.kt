package ai.koog.multiverse.gui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A single editable TQUIC parameter, mirrored from the master-agent `tquic_config.xml` schema. The GUI
 * is schema-driven: it renders whatever `<param>` entries the XML declares (key, category, type,
 * default/value, choices, min/max, unit), so it always covers the full parameter set (Sec 4.2).
 */
data class TquicParam(
    val category: String,
    val key: String,
    val type: String,
    var value: String,
    val default: String,
    val choices: List<String> = emptyList(),
    val min: String? = null,
    val max: String? = null,
    val unit: String? = null,
)

/** Loads/saves the `tquic_config.xml` document as a flat list of [TquicParam], preserving categories. */
object ConfigXmlIo {

    fun load(file: File): List<TquicParam> = file.inputStream().use { parse(it.readBytes()) }

    fun parse(bytes: ByteArray): List<TquicParam> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(bytes.inputStream())
        val params = mutableListOf<TquicParam>()
        val categories = doc.getElementsByTagName("category")
        for (c in 0 until categories.length) {
            val cat = categories.item(c) as? Element ?: continue
            val catName = cat.getAttribute("name")
            val nodes = cat.getElementsByTagName("param")
            for (i in 0 until nodes.length) {
                val el = nodes.item(i) as? Element ?: continue
                val key = el.getAttribute("key")
                if (key.isBlank()) continue
                val default = el.getAttribute("default")
                val value = el.getAttribute("value").ifBlank { default }
                params += TquicParam(
                    category = catName,
                    key = key,
                    type = el.getAttribute("type").ifBlank { "string" },
                    value = value,
                    default = default,
                    choices = el.getAttribute("choices").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    min = el.getAttribute("min").ifBlank { null },
                    max = el.getAttribute("max").ifBlank { null },
                    unit = el.getAttribute("unit").ifBlank { null },
                )
            }
        }
        return params
    }

    /** Serialize edited params back to XML grouped by category (writes both default and value). */
    fun save(file: File, params: List<TquicParam>) {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tquicConfig version=\"1\">\n")
        params.groupBy { it.category }.forEach { (cat, ps) ->
            sb.append("  <category name=\"$cat\">\n")
            ps.forEach { p ->
                sb.append("    <param key=\"${p.key}\" type=\"${p.type}\" default=\"${p.default}\" value=\"${p.value}\"")
                if (p.choices.isNotEmpty()) sb.append(" choices=\"${p.choices.joinToString(",")}\"")
                p.min?.let { sb.append(" min=\"$it\"") }
                p.max?.let { sb.append(" max=\"$it\"") }
                p.unit?.let { sb.append(" unit=\"$it\"") }
                sb.append("/>\n")
            }
            sb.append("  </category>\n")
        }
        sb.append("</tquicConfig>\n")
        file.writeText(sb.toString())
    }

    /** Validate a param's current value against its declared type/min/max/choices. Returns error or null. */
    fun validate(p: TquicParam): String? {
        val v = p.value.trim()
        when (p.type) {
            "bool" -> if (v.toBooleanStrictOrNull() == null) return "must be true/false"
            "enum" -> if (p.choices.isNotEmpty() && v !in p.choices) return "must be one of ${p.choices}"
            "f64" -> v.toDoubleOrNull() ?: return "must be a number"
            "u8", "u16", "u32", "u64", "usize", "i32", "i64" -> {
                val n = v.toLongOrNull() ?: return "must be an integer"
                p.min?.toLongOrNull()?.let { if (n < it) return "must be >= $it" }
                p.max?.toLongOrNull()?.let { if (n > it) return "must be <= $it" }
            }
        }
        return null
    }
}

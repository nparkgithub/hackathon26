package ai.koog.multiverse.gui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import java.io.File

/**
 * Mutable UI state wrapping the editable parameter list and the backing XML file. XML is the source of
 * truth for defaults; the GUI edits on top and can Save back or Reset to defaults.
 */
class ConfigState(private val file: File) {
    private val defaults: List<TquicParam> = ConfigXmlIo.load(file)
    val params: SnapshotStateList<TquicParam> = defaults.map { it.copy() }.toMutableStateList()
    val fileName: String get() = file.name

    fun save() = ConfigXmlIo.save(file, params.toList())

    fun resetToDefaults() {
        params.clear()
        defaults.forEach { params.add(it.copy(value = it.default)) }
    }

    companion object {
        /** Resolve the config file: CLI arg, TQUIC_CONFIG_XML env, or a bundled fallback next to the app. */
        fun resolve(args: Array<String>): ConfigState {
            val path = args.firstOrNull()
                ?: System.getenv("TQUIC_CONFIG_XML")
                ?: "tquic_config.xml"
            val file = File(path)
            if (!file.exists()) {
                // Write a minimal starter so the GUI always opens.
                file.writeText(STARTER_XML)
            }
            return ConfigState(file)
        }

        private val STARTER_XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tquicConfig version="1">
              <category name="multipath">
                <param key="enable_multipath" type="bool" default="false"/>
                <param key="multipath_algorithm" type="enum" default="minrtt"
                       choices="minrtt,redundant,roundrobin,ecf,erf,thle,thlev2"/>
              </category>
              <category name="patfb">
                <param key="enable_path_arrival_time_feedback" type="bool" default="false"/>
              </category>
            </tquicConfig>
        """.trimIndent()
    }
}

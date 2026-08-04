package ai.koog.multiverse.gui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/**
 * Standalone Compose Desktop entry point for the TQUIC config GUI (grillme_version2 Sec 4, Module C).
 * Reads/writes the same `tquic_config.xml` the Koog Master Agent app consumes.
 *
 * Usage: pass the path to tquic_config.xml as the first arg, or set TQUIC_CONFIG_XML.
 */
fun main(args: Array<String>) = application {
    val state = ConfigState.resolve(args)
    Window(
        onCloseRequest = ::exitApplication,
        title = "Multiverse - TQUIC Configuration",
        state = rememberWindowState(width = 720.dp, height = 640.dp),
    ) {
        MaterialTheme {
            Surface {
                TquicConfigScreen(state)
            }
        }
    }
}

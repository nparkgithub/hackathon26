package ai.koog.multiverse.gui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Tabbed TQUIC config editor (grillme_version2 Sec 4). One tab per category; fields are pre-filled
 * from the XML defaults and edited on top. Save writes back to the XML; Reset reloads shipped defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TquicConfigScreen(state: ConfigState) {
    val params = state.params
    val categories = remember(params) { params.map { it.category }.distinct() }
    var tab by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("Loaded ${params.size} parameters from ${state.fileName}") }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Text("TQUIC Transport Configuration", style = MaterialTheme.typography.titleLarge)
        Text(status, style = MaterialTheme.typography.bodySmall)

        ScrollableTabRow(selectedTabIndex = tab) {
            categories.forEachIndexed { i, c ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(c) })
            }
        }

        val current = categories.getOrNull(tab)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            params.forEachIndexed { idx, p ->
                if (p.category == current) ParamRow(params, idx)
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val errors = params.mapNotNull { p -> ConfigXmlIo.validate(p)?.let { "${p.key}: $it" } }
                status = if (errors.isEmpty()) {
                    state.save(); "Saved ${params.size} parameters to ${state.fileName}"
                } else {
                    "Cannot save - ${errors.size} invalid: ${errors.first()}"
                }
            }) { Text("Save") }

            Button(onClick = { state.resetToDefaults(); status = "Reset to defaults" }) {
                Text("Reset to defaults")
            }
        }
    }
}

@Composable
private fun ParamRow(params: SnapshotStateList<TquicParam>, index: Int) {
    val p = params[index]
    val label = buildString {
        append(p.key)
        p.unit?.let { append(" ($it)") }
    }
    val error = ConfigXmlIo.validate(p)

    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, Modifier.width(320.dp), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)

        val choices = when {
            p.type == "enum" && p.choices.isNotEmpty() -> p.choices
            p.type == "bool" -> listOf("true", "false")
            else -> emptyList()
        }

        if (choices.isNotEmpty()) {
            EnumDropdown(p.value, choices) { params[index] = p.copy(value = it) }
        } else {
            OutlinedTextField(
                value = p.value,
                onValueChange = { params[index] = p.copy(value = it) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.width(280.dp),
            )
        }
    }
}

@Composable
private fun EnumDropdown(value: String, choices: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Button(onClick = { expanded = true }, modifier = Modifier.width(200.dp)) { Text(value.ifBlank { "(select)" }) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { c ->
                DropdownMenuItem(text = { Text(c) }, onClick = { onSelect(c); expanded = false })
            }
        }
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package pet.toasty.vmpowerphone

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class ConsoleSettings(
    val resizeGuest: Boolean = false,
    val scaling: Int = 0,          // 0 = fit, 1 = fill (crop), 2 = stretch
    val quality: Int = 9,          // 0..9
    val compression: Int = 0,      // 0 = raw, 1..9 = ZRLE
    val trackpad: Boolean = false,
    val sensitivity: Float = 1.5f,
    val reverseScroll: Boolean = false,
    val showKeys: Boolean = true
)

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("console_settings", Context.MODE_PRIVATE)

    fun load() = ConsoleSettings(
        resizeGuest = p.getBoolean("resize", false),
        scaling = p.getInt("scaling", 0),
        quality = p.getInt("quality", 9),
        compression = p.getInt("compression", 0),
        trackpad = p.getBoolean("trackpad", false),
        sensitivity = p.getFloat("sensitivity", 1.5f),
        reverseScroll = p.getBoolean("reverseScroll", false),
        showKeys = p.getBoolean("showKeys", true)
    )

    fun save(s: ConsoleSettings) {
        p.edit()
            .putBoolean("resize", s.resizeGuest)
            .putInt("scaling", s.scaling)
            .putInt("quality", s.quality)
            .putInt("compression", s.compression)
            .putBoolean("trackpad", s.trackpad)
            .putFloat("sensitivity", s.sensitivity)
            .putBoolean("reverseScroll", s.reverseScroll)
            .putBoolean("showKeys", s.showKeys)
            .apply()
    }
}

private fun qualityHint(q: Int): String = when {
    q <= 3 -> "8-bit color, smallest"
    q <= 6 -> "16-bit color"
    else -> "24-bit color, best"
}

private fun compressionHint(c: Int): String = if (c == 0) "off (Raw)" else "ZRLE, level $c"

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val mod = Modifier.weight(1f).heightIn(min = 52.dp)
            if (i == selected) {
                Button(
                    onClick = { onSelect(i) },
                    modifier = mod,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(label, style = MaterialTheme.typography.titleSmall) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(i) },
                    modifier = mod,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text(label, style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}

/** Slider that only applies the value when you let go (changing it reconnects the console). */
@Composable
private fun CommitSlider(title: String, value: Int, hint: (Int) -> String, onCommit: (Int) -> Unit) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$title: ${v.toInt()}  ·  ${hint(v.toInt())}", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = v,
        onValueChange = { v = it },
        onValueChangeFinished = { onCommit(v.toInt()) },
        valueRange = 0f..9f,
        steps = 8
    )
}

@Composable
fun ConsoleSettingsPanel(settings: ConsoleSettings, onSettings: (ConsoleSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Screen", style = MaterialTheme.typography.titleSmall)
        Text("VM scaling", style = MaterialTheme.typography.bodyLarge)
        ChoiceRow(listOf("Fit", "Fill", "Stretch"), settings.scaling) {
            onSettings(settings.copy(scaling = it))
        }
        Text(
            when (settings.scaling) {
                1 -> "Fills the whole area and crops the edges."
                2 -> "Fills the whole area and distorts the picture."
                else -> "Shows the whole screen, with bars if the shapes differ."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SwitchRow(
            "Resize VM screen to my phone",
            "Asks the VM to change its resolution to fit this screen. The VM must support it, otherwise the picture is just scaled.",
            settings.resizeGuest
        ) { onSettings(settings.copy(resizeGuest = it)) }

        Text("Picture", style = MaterialTheme.typography.titleSmall)
        CommitSlider("Quality", settings.quality, ::qualityHint) {
            onSettings(settings.copy(quality = it))
        }
        CommitSlider("Compression", settings.compression, ::compressionHint) {
            onSettings(settings.copy(compression = it))
        }
        Text(
            "Lower quality uses fewer colors and less data. Compression above 0 uses ZRLE, which sends far less data than Raw. Changing either one reconnects the console.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Pointer", style = MaterialTheme.typography.titleSmall)
        ChoiceRow(listOf("Touch", "Trackpad"), if (settings.trackpad) 1 else 0) {
            onSettings(settings.copy(trackpad = it == 1))
        }
        Text(
            if (settings.trackpad)
                "Drag to move the cursor. Tap to click, tap with two fingers to right-click, double-tap and drag to drag, two-finger drag to scroll."
            else
                "The cursor goes where you touch. Tap to click, long-press to right-click, drag to drag, two-finger drag to scroll.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (settings.trackpad) {
            var speed by remember(settings.sensitivity) { mutableFloatStateOf(settings.sensitivity) }
            Text("Pointer speed: ${"%.1f".format(speed)}x", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = speed,
                onValueChange = { speed = it },
                onValueChangeFinished = { onSettings(settings.copy(sensitivity = speed)) },
                valueRange = 0.5f..4f
            )
        }
        SwitchRow("Reverse two-finger scroll", null, settings.reverseScroll) {
            onSettings(settings.copy(reverseScroll = it))
        }

        Text("Keyboard", style = MaterialTheme.typography.titleSmall)
        SwitchRow(
            "Show extra keys bar",
            "Esc, Tab, arrows, Ctrl/Alt/Shift/Super toggles and more.",
            settings.showKeys
        ) { onSettings(settings.copy(showKeys = it)) }
        Text(
            "Tap the keyboard button in the console to type with the phone keyboard. A USB or Bluetooth keyboard and mouse work automatically.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SettingsPage(settings: ConsoleSettings, onSettings: (ConsoleSettings) -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Console", style = MaterialTheme.typography.titleMedium)
                ConsoleSettingsPanel(settings, onSettings)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun AccountsPage(
    accounts: Accounts,
    avatars: AvatarStore,
    avatarTick: Int,
    onSwitch: (Int) -> Unit,
    onAddAccount: () -> Unit,
    onLogout: (Int) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    val current = accounts.current
    val cardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
    )
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Accounts", style = MaterialTheme.typography.headlineMedium)

        if (current != null) {
            Card(shape = MaterialTheme.shapes.extraLarge, colors = cardColors, modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AccountAvatar(avatars, avatarTick, current, 112.dp, Modifier.clickable { onPickPhoto() })
                    Text(current.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        current.server.removePrefix("https://").removePrefix("http://"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            onClick = onPickPhoto,
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) { Text("Change photo", style = MaterialTheme.typography.titleSmall) }
                        OutlinedButton(
                            onClick = onRemovePhoto,
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) { Text("Remove", style = MaterialTheme.typography.titleSmall) }
                    }
                    Text(
                        "Photos are stored only on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text("Switch account", style = MaterialTheme.typography.titleMedium)
        accounts.list.forEachIndexed { i, a ->
            Card(
                shape = MaterialTheme.shapes.extraLarge,
                colors = cardColors,
                modifier = Modifier.fillMaxWidth().clickable { onSwitch(i) }
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    AccountAvatar(avatars, avatarTick, a, 48.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.label, style = MaterialTheme.typography.titleMedium)
                        Text(
                            a.server.removePrefix("https://").removePrefix("http://"),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    if (i == accounts.active) {
                        Text(
                            "Active",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    TextButton(
                        onClick = { onLogout(i) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Log out") }
                }
            }
        }

        Button(
            onClick = onAddAccount,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        ) { Text("Add account", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(16.dp))
    }
}

@file:OptIn(ExperimentalMaterial3Api::class)

package pet.toasty.vmpowerphone

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
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
    val compression: Int = 6,      // 0..9, sent to the server as a hint
    val encoding: Int = 0,         // 0 = Raw, 1 = ZRLE, 2 = Tight, 3 = Auto
    val showStats: Boolean = true,
    val trackpad: Boolean = false,
    val sensitivity: Float = 1.5f,
    val reverseScroll: Boolean = false,
    val showKeys: Boolean = true,
    val glass: Boolean = true,         // blur + transparency
    val haptics: Boolean = true,       // all vibration feedback
    val bgHaptics: Boolean = true      // ticks while dragging over the background
)

class SettingsStore(context: Context) {
    private val p = context.getSharedPreferences("console_settings", Context.MODE_PRIVATE)

    fun load() = ConsoleSettings(
        resizeGuest = p.getBoolean("resize", false),
        scaling = p.getInt("scaling", 0),
        quality = p.getInt("quality", 9),
        compression = p.getInt("compression", 6),
        // older versions: compression above 0 meant ZRLE, 0 meant Raw
        encoding = if (p.contains("encoding")) p.getInt("encoding", 0)
                   else if (p.getInt("compression", 0) > 0) 1 else 0,
        showStats = p.getBoolean("showStats", true),
        trackpad = p.getBoolean("trackpad", false),
        sensitivity = p.getFloat("sensitivity", 1.5f),
        reverseScroll = p.getBoolean("reverseScroll", false),
        showKeys = p.getBoolean("showKeys", true),
        glass = p.getBoolean("glass", true),
        haptics = p.getBoolean("haptics", true),
        bgHaptics = p.getBoolean("bgHaptics", true)
    )

    fun save(s: ConsoleSettings) {
        p.edit()
            .putBoolean("resize", s.resizeGuest)
            .putInt("scaling", s.scaling)
            .putInt("quality", s.quality)
            .putInt("compression", s.compression)
            .putInt("encoding", s.encoding)
            .putBoolean("showStats", s.showStats)
            .putBoolean("trackpad", s.trackpad)
            .putFloat("sensitivity", s.sensitivity)
            .putBoolean("reverseScroll", s.reverseScroll)
            .putBoolean("showKeys", s.showKeys)
            .putBoolean("glass", s.glass)
            .putBoolean("haptics", s.haptics)
            .putBoolean("bgHaptics", s.bgHaptics)
            .apply()
    }
}

private fun qualityHint(q: Int, encoding: Int): String =
    if (encoding == 2) "JPEG quality, 24-bit color"
    else when {
        q <= 3 -> "8-bit color, smallest"
        q <= 6 -> "16-bit color"
        else -> "24-bit color, best"
    }

private fun compressionHint(c: Int, encoding: Int): String = when (encoding) {
    0 -> "not used with Raw"
    1 -> "$c (the server may ignore this for ZRLE)"
    else -> "$c (0 fastest, 9 smallest)"
}

private fun encodingHelp(e: Int): String = when (e) {
    0 -> "Raw: no compression. Simple and fine on a fast network, but it uses a lot of data."
    1 -> "ZRLE: compressed tiles, good on slow links. Many servers, QEMU included, use a fixed compression level for it."
    2 -> "Tight: the compression slider really changes how hard the server compresses, and it can use JPEG. Needs 24-bit color."
    else -> "Auto: tries Tight, then ZRLE, then Raw. Tight is skipped when quality is 6 or lower."
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .heightIn(min = 56.dp)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    // Pressing one button makes it grow while its neighbours shrink
    var pressedIdx by remember { mutableIntStateOf(-1) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val w by animateFloatAsState(
                targetValue = when {
                    pressedIdx == i -> 1.3f
                    pressedIdx >= 0 -> 0.85f
                    else -> 1f
                },
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
                label = "width"
            )
            val mod = Modifier.weight(w).heightIn(min = 52.dp)
            val press = { pressed: Boolean ->
                pressedIdx = if (pressed) i else if (pressedIdx == i) -1 else pressedIdx
            }
            if (i == selected) {
                ExpressiveButton(
                    onClick = { onSelect(i) },
                    modifier = mod,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    onPressChange = press
                ) { Text(label, style = MaterialTheme.typography.titleSmall) }
            } else {
                ExpressiveOutlinedButton(
                    onClick = { onSelect(i) },
                    modifier = mod,
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    onPressChange = press
                ) { Text(label, style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}

/** Slider that only applies the value when you let go (changing it reconnects the console). */
@Composable
private fun CommitSlider(
    title: String,
    value: Int,
    hint: (Int) -> String,
    enabled: Boolean = true,
    onCommit: (Int) -> Unit
) {
    var v by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Text("$title: ${v.toInt()}  ·  ${hint(v.toInt())}", style = MaterialTheme.typography.bodyLarge)
    Slider(
        value = v,
        onValueChange = { v = it },
        onValueChangeFinished = { onCommit(v.toInt()) },
        enabled = enabled,
        valueRange = 0f..9f,
        steps = 8
    )
}

@Composable
fun AppearancePanel(settings: ConsoleSettings, onSettings: (ConsoleSettings) -> Unit) {
    val haptics = LocalHaptics.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SwitchRow(
            "Blur and transparency",
            "Frosted glass look. Turn this off if the app feels slow.",
            settings.glass
        ) { onSettings(settings.copy(glass = it)) }
        SwitchRow(
            "Haptic feedback",
            "Vibration on buttons and actions. It is always off inside the console.",
            settings.haptics
        ) { onSettings(settings.copy(haptics = it)) }
        SwitchRow(
            "Background touch haptics",
            "Light ticks while you drag across the animated background.",
            settings.bgHaptics,
            enabled = settings.haptics
        ) { onSettings(settings.copy(bgHaptics = it)) }
        ExpressiveTonalButton(
            onClick = { haptics.confirm() },
            enabled = settings.haptics,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        ) { Text("Test haptics", style = MaterialTheme.typography.titleMedium) }
        Text(
            "If the test does nothing, check Android's Settings > Sound & vibration > Vibration & haptics.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
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
        Text("Encoding", style = MaterialTheme.typography.bodyLarge)
        ChoiceRow(listOf("Raw", "ZRLE", "Tight", "Auto"), settings.encoding) {
            onSettings(settings.copy(encoding = it))
        }
        Text(
            encodingHelp(settings.encoding),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        CommitSlider("Quality", settings.quality, { qualityHint(it, settings.encoding) }) {
            onSettings(settings.copy(quality = it))
        }
        CommitSlider(
            "Compression", settings.compression, { compressionHint(it, settings.encoding) },
            enabled = settings.encoding != 0
        ) {
            onSettings(settings.copy(compression = it))
        }
        SwitchRow(
            "Show connection stats",
            "Shows the encoding in use, data rate and frame rate in the console toolbar, so you can see what a change does.",
            settings.showStats
        ) { onSettings(settings.copy(showStats = it)) }
        Text(
            "Changing the encoding, quality or compression reconnects the console.",
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
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Appearance and haptics", style = MaterialTheme.typography.titleMedium)
                AppearancePanel(settings, onSettings)
            }
        }
        GlassCard(modifier = Modifier.fillMaxWidth()) {
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
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            "Accounts",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        if (current != null) {
            GlassCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    AccountAvatar(avatars, avatarTick, current, 112.dp, Modifier.expressiveClickable { onPickPhoto() })
                    Text(current.label, style = MaterialTheme.typography.titleLarge)
                    Text(
                        current.server.removePrefix("https://").removePrefix("http://"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExpressiveTonalButton(
                            onClick = onPickPhoto,
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) { Text("Change photo", style = MaterialTheme.typography.titleSmall) }
                        ExpressiveOutlinedButton(
                            onClick = onRemovePhoto,
                            modifier = Modifier.heightIn(min = 52.dp)
                        ) { Text("Remove", style = MaterialTheme.typography.titleSmall) }
                    }
                    Text(
                        "Your photo is saved on the server and on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Text(
            "Switch account",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        accounts.list.forEachIndexed { i, a ->
            GlassCard(modifier = Modifier.fillMaxWidth().expressiveClickable { onSwitch(i) }) {
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
                    ExpressiveTextButton(
                        onClick = { onLogout(i) },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text("Log out") }
                }
            }
        }

        ExpressiveButton(
            onClick = onAddAccount,
            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
        ) { Text("Add account", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(16.dp))
    }
}

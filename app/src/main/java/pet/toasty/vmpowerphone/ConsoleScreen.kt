@file:OptIn(ExperimentalMaterial3Api::class)

package pet.toasty.vmpowerphone

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent as AKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ---------- Scaling ----------

private class Viewport(val sx: Float, val sy: Float, val ox: Float, val oy: Float)

/** mode: 0 = fit, 1 = fill (crop), 2 = stretch */
private fun computeViewport(bw: Int, bh: Int, vw: Int, vh: Int, mode: Int): Viewport {
    if (bw <= 0 || bh <= 0 || vw <= 0 || vh <= 0) return Viewport(1f, 1f, 0f, 0f)
    val fx = vw / bw.toFloat()
    val fy = vh / bh.toFloat()
    return when (mode) {
        2 -> Viewport(fx, fy, 0f, 0f)
        1 -> {
            val s = maxOf(fx, fy)
            Viewport(s, s, (vw - bw * s) / 2f, (vh - bh * s) / 2f)
        }
        else -> {
            val s = minOf(fx, fy)
            Viewport(s, s, (vw - bw * s) / 2f, (vh - bh * s) / 2f)
        }
    }
}

// ---------- Physical keyboard -> X11 keysyms ----------

private fun keysymForKey(e: KeyEvent): Int? {
    val ke = e.nativeKeyEvent
    return when (val code = ke.keyCode) {
        AKeyEvent.KEYCODE_DEL -> 0xFF08
        AKeyEvent.KEYCODE_ENTER, AKeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D
        AKeyEvent.KEYCODE_TAB -> 0xFF09
        AKeyEvent.KEYCODE_ESCAPE -> 0xFF1B
        AKeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
        AKeyEvent.KEYCODE_INSERT -> 0xFF63
        AKeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
        AKeyEvent.KEYCODE_MOVE_END -> 0xFF57
        AKeyEvent.KEYCODE_PAGE_UP -> 0xFF55
        AKeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
        AKeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
        AKeyEvent.KEYCODE_DPAD_UP -> 0xFF52
        AKeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
        AKeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
        AKeyEvent.KEYCODE_SHIFT_LEFT -> 0xFFE1
        AKeyEvent.KEYCODE_SHIFT_RIGHT -> 0xFFE2
        AKeyEvent.KEYCODE_CTRL_LEFT -> 0xFFE3
        AKeyEvent.KEYCODE_CTRL_RIGHT -> 0xFFE4
        AKeyEvent.KEYCODE_CAPS_LOCK -> 0xFFE5
        AKeyEvent.KEYCODE_ALT_LEFT -> 0xFFE9
        AKeyEvent.KEYCODE_ALT_RIGHT -> 0xFFEA
        AKeyEvent.KEYCODE_META_LEFT -> 0xFFEB
        AKeyEvent.KEYCODE_META_RIGHT -> 0xFFEC
        in AKeyEvent.KEYCODE_F1..AKeyEvent.KEYCODE_F12 -> 0xFFBE + (code - AKeyEvent.KEYCODE_F1)
        else -> {
            val cp = ke.getUnicodeChar(ke.metaState and AKeyEvent.META_SHIFT_MASK)
            when {
                cp < 0x20 || cp > 0x10FFFF -> null
                cp <= 0xFF -> cp
                else -> 0x01000000 + cp
            }
        }
    }
}

private fun handleKey(client: VncClient, e: KeyEvent): Boolean {
    if (e.nativeKeyEvent.keyCode == AKeyEvent.KEYCODE_BACK) return false
    val sym = keysymForKey(e) ?: return false
    when (e.type) {
        KeyEventType.KeyDown -> client.key(sym, true)
        KeyEventType.KeyUp -> client.key(sym, false)
        else -> {}
    }
    return true
}

// ---------- Touch gestures (touch mode and trackpad mode) ----------

private fun Modifier.vncTouch(
    settings: ConsoleSettings,
    client: VncClient,
    cursor: MutableState<Offset>,
    map: (Offset) -> Pair<Int, Int>?,
    dims: () -> IntSize,
    scale: () -> Float
): Modifier = pointerInput(client, settings.trackpad, settings.sensitivity, settings.reverseScroll) {
    var lastTapUp = 0L
    var cx = 0f
    var cy = 0f
    var cursorInit = false

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type == PointerType.Mouse) {            // a real mouse is handled elsewhere
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        val d = dims()
        if (d.width == 0) {
            waitForUpOrCancellation()
            return@awaitEachGesture
        }
        val trackpad = settings.trackpad
        if (trackpad && !cursorInit) {
            cx = d.width / 2f
            cy = d.height / 2f
            cursorInit = true
            cursor.value = Offset(cx, cy)
        }

        val slop = viewConfiguration.touchSlop
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        val startWall = SystemClock.uptimeMillis()
        val startPos = down.position
        val doubleTapDrag = trackpad && (startWall - lastTapUp) < 300

        var moved = false
        var dragging = false
        var twoFinger = false
        var scrolled = false
        var longPressed = false
        var scrollAcc = 0f

        if (doubleTapDrag) {
            dragging = true
            client.pointer(cx.toInt(), cy.toInt(), 1)
        }

        while (true) {
            val remaining = longPressMs - (SystemClock.uptimeMillis() - startWall)
            val canLongPress = !moved && !twoFinger && !dragging && !longPressed
            val event = if (canLongPress) {
                withTimeoutOrNull(remaining.coerceAtLeast(1L)) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {                          // long press -> right click
                longPressed = true
                if (trackpad) client.click(cx.toInt(), cy.toInt(), 4)
                else map(startPos)?.let { client.click(it.first, it.second, 4) }
                continue
            }

            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break

            if (pressed.size >= 2) {                      // two-finger scroll
                twoFinger = true
                val dy = pressed.map { it.position.y - it.previousPosition.y }.average().toFloat()
                scrollAcc += dy
                val step = 36f
                val at = if (trackpad) cx.toInt() to cy.toInt() else (map(pressed[0].position) ?: (0 to 0))
                while (kotlin.math.abs(scrollAcc) >= step) {
                    val fingerDown = scrollAcc > 0
                    val wheelUp = if (settings.reverseScroll) !fingerDown else fingerDown
                    client.wheel(at.first, at.second, wheelUp)
                    scrollAcc -= if (fingerDown) step else -step
                    scrolled = true
                }
                pressed.forEach { it.consume() }
                continue
            }
            if (twoFinger) continue

            val c = pressed[0]
            if (!moved && (c.position - startPos).getDistance() > slop) moved = true
            if (moved || dragging) {
                if (trackpad) {
                    val k = settings.sensitivity / scale().coerceAtLeast(0.01f)
                    val delta = c.position - c.previousPosition
                    cx = (cx + delta.x * k).coerceIn(0f, d.width - 1f)
                    cy = (cy + delta.y * k).coerceIn(0f, d.height - 1f)
                    cursor.value = Offset(cx, cy)
                    client.pointer(cx.toInt(), cy.toInt(), if (dragging) 1 else 0)
                } else {
                    val m = map(c.position)
                    if (m != null) {
                        if (!dragging) {
                            map(startPos)?.let { client.pointer(it.first, it.second, 1) }
                            dragging = true
                        }
                        client.pointer(m.first, m.second, 1)
                    }
                }
                c.consume()
            }
        }

        // all fingers are up
        if (dragging) {
            if (trackpad) client.pointer(cx.toInt(), cy.toInt(), 0) else client.releasePointer()
        } else if (twoFinger && !scrolled) {              // two-finger tap -> right click
            if (trackpad) client.click(cx.toInt(), cy.toInt(), 4)
            else map(startPos)?.let { client.click(it.first, it.second, 4) }
        } else if (!moved && !twoFinger && !longPressed) { // tap -> left click
            if (trackpad) client.click(cx.toInt(), cy.toInt(), 1)
            else map(startPos)?.let { client.click(it.first, it.second, 1) }
            lastTapUp = SystemClock.uptimeMillis()
        }
    }
}

// ---------- Physical mouse ----------

private fun Modifier.vncMouse(client: VncClient, map: (Offset) -> Pair<Int, Int>?): Modifier =
    pointerInput(client) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                val c = e.changes.firstOrNull() ?: continue
                if (c.type != PointerType.Mouse) continue
                val p = map(c.position) ?: continue
                if (e.type == PointerEventType.Scroll) {
                    val dy = c.scrollDelta.y
                    if (dy != 0f) client.wheel(p.first, p.second, dy < 0)
                    c.consume()
                } else {
                    var m = 0
                    if (e.buttons.isPrimaryPressed) m = m or 1
                    if (e.buttons.isTertiaryPressed) m = m or 2
                    if (e.buttons.isSecondaryPressed) m = m or 4
                    client.pointer(p.first, p.second, m)
                }
            }
        }
    }

// ---------- Big key buttons ----------

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = 52.dp).widthIn(min = 56.dp),
        contentPadding = PaddingValues(horizontal = 18.dp)
    ) { Text(label, style = MaterialTheme.typography.titleMedium) }
}

@Composable
private fun ModKey(label: String, sym: Int, client: VncClient) {
    var on by remember(client) { mutableStateOf(false) }
    val mod = Modifier.heightIn(min = 52.dp).widthIn(min = 64.dp)
    if (on) {
        FilledTonalButton(
            onClick = { on = false; client.key(sym, false) },
            modifier = mod,
            contentPadding = PaddingValues(horizontal = 18.dp)
        ) { Text(label, style = MaterialTheme.typography.titleMedium) }
    } else {
        OutlinedButton(
            onClick = { on = true; client.key(sym, true) },
            modifier = mod,
            contentPadding = PaddingValues(horizontal = 18.dp)
        ) { Text(label, style = MaterialTheme.typography.titleMedium) }
    }
}

// ---------- Console screen ----------

@Composable
fun NativeConsole(
    server: String,
    vm: String,
    token: String,
    settings: ConsoleSettings,
    onSettings: (ConsoleSettings) -> Unit,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vnc", Context.MODE_PRIVATE) }
    val passKey = "$server|$vm"

    var password by remember { mutableStateOf(prefs.getString(passKey, null)) }
    var needPassword by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Connecting...") }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var canResize by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var kbOpen by remember { mutableStateOf(false) }
    val cursor = remember { mutableStateOf(Offset.Zero) }
    val scalingNow = rememberUpdatedState(settings.scaling)

    val rootFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val sentinel = "\u200B\u200B\u200B\u200B"
    var field by remember { mutableStateOf(TextFieldValue(sentinel, TextRange(sentinel.length))) }

    // No haptics or background reaction while the console is open
    DisposableEffect(Unit) {
        consoleActive.value = true
        onDispose { consoleActive.value = false }
    }

    // A new connection is made when quality or compression change
    val client = remember(attempt, settings.quality, settings.compression) {
        VncClient(
            url = "$server/vnc/$vm",
            token = token,
            password = password,
            quality = settings.quality,
            compression = settings.compression,
            onFrame = { status = ""; tick++ },
            onResizeSupported = { canResize = true },
            onEnd = { msg ->
                when (msg) {
                    NEED_PASSWORD -> { needPassword = true; status = "Password required" }
                    WRONG_PASSWORD -> {
                        prefs.edit().remove(passKey).apply()
                        password = null
                        needPassword = true
                        status = "Wrong password"
                    }
                    else -> status = msg
                }
            }
        )
    }
    DisposableEffect(client) { client.start(); onDispose { client.stop() } }
    LaunchedEffect(client) { status = "Connecting..." }

    // Keep keyboard focus on the console so a physical keyboard works
    LaunchedEffect(needPassword) { if (!needPassword) runCatching { rootFocus.requestFocus() } }

    // Phone keyboard on/off
    LaunchedEffect(kbOpen) {
        if (kbOpen) {
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        } else {
            keyboard?.hide()
            runCatching { rootFocus.requestFocus() }
        }
    }

    // Ask the VM to match this screen (debounced, not while the phone keyboard is up)
    LaunchedEffect(settings.resizeGuest, canResize, viewSize, client, kbOpen) {
        if (settings.resizeGuest && canResize && !kbOpen && viewSize.width > 0 && viewSize.height > 0) {
            delay(600)
            val f = minOf(1f, 1600f / maxOf(viewSize.width, viewSize.height))
            val w = (((viewSize.width * f).toInt()) / 8 * 8).coerceAtLeast(320)
            val h = (((viewSize.height * f).toInt()) / 8 * 8).coerceAtLeast(240)
            client.requestResize(w, h)
        }
    }

    fun viewport(): Viewport? {
        val b = client.bitmap ?: return null
        return computeViewport(b.width, b.height, viewSize.width, viewSize.height, scalingNow.value)
    }

    fun map(p: Offset): Pair<Int, Int>? {
        val b = client.bitmap ?: return null
        val v = viewport() ?: return null
        return ((p.x - v.ox) / v.sx).toInt().coerceIn(0, b.width - 1) to
                ((p.y - v.oy) / v.sy).toInt().coerceIn(0, b.height - 1)
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Console settings") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ConsoleSettingsPanel(settings, onSettings)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { showSettings = false },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Done", style = MaterialTheme.typography.titleMedium) }
            }
        )
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .systemBarsPadding()
            .imePadding()
            .onPreviewKeyEvent { handleKey(client, it) }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 52.dp)) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Close", style = MaterialTheme.typography.titleMedium)
            }
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
            )
            if (!needPassword) {
                if (kbOpen) {
                    FilledTonalIconButton(onClick = { kbOpen = false }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard", modifier = Modifier.size(28.dp))
                    }
                } else {
                    IconButton(onClick = { kbOpen = true }, modifier = Modifier.size(52.dp)) {
                        Icon(Icons.Rounded.Keyboard, contentDescription = "Keyboard", modifier = Modifier.size(28.dp))
                    }
                }
            }
            IconButton(onClick = { showSettings = true }, modifier = Modifier.size(52.dp)) {
                Icon(Icons.Rounded.Settings, contentDescription = "Console settings", modifier = Modifier.size(28.dp))
            }
        }

        if (needPassword) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("VNC password for $vm", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        prefs.edit().putString(passKey, passwordInput).apply()
                        password = passwordInput
                        passwordInput = ""
                        needPassword = false
                        status = "Connecting..."
                        attempt++
                    },
                    enabled = passwordInput.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                ) { Text("Connect", style = MaterialTheme.typography.titleMedium) }
            }
        } else {
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clipToBounds()
                    .onSizeChanged { viewSize = it }
                    .vncTouch(
                        settings = settings,
                        client = client,
                        cursor = cursor,
                        map = { map(it) },
                        dims = { client.bitmap?.let { IntSize(it.width, it.height) } ?: IntSize.Zero },
                        scale = { viewport()?.let { (it.sx + it.sy) / 2f } ?: 1f }
                    )
                    .vncMouse(client) { map(it) }
            ) {
                tick // redraw whenever a new frame arrives
                val bmp = client.bitmap ?: return@Canvas
                val v = computeViewport(
                    bmp.width, bmp.height, size.width.toInt(), size.height.toInt(), settings.scaling
                )
                drawImage(
                    bmp.asImageBitmap(),
                    dstOffset = IntOffset(v.ox.toInt(), v.oy.toInt()),
                    dstSize = IntSize((bmp.width * v.sx).toInt(), (bmp.height * v.sy).toInt())
                )
                if (settings.trackpad) {
                    val c = cursor.value
                    val center = Offset(v.ox + c.x * v.sx, v.oy + c.y * v.sy)
                    drawCircle(color = Color.Black, radius = 10f, center = center, style = Stroke(width = 3f))
                    drawCircle(color = Color.White, radius = 7f, center = center)
                }
            }

            if (settings.showKeys) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModKey("Ctrl", 0xFFE3, client)
                    ModKey("Alt", 0xFFE9, client)
                    ModKey("Shift", 0xFFE1, client)
                    ModKey("Super", 0xFFEB, client)
                    KeyButton("Esc") { client.tap(0xFF1B) }
                    KeyButton("Tab") { client.tap(0xFF09) }
                    KeyButton("←") { client.tap(0xFF51) }
                    KeyButton("↑") { client.tap(0xFF52) }
                    KeyButton("↓") { client.tap(0xFF54) }
                    KeyButton("→") { client.tap(0xFF53) }
                    KeyButton("Enter") { client.tap(0xFF0D) }
                    KeyButton("⌫") { client.tap(0xFF08) }
                    KeyButton("Del") { client.tap(0xFFFF) }
                    KeyButton("Home") { client.tap(0xFF50) }
                    KeyButton("End") { client.tap(0xFF57) }
                    KeyButton("PgUp") { client.tap(0xFF55) }
                    KeyButton("PgDn") { client.tap(0xFF56) }
                    KeyButton("Ctrl+Alt+Del") { client.ctrlAltDel() }
                }
            }

            // Invisible field that receives the phone keyboard's input
            if (kbOpen) {
                BasicTextField(
                    value = field,
                    onValueChange = { new ->
                        val t = new.text
                        when {
                            t.length > sentinel.length -> client.type(t.substring(sentinel.length))
                            t.length < sentinel.length ->
                                repeat(sentinel.length - t.length) { client.tap(0xFF08) }
                        }
                        field = TextFieldValue(sentinel, TextRange(sentinel.length))
                    },
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Send
                    ),
                    keyboardActions = KeyboardActions(onSend = { client.tap(0xFF0D) }),
                    modifier = Modifier.size(1.dp).alpha(0f).focusRequester(fieldFocus)
                )
            }
        }
    }
}
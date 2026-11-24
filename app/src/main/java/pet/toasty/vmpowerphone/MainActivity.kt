@file:OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)

package pet.toasty.vmpowerphone

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.star
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Set while the console is open, so the background ignores touches (no haptics). */
val consoleActive = mutableStateOf(false)

/** A vmpower://login link waiting to be confirmed. */
val pendingLink = mutableStateOf<String?>(null)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) pendingLink.value = intent?.dataString
        setContent { VmTheme { Root() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingLink.value = intent.dataString
    }
}

// ---------- Network ----------

suspend fun request(method: String, path: String, server: String, token: String): Pair<Int, String> =
    withContext(Dispatchers.IO) {
        val conn = URL(server + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 10_000
            conn.readTimeout = 70_000
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            code to (stream?.bufferedReader()?.readText() ?: "")
        } finally {
            conn.disconnect()
        }
    }

/** Returns (http code, username). Code -1 means the server couldn't be reached. */
suspend fun verify(server: String, token: String): Pair<Int, String?> =
    try {
        val (code, body) = request("GET", "/me", server, token)
        when (code) {
            200 -> 200 to runCatching { JSONObject(body).getString("user") }.getOrNull()
            404 -> request("GET", "/list", server, token).first to null // older server without /me
            else -> code to null
        }
    } catch (e: Exception) {
        -1 to null
    }

// ---------- Grouping "vm-start" / "vm-stop" into one card ----------

data class VmAction(val label: String, val command: String, val isStop: Boolean)
data class VmGroup(val title: String, val actions: List<VmAction>)

fun groupCommands(names: List<String>): List<VmGroup> {
    val map = linkedMapOf<String, MutableList<VmAction>>()
    names.forEach { n ->
        val base = n.removeSuffix("-start").removeSuffix("-stop")
        val action = when {
            n.endsWith("-start") -> VmAction("Start", n, false)
            n.endsWith("-stop") -> VmAction("Force off", n, true)
            else -> VmAction("Run", n, false)
        }
        map.getOrPut(base) { mutableListOf() }.add(action)
    }
    return map.map { (title, actions) -> VmGroup(title, actions.sortedBy { it.isStop }) }
}

// ---------- Theme ----------

@Composable
fun VmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialExpressiveTheme(
        colorScheme = colors,
        motionScheme = MotionScheme.expressive(),
        content = content
    )
}

// ---------- Animated, touch-reactive, haptic background ----------

private class Blob(
    val baseX: Float,
    val baseY: Float,
    val size: Float,
    val morphMs: Int,
    val driftMs: Int,
    val spinMs: Int,
    val pull: Float,
    val morph: Morph
)

private fun Morph.toComposePath(progress: Float): Path {
    val path = Path()
    val cubics = asCubics(progress)
    if (cubics.isEmpty()) return path
    path.moveTo(cubics.first().anchor0X, cubics.first().anchor0Y)
    for (c in cubics) {
        path.cubicTo(c.control0X, c.control0Y, c.control1X, c.control1Y, c.anchor1X, c.anchor1Y)
    }
    path.close()
    return path
}

@Composable
fun MorphBackground(content: @Composable BoxScope.() -> Unit) {
    val haptic = LocalHapticFeedback.current
    val scheme = MaterialTheme.colorScheme

    val shapes = remember {
        listOf(
            RoundedPolygon.star(8, 1f, 0.72f, CornerRounding(0.25f)),
            RoundedPolygon(6, 1f, rounding = CornerRounding(0.3f)),
            RoundedPolygon.star(5, 1f, 0.6f, CornerRounding(0.3f)),
            RoundedPolygon(4, 1f, rounding = CornerRounding(0.45f)),
            RoundedPolygon.star(12, 1f, 0.8f, CornerRounding(0.2f)),
        )
    }
    val colors = listOf(
        scheme.primaryContainer,
        scheme.tertiaryContainer,
        scheme.secondaryContainer,
        scheme.primary
    )
    val blobs = remember {
        listOf(
            Blob(0.15f, 0.20f, 0.55f, 5200, 9000, 24000, 0.30f, Morph(shapes[0], shapes[1])),
            Blob(0.90f, 0.35f, 0.45f, 6400, 11000, 30000, 0.22f, Morph(shapes[2], shapes[3])),
            Blob(0.25f, 0.80f, 0.60f, 7300, 13000, 36000, 0.18f, Morph(shapes[3], shapes[4])),
            Blob(0.85f, 0.90f, 0.40f, 4800, 8000, 20000, 0.35f, Morph(shapes[1], shapes[2])),
        )
    }

    val infinite = rememberInfiniteTransition(label = "bg")
    val progress = blobs.map { b ->
        infinite.animateFloat(
            0f, 1f,
            infiniteRepeatable(tween(b.morphMs, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "morph"
        )
    }
    val drift = blobs.map { b ->
        infinite.animateFloat(
            0f, (2 * Math.PI).toFloat(),
            infiniteRepeatable(tween(b.driftMs, easing = LinearEasing)),
            label = "drift"
        )
    }
    val spin = blobs.map { b ->
        infinite.animateFloat(
            0f, 360f,
            infiniteRepeatable(tween(b.spinMs, easing = LinearEasing)),
            label = "spin"
        )
    }

    var touch by remember { mutableStateOf<Offset?>(null) }
    var lastTouch by remember { mutableStateOf(Offset.Zero) }
    val pullAmount by animateFloatAsState(
        if (touch != null) 1f else 0f,
        spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessLow),
        label = "pull"
    )

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var accumulated = 0f
                    var previous: Offset? = null
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (consoleActive.value) {
                            touch = null
                            previous = null
                            accumulated = 0f
                            continue
                        }
                        val down = event.changes.firstOrNull { it.pressed }
                        if (down != null) {
                            touch = down.position
                            lastTouch = down.position
                            if (previous == null) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else {
                                accumulated += (down.position - previous).getDistance()
                                if (accumulated > 90f) {
                                    accumulated = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                            previous = down.position
                        } else {
                            touch = null
                            previous = null
                            accumulated = 0f
                        }
                    }
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            blobs.forEachIndexed { i, b ->
                val radius = size.width * b.size
                val angle = drift[i].value
                var cx = size.width * b.baseX + kotlin.math.cos(angle) * size.width * 0.07f
                var cy = size.height * b.baseY + kotlin.math.sin(angle * 0.8f) * size.height * 0.05f
                cx += (lastTouch.x - cx) * b.pull * pullAmount
                cy += (lastTouch.y - cy) * b.pull * pullAmount
                val path = b.morph.toComposePath(progress[i].value)
                translate(cx, cy) {
                    rotate(spin[i].value, Offset.Zero) {
                        scale(radius * (1f + 0.12f * pullAmount), Offset.Zero) {
                            drawPath(path, colors[i].copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
        content()
    }
}

// ---------- Root: accounts, login, or the swipeable main screen ----------

@Composable
fun Root() {
    val context = LocalContext.current
    val store = remember { AccountStore(context) }
    val settingsStore = remember { SettingsStore(context) }
    val avatars = remember { AvatarStore(context) }
    val scope = rememberCoroutineScope()

    var state by remember { mutableStateOf(store.load()) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var adding by remember { mutableStateOf(false) }
    var avatarTick by remember { mutableIntStateOf(0) }
    var linkBusy by remember { mutableStateOf(false) }
    var linkError by remember { mutableStateOf<String?>(null) }

    fun update(s: Accounts) {
        store.save(s)
        state = s
    }

    fun updateSettings(s: ConsoleSettings) {
        settings = s
        settingsStore.save(s)
    }

    fun addAccount(acc: Account) {
        val existing = state.list.indexOfFirst { it.server == acc.server && it.token == acc.token }
        update(
            if (existing >= 0) Accounts(state.list, existing)
            else Accounts(state.list + acc, state.list.size)
        )
        adding = false
    }

    fun removeAt(index: Int) {
        val list = state.list.toMutableList()
        if (index in list.indices) list.removeAt(index)
        val newActive = when {
            index < state.active -> state.active - 1
            index == state.active -> 0
            else -> state.active
        }
        update(Accounts(list, newActive.coerceIn(0, maxOf(0, list.size - 1))))
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val acc = state.current
        if (uri != null && acc != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) { avatars.save(avatarKey(acc), uri) }
                if (ok) {
                    avatarTick++
                    val key = avatarKey(acc)
                    val bytes = withContext(Dispatchers.IO) { avatars.readBytes(key) }
                    if (bytes != null && uploadAvatar(acc.server, acc.token, bytes)) avatars.markSynced(key)
                }
            }
        }
    }

    // Pull profile pictures from the servers (the server copy is the source of truth)
    LaunchedEffect(state.list) {
        state.list.forEach { syncAvatar(avatars, it) }
        avatarTick++
    }

    MorphBackground {
        val current = state.current
        if (current == null || adding) {
            LoginScreen(
                canCancel = current != null,
                onCancel = { adding = false },
                onLogin = { server, token ->
                    val s = normalizeServer(server)
                    val (code, user) = verify(s, token)
                    when (code) {
                        200 -> { addAccount(Account(s, token, labelFor(s, user))); null }
                        401 -> "That token isn't valid"
                        else -> "Can't reach the server"
                    }
                }
            )
        } else {
            SignedIn(
                state = state,
                settings = settings,
                avatars = avatars,
                avatarTick = avatarTick,
                onSettings = { updateSettings(it) },
                onSwitch = { update(Accounts(state.list, it)) },
                onAddAccount = { adding = true },
                onLogout = { removeAt(it) },
                onPickPhoto = {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRemovePhoto = {
                    state.current?.let { acc ->
                        avatars.delete(avatarKey(acc))
                        scope.launch { deleteAvatarOnServer(acc.server, acc.token) }
                    }
                    avatarTick++
                }
            )
        }
    }

    // A vmpower://login link was opened: ask before adding it
    val link = pendingLink.value
    if (link != null) {
        val parsed = parseLoginLink(link)
        if (parsed == null) {
            AlertDialog(
                onDismissRequest = { pendingLink.value = null },
                title = { Text("Invalid login link") },
                text = { Text("That link couldn't be read.") },
                confirmButton = { TextButton(onClick = { pendingLink.value = null }) { Text("OK") } }
            )
        } else {
            val (server, token) = parsed
            AlertDialog(
                onDismissRequest = { if (!linkBusy) { pendingLink.value = null; linkError = null } },
                title = { Text("Add account?") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sign in to $server?")
                        Text("Only continue if you trust this server.", style = MaterialTheme.typography.bodySmall)
                        linkError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                },
                confirmButton = {
                    TextButton(enabled = !linkBusy, onClick = {
                        scope.launch {
                            linkBusy = true
                            linkError = null
                            val (code, user) = verify(server, token)
                            linkBusy = false
                            if (code == 200) {
                                addAccount(Account(server, token, labelFor(server, user)))
                                pendingLink.value = null
                            } else {
                                linkError = if (code == 401) "That token isn't valid" else "Can't reach the server"
                            }
                        }
                    }) { Text(if (linkBusy) "Checking..." else "Add account") }
                },
                dismissButton = {
                    TextButton(enabled = !linkBusy, onClick = { pendingLink.value = null; linkError = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

// ---------- Signed in: swipeable pages with a bottom bar ----------

@Composable
private fun SignedIn(
    state: Accounts,
    settings: ConsoleSettings,
    avatars: AvatarStore,
    avatarTick: Int,
    onSettings: (ConsoleSettings) -> Unit,
    onSwitch: (Int) -> Unit,
    onAddAccount: () -> Unit,
    onLogout: (Int) -> Unit,
    onPickPhoto: () -> Unit,
    onRemovePhoto: () -> Unit
) {
    val current = state.current ?: return
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val inConsole = consoleActive.value

    Scaffold(
        containerColor = Color.Transparent,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            if (!inConsole) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.94f)
                ) {
                    listOf(
                        Icons.Rounded.Computer to "VMs",
                        Icons.Rounded.Person to "Accounts",
                        Icons.Rounded.Settings to "Settings"
                    )
                        .forEachIndexed { i, (icon, label) ->
                            NavigationBarItem(
                                selected = pager.currentPage == i,
                                onClick = { scope.launch { pager.animateScrollToPage(i) } },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) }
                            )
                        }
                }
            }
        }
    ) { padding ->
        HorizontalPager(
            state = pager,
            userScrollEnabled = !inConsole,
            beyondViewportPageCount = 2,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
        ) { page ->
            when (page) {
                0 -> key(current.server, current.token) {
                    VmScreen(
                        account = current,
                        settings = settings,
                        onSettings = onSettings,
                        avatars = avatars,
                        avatarTick = avatarTick,
                        onAvatarClick = { scope.launch { pager.animateScrollToPage(1) } },
                        onLogout = { onLogout(state.active) }
                    )
                }
                1 -> AccountsPage(
                    accounts = state,
                    avatars = avatars,
                    avatarTick = avatarTick,
                    onSwitch = onSwitch,
                    onAddAccount = onAddAccount,
                    onLogout = onLogout,
                    onPickPhoto = onPickPhoto,
                    onRemovePhoto = onRemovePhoto
                )
                else -> SettingsPage(settings, onSettings)
            }
        }
    }
}

// ---------- Login ----------

@Composable
fun LoginScreen(
    canCancel: Boolean,
    onCancel: () -> Unit,
    onLogin: suspend (server: String, token: String) -> String?
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var linkText by remember { mutableStateOf("") }
    var server by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            shape = MaterialTheme.shapes.extraLarge,
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.85f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("VM Power", style = MaterialTheme.typography.displaySmall)
                Text(
                    if (canCancel) "Add another account." else "Sign in to your server.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = linkText,
                    onValueChange = { v ->
                        val parsed = parseLoginLink(v)
                        if (parsed != null) {
                            server = parsed.first
                            token = parsed.second
                            linkText = ""
                            error = null
                        } else linkText = v.trim()
                    },
                    label = { Text("Login link") },
                    supportingText = { Text("Paste a vmpower:// link to fill in the fields below") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it.trim(); error = null },
                    label = { Text("Server") },
                    placeholder = { Text("vm.example.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it.trim(); error = null },
                    label = { Text("Access token") },
                    singleLine = true,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            val result = onLogin(server, token)
                            loading = false
                            if (result != null) {
                                error = result
                                haptic.performHapticFeedback(HapticFeedbackType.Reject)
                            } else {
                                haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                            }
                        }
                    },
                    enabled = server.isNotEmpty() && token.isNotEmpty() && !loading,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
                ) {
                    if (loading) LoadingIndicator(Modifier.size(24.dp))
                    else Text("Sign in", style = MaterialTheme.typography.titleMedium)
                }
                if (canCancel) {
                    TextButton(
                        onClick = onCancel,
                        modifier = Modifier.heightIn(min = 52.dp)
                    ) { Text("Cancel", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
    }
}

// ---------- VM list ----------

@Composable
fun VmScreen(
    account: Account,
    settings: ConsoleSettings,
    onSettings: (ConsoleSettings) -> Unit,
    avatars: AvatarStore,
    avatarTick: Int,
    onAvatarClick: () -> Unit,
    onLogout: () -> Unit
) {
    val server = account.server
    val token = account.token
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    var groups by remember { mutableStateOf<List<VmGroup>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf<VmAction?>(null) }
    var pendingTitle by remember { mutableStateOf("") }
    var consoleVm by remember { mutableStateOf<String?>(null) }
    var statuses by remember { mutableStateOf<Map<String, VmStatus>>(emptyMap()) }

    suspend fun load() {
        loading = true
        try {
            val (code, body) = request("GET", "/list", server, token)
            if (code == 200) {
                val arr = JSONArray(body)
                groups = groupCommands(List(arr.length()) { arr.getString(it) })
            } else if (code == 401) {
                onLogout()
            } else snackbar.showSnackbar("Error $code")
        } catch (e: Exception) {
            snackbar.showSnackbar("Failed: ${e.message}")
        }
        loading = false
    }

    suspend fun loadStatus() {
        try {
            val (code, body) = request("GET", "/status", server, token)
            if (code == 200) statuses = parseStatuses(body)
        } catch (e: Exception) {
            // older server or no connection: keep the last known status
        }
    }

    fun run(action: VmAction, title: String) {
        scope.launch {
            busy = true
            var ok = false
            val msg = try {
                val (code, body) = request("POST", "/run/${action.command}", server, token)
                if (code == 200) {
                    val j = JSONObject(body)
                    if (j.getInt("code") == 0) {
                        ok = true
                        "$title: ${action.label} OK"
                    } else "$title: ${j.getString("stderr").trim()}"
                } else "Error $code"
            } catch (e: Exception) {
                "Failed: ${e.message}"
            }
            busy = false
            haptic.performHapticFeedback(if (ok) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
            scope.launch { delay(1500); loadStatus() }
            snackbar.showSnackbar(msg)
        }
    }

    LaunchedEffect(Unit) { load() }

    // Only poll the status while the app is on screen and no console is open
    val activity = LocalContext.current as? ComponentActivity
    var resumed by remember { mutableStateOf(true) }
    DisposableEffect(activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resumed = true
            else if (event == Lifecycle.Event.ON_PAUSE) resumed = false
        }
        activity?.lifecycle?.addObserver(observer)
        onDispose { activity?.lifecycle?.removeObserver(observer) }
    }
    LaunchedEffect(resumed, consoleVm == null) {
        if (resumed && consoleVm == null) {
            while (true) {
                loadStatus()
                delay(8_000)
            }
        }
    }

    consoleVm?.let { NativeConsole(server, it, token, settings, onSettings) { consoleVm = null }; return }

    confirmStop?.let { action ->
        AlertDialog(
            onDismissRequest = { confirmStop = null },
            title = { Text("Force off $pendingTitle?") },
            text = { Text("This cuts power to the VM immediately. Unsaved data may be lost.") },
            confirmButton = {
                TextButton(
                    onClick = { confirmStop = null; run(action, pendingTitle) },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Force off", style = MaterialTheme.typography.titleMedium) }
            },
            dismissButton = {
                TextButton(
                    onClick = { confirmStop = null },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) { Text("Cancel", style = MaterialTheme.typography.titleMedium) }
            }
        )
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            LargeTopAppBar(
                title = { Text("My VMs") },
                actions = {
                    AccountAvatar(
                        avatars, avatarTick, account, 44.dp,
                        Modifier.clickable { onAvatarClick() }
                    )
                    Spacer(Modifier.width(12.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.9f)
                ),
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            MediumFloatingActionButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                scope.launch { load(); loadStatus() }
            }) {
                if (loading || busy) LoadingIndicator(Modifier.size(32.dp))
                else Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", modifier = Modifier.size(32.dp))
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (groups.isEmpty() && !loading) {
                item {
                    Text(
                        "No VMs available for this account.",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            items(groups, key = { it.title }) { group ->
                VmCard(
                    group = group,
                    status = statuses[group.title],
                    enabled = !busy,
                    onConsole = { consoleVm = group.title },
                    onAction = { action ->
                        haptic.performHapticFeedback(HapticFeedbackType.ContextClick)
                        pendingTitle = group.title
                        if (action.isStop) confirmStop = action else run(action, group.title)
                    }
                )
            }
        }
    }
}

@Composable
fun VmCard(
    group: VmGroup,
    status: VmStatus?,
    enabled: Boolean,
    onConsole: () -> Unit,
    onAction: (VmAction) -> Unit
) {
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(group.title, style = MaterialTheme.typography.headlineMedium)
            StatusBadge(status)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                group.actions.forEach { action ->
                    val mod = Modifier.weight(1f).heightIn(min = 68.dp)
                    val allowed = enabled && actionAllowed(action, status)
                    if (action.isStop) {
                        FilledTonalButton(
                            onClick = { onAction(action) },
                            enabled = allowed,
                            modifier = mod
                        ) { Text(action.label, style = MaterialTheme.typography.titleMedium) }
                    } else {
                        Button(
                            onClick = { onAction(action) },
                            enabled = allowed,
                            modifier = mod
                        ) { Text(action.label, style = MaterialTheme.typography.titleMedium) }
                    }
                }
            }
            OutlinedButton(
                onClick = onConsole,
                enabled = status?.active ?: true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)
            ) {
                Text("Console", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

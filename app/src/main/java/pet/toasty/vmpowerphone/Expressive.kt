@file:OptIn(ExperimentalMaterial3Api::class)

package pet.toasty.vmpowerphone

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------- haptics

/**
 * One place for all vibration feedback.
 * - Uses the system haptics first; if the system refuses (for example "touch feedback"
 *   is switched off), it falls back to the vibration motor directly.
 * - Never fires while the console is open.
 */
class Haptics(private val view: View?, private val context: Context?) {
    var enabled: Boolean = true
    var backgroundEnabled: Boolean = true

    private fun fire(constant: Int, kind: Int) {
        if (!enabled || consoleActive.value) return
        val done = try {
            view?.performHapticFeedback(constant) ?: false
        } catch (e: Exception) {
            false
        }
        if (!done) vibrate(kind)
    }

    fun tick() = fire(HapticFeedbackConstants.CLOCK_TICK, 0)
    fun click() = fire(HapticFeedbackConstants.CONTEXT_CLICK, 1)
    fun heavy() = fire(HapticFeedbackConstants.LONG_PRESS, 2)

    fun confirm() = fire(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS, 1
    )

    fun reject() = fire(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS, 2
    )

    /** Light tick while dragging over the animated background. */
    fun bgTick() {
        if (backgroundEnabled) tick()
    }

    @Suppress("DEPRECATION")
    private fun vibrate(kind: Int) {
        val ctx = context ?: return
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
            if (vibrator == null || !vibrator.hasVibrator()) return
            when {
                Build.VERSION.SDK_INT >= 29 -> vibrator.vibrate(
                    VibrationEffect.createPredefined(
                        when (kind) {
                            0 -> VibrationEffect.EFFECT_TICK
                            1 -> VibrationEffect.EFFECT_CLICK
                            else -> VibrationEffect.EFFECT_HEAVY_CLICK
                        }
                    )
                )
                Build.VERSION.SDK_INT >= 26 -> vibrator.vibrate(
                    VibrationEffect.createOneShot(if (kind == 2) 30L else 12L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
                else -> vibrator.vibrate(if (kind == 2) 30L else 12L)
            }
        } catch (e: Exception) {
            // no vibration permission or no motor: nothing to do
        }
    }
}

val LocalHaptics = staticCompositionLocalOf { Haptics(null, null) }

// ---------------------------------------------------------------- glass (blurred backdrop)

/**
 * The animated background, drawable anywhere. Glass elements draw their own blurred copy of
 * it behind themselves, so they look frosted without any extra library.
 */
class Backdrop(val draw: DrawScope.(Float, Float) -> Unit) {
    var size by mutableStateOf(Size.Zero)
}

val LocalBackdrop = compositionLocalOf<Backdrop?> { null }
val LocalGlassOn = staticCompositionLocalOf { true }

@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    content: @Composable BoxScope.() -> Unit
) {
    val backdrop = LocalBackdrop.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .clip(shape)
            .onGloballyPositioned { origin = it.positionInRoot() }
    ) {
        if (LocalGlassOn.current && backdrop != null) {
            Canvas(Modifier.matchParentSize().blur(28.dp)) {
                translate(-origin.x, -origin.y) {
                    backdrop.draw(this, backdrop.size.width, backdrop.size.height)
                }
            }
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    content: @Composable ColumnScope.() -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val glassOn = LocalGlassOn.current && LocalBackdrop.current != null
    GlassSurface(modifier = modifier, shape = shape) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = shape,
            colors = CardDefaults.cardColors(
                containerColor = scheme.surfaceContainerHigh.copy(alpha = if (glassOn) 0.50f else 0.92f)
            ),
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
            content = content
        )
    }
}

@Composable
fun GlassNavigationBar(content: @Composable RowScope.() -> Unit) {
    val glassOn = LocalGlassOn.current && LocalBackdrop.current != null
    GlassSurface(shape = RectangleShape) {
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (glassOn) 0.50f else 0.94f),
            content = content
        )
    }
}

// ---------------------------------------------------------------- expressive buttons

/**
 * Material 3 Expressive feel: buttons are pills that spring into a tighter, squarer
 * shape and shrink slightly while pressed, with a haptic click.
 */
@Composable
private fun rememberExpressive(interaction: MutableInteractionSource): Pair<Shape, Modifier> {
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHaptics.current
    val corner by animateDpAsState(
        targetValue = if (pressed) 12.dp else 40.dp,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 600f),
        label = "corner"
    )
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = 500f),
        label = "scale"
    )
    LaunchedEffect(pressed) { if (pressed) haptics.click() }
    val shape = RoundedCornerShape(corner.coerceAtLeast(0.dp))
    return shape to Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

@Composable
fun ExpressiveButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val (shape, feel) = rememberExpressive(interaction)
    Button(
        onClick = onClick,
        modifier = modifier.then(feel),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.93f)
        ),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content
    )
}

@Composable
fun ExpressiveTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val (shape, feel) = rememberExpressive(interaction)
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier.then(feel),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.filledTonalButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.85f)
        ),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content
    )
}

@Composable
fun ExpressiveOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val (shape, feel) = rememberExpressive(interaction)
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.then(feel),
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.30f)
        ),
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content
    )
}

@Composable
fun ExpressiveTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentPadding: PaddingValues = ButtonDefaults.TextButtonContentPadding,
    content: @Composable RowScope.() -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val (shape, feel) = rememberExpressive(interaction)
    TextButton(
        onClick = onClick,
        modifier = modifier.then(feel),
        enabled = enabled,
        shape = shape,
        contentPadding = contentPadding,
        interactionSource = interaction,
        content = content
    )
}

@Composable
fun ExpressiveIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tonal: Boolean = false,
    content: @Composable () -> Unit
) {
    val interaction = remember { MutableInteractionSource() }
    val (shape, feel) = rememberExpressive(interaction)
    val scheme = MaterialTheme.colorScheme
    FilledTonalIconButton(
        onClick = onClick,
        modifier = modifier.then(feel),
        shape = shape,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (tonal) scheme.secondaryContainer.copy(alpha = 0.85f)
            else scheme.surfaceContainerHigh.copy(alpha = 0.40f),
            contentColor = if (tonal) scheme.onSecondaryContainer else scheme.onSurface
        ),
        interactionSource = interaction,
        content = content
    )
}

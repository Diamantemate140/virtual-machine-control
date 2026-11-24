package pet.toasty.vmpowerphone

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.json.JSONObject

/** state: running, off, crashed, paused, shutting_down, suspended or unknown. */
data class VmStatus(
    val state: String,
    val reason: String,
    val active: Boolean,
    val uptime: Long?,
    val vcpus: Int?,
    val memoryMb: Int?
)

fun parseStatuses(body: String): Map<String, VmStatus> {
    val root = JSONObject(body)
    val out = mutableMapOf<String, VmStatus>()
    root.keys().forEach { vm ->
        val s = root.getJSONObject(vm)
        out[vm] = VmStatus(
            state = s.optString("state", "unknown"),
            reason = s.optString("reason", ""),
            active = s.optBoolean("active", false),
            uptime = if (s.isNull("uptime")) null else s.optLong("uptime"),
            vcpus = if (s.isNull("vcpus")) null else s.optInt("vcpus"),
            memoryMb = if (s.isNull("memory_mb")) null else s.optInt("memory_mb")
        )
    }
    return out
}

fun formatUptime(sec: Long): String {
    val d = sec / 86400
    val h = sec % 86400 / 3600
    val m = sec % 3600 / 60
    val s = sec % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
    }
}

/** Which buttons make sense right now. If the status is unknown, everything stays enabled. */
fun actionAllowed(action: VmAction, status: VmStatus?): Boolean = when {
    status == null || status.state == "unknown" -> true
    action.isStop -> status.active
    action.command.endsWith("-start") -> !status.active
    else -> true
}

@Composable
fun StatusBadge(status: VmStatus?) {
    if (status == null) return
    val warn = Color(0xFFE0A000)
    val (color, label) = when (status.state) {
        "running" -> Color(0xFF2E9E5B) to "Running"
        "off" -> MaterialTheme.colorScheme.outline to "Powered off"
        "crashed" -> MaterialTheme.colorScheme.error to "Crashed"
        "paused" -> warn to "Paused"
        "shutting_down" -> warn to "Shutting down"
        "suspended" -> warn to "Suspended"
        else -> MaterialTheme.colorScheme.outline to "Unknown"
    }
    val details = listOfNotNull(
        status.uptime?.takeIf { status.active }?.let { "Up ${formatUptime(it)}" },
        status.vcpus?.let { "$it vCPU" },
        status.memoryMb?.let { "$it MB" },
        status.reason.takeIf { it.isNotBlank() && status.state != "running" }
    ).joinToString("  ·  ")

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        if (details.isNotEmpty()) {
            Text(
                details,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

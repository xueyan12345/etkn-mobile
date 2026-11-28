package xyz.etkn.mobile.ui.screens.me

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.RangeSliderState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.etkn.mobile.data.diagnostics.Diagnostics
import xyz.etkn.mobile.ui.components.GlassSheet
import xyz.etkn.mobile.ui.theme.Etkn
import java.time.LocalDate
import kotlin.math.roundToInt
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.EtknLoading

/** Pick one day and an hour range of the local error log, then save it as a text file. */
@Composable
fun LogExportSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var days by remember { mutableStateOf<List<LocalDate>?>(null) }
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var hours by remember { mutableStateOf(0f..24f) }
    LaunchedEffect(Unit) {
        val found = withContext(Dispatchers.IO) { Diagnostics.days(context) }
        days = found
        day = found.firstOrNull()
    }
    val from = hours.start.roundToInt()
    val to = hours.endInclusive.roundToInt().coerceAtLeast(from + 1)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { target ->
        val chosen = day
        if (target != null && chosen != null) scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { Diagnostics.export(context, target, chosen, from, to) } }
            Toast.makeText(context, if (result.isSuccess) "日志已导出" else "导出失败", Toast.LENGTH_SHORT).show()
            if (result.isSuccess) onDismiss()
        }
    }

    GlassSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Etkn.space.screen).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("导出日志", style = MaterialTheme.typography.titleLarge)
            val list = days
            when {
                list == null -> EtknLoading(Modifier.align(Alignment.CenterHorizontally))
                list.isEmpty() -> Text("暂无日志", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(PillGap), verticalArrangement = Arrangement.spacedBy(PillGap)) {
                        list.forEach { option ->
                            PillToggle(checked = option == day, onClick = { day = option }) { Text(dayLabel(option)) }
                        }
                    }
                    Column {
                        Text("%02d:00 – %02d:00".format(from, to), style = MaterialTheme.typography.titleMedium)
                        val range = remember { RangeSliderState(0f, 24f, steps = 23, trackRange = 0f..24f) }
                        range.startValue = hours.start
                        range.endValue = hours.endInclusive
                        RangeSlider(state = range, onValueChange = { hours = it })
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    enabled = !list.isNullOrEmpty(),
                    onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { Diagnostics.clear(context) }
                            days = emptyList()
                            day = null
                        }
                    },
                ) { Text("清空") }
                Button(enabled = day != null, onClick = { day?.let { save.launch(Diagnostics.fileName(it)) } }) { Text("导出") }
            }
        }
    }
}

private fun dayLabel(day: LocalDate): String {
    val today = LocalDate.now()
    return when (day) {
        today -> "今天"
        today.minusDays(1) -> "昨天"
        else -> "${day.monthValue}月${day.dayOfMonth}日"
    }
}

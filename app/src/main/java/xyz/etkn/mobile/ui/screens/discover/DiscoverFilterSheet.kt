package xyz.etkn.mobile.ui.screens.discover

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.etkn.mobile.data.discover.DiscoverFilter
import xyz.etkn.mobile.data.discover.DiscoverOption
import xyz.etkn.mobile.data.discover.DiscoverOptions
import xyz.etkn.mobile.data.discover.DiscoverSort
import xyz.etkn.mobile.data.discover.PersonMatch
import xyz.etkn.mobile.data.discover.best
import xyz.etkn.mobile.ui.components.GlassSheet
import xyz.etkn.mobile.ui.theme.Corner
import xyz.etkn.mobile.ui.components.PillGap
import xyz.etkn.mobile.ui.components.PillToggle
import xyz.etkn.mobile.ui.components.EtknLoading

/** [department] = TMDb department a typed name is matched in (people pickers only). */
private enum class Picker(val title: String, val department: String = "") {
    Countries("地区"), Language("语言"), Studios("工作室"), Keywords("关键词"), Actors("演员", "Acting"), Directors("导演", "Directing"),
}

/**
 * Every filter the Web 影视探索 panel has. Short vocabularies are chips; long ones (地区, 语言,
 * 工作室, 关键词) open a searchable picker; 演员 / 导演 search TMDb people by name.
 */
@Composable
internal fun FilterSheet(
    initial: DiscoverFilter,
    options: DiscoverOptions,
    findPeople: suspend (String) -> List<PersonMatch>,
    onDismiss: () -> Unit,
    onApply: (DiscoverFilter) -> Unit,
) {
    var draft by remember { mutableStateOf(initial) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    GlassSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp)) {
            FilterGroup("排序") {
                DiscoverSort.entries.forEach { sort ->
                    PillToggle(checked = draft.sort == sort, onClick = { draft = draft.copy(sort = sort) }) { Text(sort.label) }
                }
            }
            if (options.genres.isNotEmpty()) {
                FilterGroup("风格", trailing = {
                    listOf(false to "包含", true to "排除").forEach { (exclude, label) ->
                        PillToggle(checked = draft.excludeGenres == exclude, onClick = { draft = draft.copy(excludeGenres = exclude) }) { Text(label) }
                    }
                }) {
                    options.genres.forEach { genre ->
                        val selected = genre.value in draft.genres
                        PillToggle(
                            checked = selected,
                            onClick = { draft = draft.copy(genres = if (selected) draft.genres - genre.value else draft.genres + genre.value) },
                        ) { Text(genre.label) }
                    }
                }
            }
            if (options.ratings.isNotEmpty()) {
                FilterGroup("分级") {
                    options.ratings.forEach { rating ->
                        val selected = draft.rating == rating.value
                        PillToggle(
                            checked = selected,
                            onClick = { draft = draft.copy(rating = if (selected) null else rating.value) },
                        ) { Text(rating.label.substringAfter(". ")) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            PickerRow(Picker.Countries.title, labels(options.countries, draft.countries)) { picker = Picker.Countries }
            PickerRow(Picker.Language.title, labels(options.languages, setOfNotNull(draft.language))) { picker = Picker.Language }
            PickerRow(Picker.Studios.title, draft.studios.toList()) { picker = Picker.Studios }
            PickerRow(Picker.Keywords.title, draft.keywords.toList()) { picker = Picker.Keywords }
            PickerRow(Picker.Actors.title, draft.actors.map { it.label }) { picker = Picker.Actors }
            PickerRow(Picker.Directors.title, draft.directors.map { it.label }) { picker = Picker.Directors }
            FilterGroup("发行年份") {}
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                YearField("从", draft.yearFrom, Modifier.weight(1f)) { draft = draft.copy(yearFrom = it) }
                YearField("到", draft.yearTo, Modifier.weight(1f)) { draft = draft.copy(yearTo = it) }
            }
            val vote = draft.minVote ?: 0f
            FilterGroup("最低评分 · ${if (vote > 0f) "%.1f".format(vote) else "不限"}") {}
            val voteState = remember { SliderState(vote, 19, 0f..9.5f) }
            voteState.value = vote
            Slider(state = voteState, onValueChange = { draft = draft.copy(minVote = it.takeIf { v -> v > 0f }) })
            Row(
                Modifier.fillMaxWidth().clickable { draft = draft.copy(hideInLibrary = !draft.hideInLibrary) }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("隐藏已入库", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = draft.hideInLibrary, onCheckedChange = { draft = draft.copy(hideInLibrary = it) })
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { draft = DiscoverFilter(query = initial.query) }, modifier = Modifier.weight(1f)) { Text("重置") }
                Button(onClick = { onApply(draft) }, modifier = Modifier.weight(1f)) { Text("应用") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    when (val open = picker) {
        Picker.Countries -> OptionPickerSheet(open.title, options.countries, draft.countries, multiple = true, onDismiss = { picker = null }) { draft = draft.copy(countries = it) }
        Picker.Language -> OptionPickerSheet(open.title, options.languages, setOfNotNull(draft.language), multiple = false, onDismiss = { picker = null }) { draft = draft.copy(language = it.firstOrNull()) }
        Picker.Studios -> OptionPickerSheet(open.title, options.studios, draft.studios, multiple = true, onDismiss = { picker = null }) { draft = draft.copy(studios = it) }
        Picker.Keywords -> OptionPickerSheet(open.title, options.keywords, draft.keywords, multiple = true, onDismiss = { picker = null }) { draft = draft.copy(keywords = it) }
        Picker.Actors -> PeoplePickerSheet(open, draft.actors, findPeople, onDismiss = { picker = null }) { draft = draft.copy(actors = it) }
        Picker.Directors -> PeoplePickerSheet(open, draft.directors, findPeople, onDismiss = { picker = null }) { draft = draft.copy(directors = it) }
        null -> Unit
    }
}

private fun labels(options: List<DiscoverOption>, values: Set<String>): List<String> =
    values.map { value -> options.firstOrNull { it.value == value }?.label ?: value }

@Composable
private fun FilterGroup(title: String, trailing: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
        if (trailing != null) Row(horizontalArrangement = Arrangement.spacedBy(PillGap)) { trailing() }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(PillGap), verticalArrangement = Arrangement.spacedBy(PillGap)) { content() }
}

/** One line per long vocabulary: its name, what is chosen, and a tap to change it. */
@Composable
private fun PickerRow(title: String, chosen: List<String>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(Corner.card).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(72.dp))
        Text(
            chosen.joinToString("、").ifEmpty { "不限" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (chosen.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun YearField(label: String, value: Int?, modifier: Modifier, onChange: (Int?) -> Unit) {
    var text by remember(value) { mutableStateOf(value?.toString().orEmpty()) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            val digits = input.filter(Char::isDigit).take(4)
            text = digits
            onChange(digits.toIntOrNull()?.takeIf { digits.length == 4 && it in 1874..2100 })
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Searchable list of one vocabulary; [multiple] = checkboxes, else picking one closes it. */
@Composable
private fun OptionPickerSheet(
    title: String,
    options: List<DiscoverOption>,
    selected: Set<String>,
    multiple: Boolean,
    onDismiss: () -> Unit,
    onChange: (Set<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(selected) }
    var search by remember { mutableStateOf("") }
    val shown = remember(options, search) { options.filter { search.isBlank() || it.label.contains(search.trim(), ignoreCase = true) } }
    GlassSheet(onDismissRequest = { onChange(chosen); onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (chosen.isNotEmpty()) TextButton(onClick = { chosen = emptySet() }) { Text("清除") }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text("搜索") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(shown, key = { it.value }) { option ->
                    val on = option.value in chosen
                    OptionRow(option.label, on) {
                        if (multiple) {
                            chosen = if (on) chosen - option.value else chosen + option.value
                        } else {
                            chosen = if (on) emptySet() else setOf(option.value)
                            onChange(chosen)
                            onDismiss()
                        }
                    }
                }
            }
            if (multiple) {
                Button(onClick = { onChange(chosen); onDismiss() }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("完成") }
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

/**
 * Actors / directors: search TMDb people by name and tap to pick several. Confirming with a name
 * typed but nobody tapped picks the most likely person for it ([best]), so 完成 or the
 * keyboard's search key right after typing a name does what it says.
 */
@Composable
private fun PeoplePickerSheet(
    picker: Picker,
    selected: List<DiscoverOption>,
    findPeople: suspend (String) -> List<PersonMatch>,
    onDismiss: () -> Unit,
    onChange: (List<DiscoverOption>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf(selected) }
    var search by remember { mutableStateOf("") }
    // Results belong to [answered]; [tapped] = someone was picked from them since the last edit.
    var results by remember { mutableStateOf<List<PersonMatch>>(emptyList()) }
    var answered by remember { mutableStateOf("") }
    var tapped by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var notFound by remember { mutableStateOf(false) }
    val query = search.trim()

    LaunchedEffect(query) {
        tapped = false
        notFound = false
        if (query.isEmpty()) {
            results = emptyList()
            answered = ""
            return@LaunchedEffect
        }
        delay(300)
        loading = true
        results = findPeople(query)
        answered = query
        loading = false
    }

    fun toggle(person: DiscoverOption) {
        chosen = if (chosen.any { it.value == person.value }) chosen.filterNot { it.value == person.value } else chosen + person
    }

    fun confirm() = scope.launch {
        if (query.isNotEmpty() && !tapped) {
            val matches = if (answered == query) results else findPeople(query)
            val person = matches.best(query, picker.department)
            if (person == null) {
                notFound = true
                return@launch
            }
            if (chosen.none { it.value == person.id }) chosen = chosen + person.option
        }
        onChange(chosen)
        onDismiss()
    }

    GlassSheet(onDismissRequest = { onChange(chosen); onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(picker.title, style = MaterialTheme.typography.titleLarge)
            if (chosen.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(PillGap),
                    verticalArrangement = Arrangement.spacedBy(PillGap),
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    chosen.forEach { person ->
                        PillToggle(checked = true, onClick = { toggle(person) }) {
                            Text(person.label)
                            Icon(Icons.Rounded.Close, "移除", Modifier.padding(start = 6.dp).size(16.dp))
                        }
                    }
                }
            }
            OutlinedTextField(
                value = search, onValueChange = { search = it }, singleLine = true,
                placeholder = { Text("输入姓名") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = { if (loading) EtknLoading(Modifier.size(24.dp), size = 4.dp) },
                isError = notFound,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { confirm() }),
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
            if (notFound) {
                Text("没有找到“$query”", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp, bottom = 4.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                items(results, key = { it.id }) { person ->
                    OptionRow(person.name, chosen.any { it.value == person.id }, detail = personDetail(person)) {
                        tapped = true
                        toggle(person.option)
                    }
                }
            }
            Button(onClick = { confirm() }, modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) { Text("完成") }
        }
    }
}

private fun personDetail(person: PersonMatch): String {
    val role = when (person.department) {
        "Acting" -> "演员"
        "Directing" -> "导演"
        "Writing" -> "编剧"
        "Production" -> "制片"
        else -> person.department
    }
    return listOf(role, person.knownFor).filter { it.isNotEmpty() }.joinToString(" · ")
}

@Composable
private fun OptionRow(label: String, checked: Boolean, detail: String = "", onClick: () -> Unit) {
    val color = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clip(Corner.card).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (checked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

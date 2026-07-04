package com.ultiq.app.ui.checklist

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ultiq.app.R
import com.ultiq.app.util.LocaleManager
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeeklyPlannerScreen(
    onDone: () -> Unit,
    viewModel: WeeklyPlannerViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    LaunchedEffect(state.finished) {
        if (state.finished) onDone()
    }

    val days = (0L..6L).map { state.weekStart.plusDays(it) }
    val totalItems = state.itemsByDate.values.sumOf { it.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.planner_title)) },
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back))
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Text(
                    stringResource(
                        R.string.planner_week_range,
                        state.weekStart.format(DateTimeFormatter.ofPattern("MMM d", LocaleManager.currentLocale())),
                        state.weekStart.plusDays(6).format(DateTimeFormatter.ofPattern("MMM d", LocaleManager.currentLocale())),
                    ),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                )
            }
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                days.forEach { date ->
                    item(key = "day-$date") {
                        DayCard(
                            date = date,
                            items = state.itemsByDate[date].orEmpty(),
                            onAdd = { title, priority -> viewModel.addItem(date, title, priority) },
                            onRemove = { id -> viewModel.removeItem(date, id) },
                            onCyclePriority = { id -> viewModel.cyclePriority(date, id) },
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onDone,
                    modifier = Modifier.weight(1f),
                ) { Text(if (totalItems == 0) stringResource(R.string.action_cancel) else stringResource(R.string.action_skip)) }
                Button(
                    onClick = { viewModel.save() },
                    enabled = totalItems > 0 && !state.isSaving,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(stringResource(R.string.planner_save_count, totalItems))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayCard(
    date: LocalDate,
    items: List<PendingItem>,
    onAdd: (title: String, priority: Int) -> Unit,
    onRemove: (id: String) -> Unit,
    onCyclePriority: (id: String) -> Unit,
) {
    var inputText by remember(date) { mutableStateOf("") }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val isToday = date == LocalDate.now()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    date.format(DateTimeFormatter.ofPattern("EEEE", LocaleManager.currentLocale())) +
                        if (isToday) "  •  " + stringResource(R.string.date_today) else "",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    date.format(DateTimeFormatter.ofPattern("MMM d", LocaleManager.currentLocale())),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            items.forEach { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val color = when (item.priority) {
                        2 -> MaterialTheme.colorScheme.error
                        1 -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.outline
                    }
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(color = color, shape = CircleShape)
                            .clickable { onCyclePriority(item.id) },
                    )
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemove(item.id) }) {
                        Icon(
                            Icons.Default.Close,
                            stringResource(R.string.action_remove),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text(stringResource(R.string.planner_add_item)) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        onAdd(inputText, 1)
                        inputText = ""
                    },
                    enabled = inputText.isNotBlank(),
                ) {
                    Icon(Icons.Default.Add, stringResource(R.string.action_add))
                }
            }
        }
    }
}

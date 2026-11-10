/*
 * Copyright (c) 2026 European Commission
 *
 * Licensed under the EUPL, Version 1.2 or - as soon they will be approved by the European
 * Commission - subsequent versions of the EUPL (the "Licence"); You may not use this work
 * except in compliance with the Licence.
 *
 * You may obtain a copy of the Licence at:
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the Licence is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the Licence for the specific language
 * governing permissions and limitations under the Licence.
 */


package eu.europa.ec.dashboardfeature.ui.audit.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.businesslogic.controller.audit.AuditRecord
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditStatus
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.navigation.DashboardScreens
import eu.europa.ec.uilogic.navigation.helper.generateComposableArguments
import eu.europa.ec.uilogic.navigation.helper.generateComposableNavigationLink
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import java.text.DateFormat
import java.util.Date

@Composable
internal fun AuditHistoryScreen(
    navController: NavController,
    viewModel: AuditHistoryViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()

    LifecycleStartEffect(Unit) {
        viewModel.setEvent(Event.Refresh)
        onStopOrDispose { }
    }

    LaunchedEffect(Unit) {
        viewModel.effect.onEach { effect ->
            when (effect) {
                Effect.Navigation.Pop -> navController.popBackStack()
                is Effect.Navigation.Progress -> navController.navigate(
                    generateComposableNavigationLink(
                        screen = DashboardScreens.AuditProgress,
                        arguments = generateComposableArguments(mapOf("analysisId" to effect.analysisId)),
                    )
                )

                is Effect.Navigation.Report -> navController.navigate(
                    generateComposableNavigationLink(
                        screen = DashboardScreens.AuditReport,
                        arguments = generateComposableArguments(mapOf("analysisId" to effect.analysisId)),
                    )
                )
            }
        }.collect()
    }

    ContentScreen(
        isLoading = state.isLoading,
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.Pop) },
    ) { contentPadding ->
        Content(state = state, onEventSend = { viewModel.setEvent(it) }, paddingValues = contentPadding)
    }

    state.pendingDeletion?.let {
        AlertDialog(
            onDismissRequest = { viewModel.setEvent(Event.CancelDelete) },
            title = { Text(stringResource(R.string.audit_history_delete_title)) },
            text = { Text(stringResource(R.string.audit_history_delete_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.setEvent(Event.ConfirmDelete) }) {
                    Text(stringResource(R.string.audit_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.setEvent(Event.CancelDelete) }) {
                    Text(stringResource(R.string.audit_cancel))
                }
            },
        )
    }
}

@Composable
private fun Content(
    state: State,
    onEventSend: (Event) -> Unit,
    paddingValues: PaddingValues,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
    ) {
        item {
            ContentTitle(
                title = stringResource(R.string.audit_history_title),
                subtitle = stringResource(R.string.audit_history_subtitle),
            )
        }
        if (!state.isLoading && state.records.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.audit_history_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.records, key = { it.id }) { record ->
            RecordCard(
                record = record,
                onOpen = { onEventSend(Event.Open(record)) },
                onDelete = { onEventSend(Event.AskDelete(record)) },
            )
        }
    }
}

@Composable
private fun RecordCard(record: AuditRecord, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(start = SPACING_LARGE.dp, top = SPACING_MEDIUM.dp, bottom = SPACING_MEDIUM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = record.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(
                    text = "${record.country} · " +
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(record.createdAtEpochMillis)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                AssistChip(onClick = onOpen, label = { Text(statusLabel(record)) })
            }
            IconButton(onClick = onDelete) {
                WrapIcon(iconData = AppIcons.Delete)
            }
        }
    }
}

@Composable
private fun statusLabel(record: AuditRecord): String = stringResource(
    when {
        record.status == AuditStatus.COMPLETED && record.hasReport -> R.string.audit_status_completed
        record.status == AuditStatus.FAILED -> R.string.audit_status_failed
        record.status == AuditStatus.EXPIRED -> R.string.audit_status_expired
        else -> R.string.audit_status_in_progress
    }
)

@ThemeModePreviews
@Composable
private fun AuditHistoryScreenPreview() {
    PreviewTheme {
        Content(
            state = State(
                isLoading = false,
                records = listOf(
                    AuditRecord(
                        id = "1", fileName = "contrato-arrendamiento.pdf", country = "CO",
                        createdAtEpochMillis = 0, documentUri = "", documentSha256 = "",
                        status = AuditStatus.COMPLETED, stage = "PREPARING_REPORT", hasReport = true,
                    )
                ),
            ),
            onEventSend = {},
            paddingValues = PaddingValues(SPACING_MEDIUM.dp),
        )
    }
}

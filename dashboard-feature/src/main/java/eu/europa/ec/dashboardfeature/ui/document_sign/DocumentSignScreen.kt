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


package eu.europa.ec.dashboardfeature.ui.document_sign

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.dashboardfeature.ui.document_sign.model.DocumentSignButtonUi
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ContentTitle
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.VSpacer
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.component.wrap.WrapListItem
import eu.europa.ec.uilogic.navigation.DashboardScreens
import eu.europa.ec.uilogic.navigation.helper.generateComposableArguments
import eu.europa.ec.uilogic.navigation.helper.generateComposableNavigationLink
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

@Composable
internal fun DocumentSignScreen(
    navController: NavController,
    viewModel: DocumentSignViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()

    ContentScreen(
        isLoading = state.isLoading,
        navigatableAction = ScreenNavigateAction.CANCELABLE,
        onBack = { viewModel.setEvent(Event.Pop) },
        contentErrorConfig = state.error
    ) { contentPadding ->
        Content(
            state = state,
            effectFlow = viewModel.effect,
            onEventSend = { viewModel.setEvent(it) },
            onNavigationRequested = { navigationEffect ->
                when (navigationEffect) {
                    Effect.Navigation.Pop -> navController.popBackStack()
                    is Effect.Navigation.AuditProgress -> navController.navigate(
                        generateComposableNavigationLink(
                            screen = DashboardScreens.AuditProgress,
                            arguments = generateComposableArguments(
                                mapOf("analysisId" to navigationEffect.analysisId)
                            )
                        )
                    )

                    Effect.Navigation.AuditHistory -> navController.navigate(
                        DashboardScreens.AuditHistory.screenRoute
                    )
                }
            },
            paddingValues = contentPadding
        )
    }
}


@Composable
private fun Content(
    state: State,
    effectFlow: Flow<Effect>,
    onEventSend: (Event) -> Unit,
    onNavigationRequested: (Effect.Navigation) -> Unit,
    paddingValues: PaddingValues,
) {

    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
    ) {
        ContentTitle(
            title = state.title,
            subtitle = state.subtitle,
        )

        VSpacer.Medium()

        SignButton(
            modifier = Modifier.fillMaxWidth(),
            buttonUi = state.buttonUi,
            onClick = { onEventSend(Event.OnSelectDocument) },
        )

        VSpacer.Small()

        SignButton(
            modifier = Modifier.fillMaxWidth(),
            buttonUi = state.historyButtonUi,
            onClick = { onEventSend(Event.OpenAuditHistory) },
        )
    }

    if (state.pendingDocument != null) {
        SignOptionsSheet(state = state, onEventSend = onEventSend)
    }

    state.auditError?.let { message ->
        AlertDialog(
            onDismissRequest = { onEventSend(Event.DismissAuditError) },
            title = { Text(stringResource(R.string.audit_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { onEventSend(Event.DismissAuditError) }) {
                    Text(stringResource(R.string.audit_ok))
                }
            },
        )
    }

    val selectPdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let {
            onEventSend(Event.DocumentUriRetrieved(context, it))
        }
    }

    LaunchedEffect(Unit) {
        effectFlow.onEach { effect ->
            when (effect) {
                is Effect.Navigation -> onNavigationRequested(effect)
                is Effect.OpenDocumentSelection -> selectPdfLauncher.launch(
                    effect.selection.toTypedArray()
                )
            }
        }.collect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignOptionsSheet(
    state: State,
    onEventSend: (Event) -> Unit,
) {
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = { onEventSend(Event.DismissOptions) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = SPACING_LARGE.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
        ) {
            Text(
                text = stringResource(R.string.audit_sheet_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = stringResource(R.string.audit_sheet_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(
                    modifier = Modifier.padding(SPACING_LARGE.dp),
                    verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WrapIcon(
                            iconData = AppIcons.Verified,
                            customTint = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Column(modifier = Modifier.padding(start = SPACING_MEDIUM.dp)) {
                            Text(
                                text = stringResource(R.string.audit_option_review_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                            Text(
                                text = stringResource(R.string.audit_option_review_subtitle),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.audit_country_label),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp)) {
                        state.countries.forEach { country ->
                            FilterChip(
                                selected = country == state.selectedCountry,
                                onClick = { onEventSend(Event.CountrySelected(country)) },
                                label = { Text(countryName(country)) },
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.audit_consent),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onEventSend(Event.StartAudit) },
                    ) {
                        Text(stringResource(R.string.audit_start_button))
                    }
                }
            }

            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onEventSend(Event.SignWithoutAudit(context)) },
            ) {
                Row(
                    modifier = Modifier.padding(SPACING_LARGE.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    WrapIcon(iconData = AppIcons.Sign)
                    Column(modifier = Modifier.padding(start = SPACING_MEDIUM.dp)) {
                        Text(
                            text = stringResource(R.string.audit_option_sign_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.audit_option_sign_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            VSpacer.Medium()
        }
    }
}

@Composable
private fun countryName(country: String): String = when (country) {
    "CO" -> stringResource(R.string.audit_country_co)
    "ES" -> stringResource(R.string.audit_country_es)
    else -> country
}

@Composable
private fun SignButton(
    modifier: Modifier = Modifier,
    buttonUi: DocumentSignButtonUi,
    onClick: () -> Unit,
) {
    WrapListItem(
        modifier = modifier,
        item = buttonUi.data,
        onItemClick = { onClick() },
        mainContentVerticalPadding = SPACING_LARGE.dp,
        mainContentTextStyle = MaterialTheme.typography.titleMedium,
    )
}

@ThemeModePreviews
@Composable
private fun DocumentSignScreenPreview() {
    PreviewTheme {
        Content(
            state = State(
                title = stringResource(R.string.document_sign_title),
                subtitle = stringResource(R.string.document_sign_subtitle),
                buttonUi = DocumentSignButtonUi(
                    data = ListItemDataUi(
                        itemId = "0",
                        mainContentData = ListItemMainContentDataUi.Text(
                            text = stringResource(R.string.document_sign_select_document),
                        ),
                        trailingContentData = ListItemTrailingContentDataUi.Icon(
                            iconData = AppIcons.Add
                        ),
                    )
                ),
                historyButtonUi = DocumentSignButtonUi(
                    data = ListItemDataUi(
                        itemId = "1",
                        mainContentData = ListItemMainContentDataUi.Text(
                            text = stringResource(R.string.audit_history_item),
                        ),
                        trailingContentData = ListItemTrailingContentDataUi.Icon(
                            iconData = AppIcons.Documents
                        ),
                    )
                ),
                countries = listOf("CO", "ES"),
                selectedCountry = "CO",
            ),
            effectFlow = Channel<Effect>().receiveAsFlow(),
            onEventSend = {},
            onNavigationRequested = {},
            paddingValues = PaddingValues(SPACING_MEDIUM.dp),
        )
    }
}

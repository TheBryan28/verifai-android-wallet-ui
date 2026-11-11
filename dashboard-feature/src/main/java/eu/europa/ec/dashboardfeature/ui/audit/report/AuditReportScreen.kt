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


package eu.europa.ec.dashboardfeature.ui.audit.report

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditCitation
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditFinding
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditReport
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditRisk
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.content.ContentScreen
import eu.europa.ec.uilogic.component.content.ScreenNavigateAction
import eu.europa.ec.uilogic.component.preview.PreviewTheme
import eu.europa.ec.uilogic.component.preview.ThemeModePreviews
import eu.europa.ec.uilogic.component.utils.SPACING_LARGE
import eu.europa.ec.uilogic.component.utils.SPACING_MEDIUM
import eu.europa.ec.uilogic.component.utils.SPACING_SMALL
import eu.europa.ec.uilogic.component.wrap.WrapIcon
import eu.europa.ec.uilogic.navigation.DashboardScreens
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

@Composable
internal fun AuditReportScreen(
    navController: NavController,
    viewModel: AuditReportViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.effect.onEach { effect ->
            when (effect) {
                Effect.Navigation.Pop -> navController.popBackStack()
                Effect.Navigation.Home -> navController.popBackStack(
                    route = DashboardScreens.Dashboard.screenRoute,
                    inclusive = false,
                )
            }
        }.collect()
    }

    ContentScreen(
        isLoading = state.isLoading,
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.Pop) },
        stickyBottom = { padding ->
            if (state.report != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(padding),
                    horizontalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
                ) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setEvent(Event.DontSign) },
                    ) { Text(stringResource(R.string.audit_report_dont_sign)) }
                    Button(
                        modifier = Modifier.weight(1f),
                        onClick = { viewModel.setEvent(Event.SignAnyway(context)) },
                    ) { Text(stringResource(R.string.audit_report_sign)) }
                }
            }
        },
    ) { contentPadding ->
        Content(state = state, onEventSend = { viewModel.setEvent(it) }, paddingValues = contentPadding)
    }

    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.setEvent(Event.DismissError) },
            title = { Text(stringResource(R.string.audit_error_title)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.setEvent(Event.DismissError) }) {
                    Text(stringResource(R.string.audit_ok))
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
    val report = state.report ?: return
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues),
        verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
    ) {
        item { Summary(fileName = state.fileName, report = report) }

        if (report.isPartialCoverage) {
            item { Notice(text = stringResource(R.string.audit_report_partial_coverage)) }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp)) {
                FilterChip(
                    selected = state.onlyWithIssues,
                    onClick = { onEventSend(Event.ShowOnlyWithIssues(true)) },
                    label = { Text(stringResource(R.string.audit_report_filter_with_issues)) },
                )
                FilterChip(
                    selected = !state.onlyWithIssues,
                    onClick = { onEventSend(Event.ShowOnlyWithIssues(false)) },
                    label = { Text(stringResource(R.string.audit_report_filter_all)) },
                )
            }
        }

        val findings = state.visibleFindings
        if (findings.isEmpty()) {
            item { Notice(text = stringResource(R.string.audit_report_no_findings)) }
        }
        items(findings, key = { it.clauseIndex }) { finding ->
            FindingCard(
                finding = finding,
                expanded = finding.clauseIndex in state.expanded,
                onToggle = { onEventSend(Event.ToggleFinding(finding.clauseIndex)) },
            )
        }

        item {
            Text(
                text = stringResource(R.string.audit_report_disclaimer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = SPACING_MEDIUM.dp),
            )
        }
    }
}

@Composable
private fun Summary(fileName: String, report: AuditReport) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(SPACING_LARGE.dp),
            verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
        ) {
            Text(text = stringResource(R.string.audit_report_title), style = MaterialTheme.typography.titleLarge)
            if (fileName.isNotBlank()) {
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.audit_report_summary_title, report.findings.size),
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp)) {
                AuditRisk.entries.forEach { risk ->
                    RiskCount(risk = risk, count = report.summary[risk.code] ?: 0, modifier = Modifier.weight(1f))
                }
            }
            if (report.corpusVersion.isNotBlank()) {
                Text(
                    text = stringResource(R.string.audit_report_corpus, report.corpusVersion),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RiskCount(risk: AuditRisk, count: Int, modifier: Modifier = Modifier) {
    val color = riskColor(risk)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(vertical = SPACING_MEDIUM.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = color,
        )
        Text(
            text = riskLabel(risk),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            maxLines = 1,
        )
    }
}

@Composable
private fun FindingCard(finding: AuditFinding, expanded: Boolean, onToggle: () -> Unit) {
    val color = riskColor(finding.riskLevel)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onToggle),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(6.dp)
                    .fillMaxHeight()
                    .background(color),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(SPACING_LARGE.dp),
                verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = riskLabel(finding.riskLevel),
                        style = MaterialTheme.typography.labelMedium,
                        color = color,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    finding.page?.let {
                        Text(
                            text = stringResource(R.string.audit_report_page, it),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    WrapIcon(
                        iconData = if (expanded) AppIcons.KeyboardArrowUp else AppIcons.KeyboardArrowDown,
                        modifier = Modifier.padding(start = SPACING_SMALL.dp),
                    )
                }
                Text(
                    text = finding.clauseHeading,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (!finding.evaluated) {
                    Text(
                        text = stringResource(R.string.audit_report_not_evaluated),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else if (finding.explanation.isNotBlank()) {
                    Text(text = finding.explanation, style = MaterialTheme.typography.bodyMedium)
                }

                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp)) {
                        if (finding.recommendation.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.audit_report_recommendation),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(text = finding.recommendation, style = MaterialTheme.typography.bodyMedium)
                        }
                        if (finding.citations.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.audit_report_sources),
                                style = MaterialTheme.typography.labelLarge,
                            )
                            // A long article indexed in several fragments can be cited more than once
                            finding.citations
                                .distinctBy { it.norm to (it.article ?: it.sourceId) }
                                .forEach { CitationLink(it) }
                        }
                    }
                }

                if (finding.evaluated && !finding.verified) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        WrapIcon(
                            iconData = AppIcons.Warning,
                            customTint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(end = SPACING_SMALL.dp),
                        )
                        Text(
                            text = stringResource(R.string.audit_report_unverified),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CitationLink(citation: AuditCitation) {
    val uriHandler = LocalUriHandler.current
    val label = listOfNotNull(
        citation.norm,
        citation.article?.takeIf { it.isNotBlank() }?.let { "Art. $it" },
    ).joinToString(", ").ifBlank { citation.sourceId }
    val url = citation.url?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
    Text(
        text = "• $label",
        style = MaterialTheme.typography.bodyMedium,
        color = if (url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        textDecoration = if (url != null) TextDecoration.Underline else null,
        modifier = if (url != null) Modifier.clickable { runCatching { uriHandler.openUri(url) } } else Modifier,
    )
}

@Composable
private fun Notice(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(modifier = Modifier.padding(SPACING_LARGE.dp), verticalAlignment = Alignment.CenterVertically) {
            WrapIcon(iconData = AppIcons.Info, modifier = Modifier.padding(end = SPACING_MEDIUM.dp))
            Text(text = text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun riskColor(risk: AuditRisk): Color = when (risk) {
    AuditRisk.HIGH -> MaterialTheme.colorScheme.error
    AuditRisk.MEDIUM -> Color(0xFFD9822B)
    AuditRisk.LOW -> Color(0xFF3B78C3)
    AuditRisk.NONE -> Color(0xFF2E7D32)
}

@Composable
private fun riskLabel(risk: AuditRisk): String = stringResource(
    when (risk) {
        AuditRisk.HIGH -> R.string.audit_report_risk_high
        AuditRisk.MEDIUM -> R.string.audit_report_risk_medium
        AuditRisk.LOW -> R.string.audit_report_risk_low
        AuditRisk.NONE -> R.string.audit_report_risk_none
    }
)

@ThemeModePreviews
@Composable
private fun AuditReportScreenPreview() {
    PreviewTheme {
        Content(
            state = State(
                isLoading = false,
                fileName = "contrato-arrendamiento.pdf",
                report = AuditReport(
                    country = "CO",
                    corpusVersion = "CO@2026-11",
                    summary = mapOf("high" to 1, "medium" to 1, "low" to 0, "none" to 1),
                    findings = listOf(
                        AuditFinding(
                            clauseIndex = 9,
                            clauseHeading = "OCTAVA.- TERMINACIÓN POR PARTE DEL ARRENDATARIO.",
                            page = 2,
                            risk = "high",
                            explanation = "La penalidad de 24 cánones supera la indemnización que permite la ley.",
                            recommendation = "Pide que la indemnización se limite a tres meses de canon.",
                            citations = listOf(AuditCitation("CO-ley-820-art24", "Ley 820 de 2003", "24")),
                            verified = true,
                        ),
                        AuditFinding(clauseIndex = 4, clauseHeading = "TERCERA.- CANON Y REAJUSTE.", risk = "medium"),
                    ),
                ),
                expanded = setOf(9),
            ),
            onEventSend = {},
            paddingValues = PaddingValues(SPACING_MEDIUM.dp),
        )
    }
}

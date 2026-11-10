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


package eu.europa.ec.dashboardfeature.ui.audit.progress

import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditStage
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
import eu.europa.ec.uilogic.navigation.helper.generateComposableArguments
import eu.europa.ec.uilogic.navigation.helper.generateComposableNavigationLink
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach

private const val TIP_INTERVAL_MILLIS = 6_000L

@Composable
internal fun AuditProgressScreen(
    navController: NavController,
    viewModel: AuditProgressViewModel,
) {
    val state: State by viewModel.viewState.collectAsStateWithLifecycle()

    // Poll only while the screen is visible
    LifecycleStartEffect(Unit) {
        viewModel.setEvent(Event.StartPolling)
        onStopOrDispose { viewModel.setEvent(Event.StopPolling) }
    }

    LaunchedEffect(Unit) {
        viewModel.effect.onEach { effect ->
            when (effect) {
                Effect.Navigation.Pop -> navController.popBackStack()
                is Effect.Navigation.Report -> navController.navigate(
                    generateComposableNavigationLink(
                        screen = DashboardScreens.AuditReport,
                        arguments = generateComposableArguments(mapOf("analysisId" to effect.analysisId)),
                    )
                ) {
                    popUpTo(DashboardScreens.AuditProgress.screenRoute) { inclusive = true }
                }
            }
        }.collect()
    }

    ContentScreen(
        navigatableAction = ScreenNavigateAction.BACKABLE,
        onBack = { viewModel.setEvent(Event.Pop) },
    ) { contentPadding ->
        Content(
            state = state,
            onLeave = { viewModel.setEvent(Event.Pop) },
            paddingValues = contentPadding,
        )
    }
}

@Composable
private fun Content(
    state: State,
    onLeave: () -> Unit,
    paddingValues: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(SPACING_LARGE.dp),
    ) {
        Header(state = state)

        if (state.failureMessage != null) {
            FailureCard(message = state.failureMessage, onBack = onLeave)
            return@Column
        }

        OverallProgress(stage = state.stage)
        Timeline(state = state)
        TipsCard(tips = state.tips)

        Text(
            text = stringResource(R.string.audit_progress_leave_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onLeave) {
            Text(stringResource(R.string.audit_progress_leave))
        }
    }
}

@Composable
private fun Header(state: State) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1_100, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale",
    )
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp),
    ) {
        Box(
            modifier = Modifier
                .size(88.dp)
                .scale(if (state.failureMessage == null) scale else 1f)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            WrapIcon(
                iconData = if (state.failureMessage == null) AppIcons.Verified else AppIcons.Error,
                customTint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(40.dp),
            )
        }
        Text(
            text = stringResource(
                if (state.failureMessage == null) R.string.audit_progress_title else R.string.audit_progress_failed_title
            ),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        if (state.fileName.isNotBlank()) {
            Text(
                text = state.fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (state.failureMessage == null) {
            Text(
                text = stringResource(R.string.audit_progress_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun OverallProgress(stage: AuditStage) {
    val steps = AuditStage.entries.size
    val target = (stage.ordinal + 0.5f) / steps
    val progress by animateFloatAsState(targetValue = target, animationSpec = tween(800), label = "progress")
    Column(verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp)) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            strokeCap = StrokeCap.Round,
        )
        Text(
            text = stringResource(R.string.audit_progress_percent, (progress * 100).toInt()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.End),
        )
    }
}

@Composable
private fun Timeline(state: State) {
    Column(verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp)) {
        AuditStage.entries.forEach { step ->
            val status = when {
                step.ordinal < state.stage.ordinal -> StepStatus.DONE
                step == state.stage -> StepStatus.CURRENT
                else -> StepStatus.PENDING
            }
            TimelineStep(
                label = stringResource(step.labelRes()),
                detail = stepDetail(step, state),
                status = status,
                number = step.ordinal + 1,
            )
        }
    }
}

private enum class StepStatus { DONE, CURRENT, PENDING }

@StringRes
private fun AuditStage.labelRes(): Int = when (this) {
    AuditStage.RECEIVED -> R.string.audit_stage_received
    AuditStage.EXTRACTING_TEXT -> R.string.audit_stage_extracting
    AuditStage.PROTECTING_PERSONAL_DATA -> R.string.audit_stage_protecting
    AuditStage.IDENTIFYING_CLAUSES -> R.string.audit_stage_clauses
    AuditStage.SEARCHING_LAW -> R.string.audit_stage_law
    AuditStage.ANALYZING_WITH_AI -> R.string.audit_stage_ai
    AuditStage.PREPARING_REPORT -> R.string.audit_stage_report
}

/** Counters shown under the step that produced them, once the backend reports them. */
@Composable
private fun stepDetail(step: AuditStage, state: State): String? = when (step) {
    AuditStage.EXTRACTING_TEXT -> state.pages?.let { pluralStringResource(R.plurals.audit_detail_pages, it, it) }
    AuditStage.IDENTIFYING_CLAUSES -> state.clauseCount?.let { pluralStringResource(R.plurals.audit_detail_clauses, it, it) }
    AuditStage.SEARCHING_LAW -> state.legalSources?.let { pluralStringResource(R.plurals.audit_detail_sources, it, it) }
    else -> null
}

@Composable
private fun TimelineStep(label: String, detail: String?, status: StepStatus, number: Int) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.DONE -> Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    WrapIcon(iconData = AppIcons.Check, customTint = colors.onPrimary, modifier = Modifier.size(18.dp))
                }

                StepStatus.CURRENT -> CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )

                StepStatus.PENDING -> Box(
                    modifier = Modifier
                        .size(28.dp)
                        .border(2.dp, colors.outlineVariant, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        Column(modifier = Modifier.padding(start = SPACING_MEDIUM.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (status == StepStatus.CURRENT) FontWeight.SemiBold else FontWeight.Normal,
                color = if (status == StepStatus.PENDING) colors.onSurfaceVariant else colors.onSurface,
            )
            AnimatedVisibility(visible = detail != null, enter = fadeIn() + expandVertically()) {
                Text(
                    text = detail.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.primary,
                )
            }
        }
    }
}

@Composable
private fun TipsCard(tips: List<String>) {
    if (tips.isEmpty()) return
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(index) {
        delay(TIP_INTERVAL_MILLIS)
        index = (index + 1) % tips.size
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { index = (index + 1) % tips.size },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(SPACING_LARGE.dp),
            verticalArrangement = Arrangement.spacedBy(SPACING_SMALL.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WrapIcon(
                    iconData = AppIcons.Info,
                    customTint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.audit_progress_tip_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(start = SPACING_SMALL.dp),
                )
            }
            AnimatedContent(
                targetState = index,
                transitionSpec = {
                    (slideInHorizontally { it / 3 } + fadeIn(tween(400)))
                        .togetherWith(slideOutHorizontally { -it / 3 } + fadeOut(tween(250)))
                },
                label = "tip",
            ) { tipIndex ->
                Text(
                    text = tips[tipIndex],
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                tips.indices.forEach { dot ->
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(width = if (dot == index) 16.dp else 6.dp, height = 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (dot == index) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.3f)
                            ),
                    )
                }
            }
        }
    }
}

@Composable
private fun FailureCard(message: String, onBack: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(
            modifier = Modifier.padding(SPACING_LARGE.dp),
            verticalArrangement = Arrangement.spacedBy(SPACING_MEDIUM.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Button(modifier = Modifier.fillMaxWidth(), onClick = onBack) {
                Text(stringResource(R.string.audit_progress_back))
            }
        }
    }
}

@ThemeModePreviews
@Composable
private fun AuditProgressScreenPreview() {
    PreviewTheme {
        Content(
            state = State(
                fileName = "contrato-arrendamiento.pdf",
                stage = AuditStage.SEARCHING_LAW,
                pages = 3,
                clauseCount = 17,
                tips = listOf(stringResource(R.string.audit_tip_1), stringResource(R.string.audit_tip_2)),
            ),
            onLeave = {},
            paddingValues = PaddingValues(SPACING_MEDIUM.dp),
        )
    }
}

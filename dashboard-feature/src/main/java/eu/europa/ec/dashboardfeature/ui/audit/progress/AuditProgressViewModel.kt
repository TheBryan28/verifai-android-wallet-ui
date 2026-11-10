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

import androidx.lifecycle.viewModelScope
import eu.europa.ec.businesslogic.controller.audit.AuditRecord
import eu.europa.ec.dashboardfeature.interactor.DocumentAuditInteractor
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditStage
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditStatus
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel
import org.koin.core.annotation.InjectedParam

data class State(
    val fileName: String = "",
    val stage: AuditStage = AuditStage.RECEIVED,
    val pages: Int? = null,
    val clauseCount: Int? = null,
    val legalSources: Int? = null,
    /** Set when the analysis ended without a report. */
    val failureMessage: String? = null,
    val tips: List<String>,
) : ViewState

sealed class Event : ViewEvent {
    /** The screen is visible: poll the status. */
    data object StartPolling : Event()

    /** The screen is no longer visible: stop polling. */
    data object StopPolling : Event()
    data object Pop : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data class Report(val analysisId: String) : Navigation()
    }
}

@KoinViewModel
class AuditProgressViewModel(
    private val documentAuditInteractor: DocumentAuditInteractor,
    private val resourceProvider: ResourceProvider,
    @InjectedParam private val analysisId: String,
) : MviViewModel<Event, State, Effect>() {

    private companion object {
        const val POLL_INTERVAL_MILLIS = 3_000L
    }

    private var pollingJob: Job? = null

    override fun setInitialState(): State = State(
        tips = listOf(
            R.string.audit_tip_1, R.string.audit_tip_2, R.string.audit_tip_3, R.string.audit_tip_4,
            R.string.audit_tip_5, R.string.audit_tip_6, R.string.audit_tip_7, R.string.audit_tip_8,
        ).map { resourceProvider.getString(it) }.shuffled(),
    )

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.StartPolling -> startPolling()
            is Event.StopPolling -> pollingJob?.cancel()
            is Event.Pop -> setEffect { Effect.Navigation.Pop }
        }
    }

    private fun startPolling() {
        if (pollingJob?.isActive == true) return
        pollingJob = viewModelScope.launch {
            documentAuditInteractor.getRecord(analysisId)?.let(::showRecord)
            while (isActive) {
                // A failed request (no connection, timeout) is retried on the next tick
                documentAuditInteractor.refresh(analysisId).onSuccess { record ->
                    showRecord(record)
                    if (AuditStatus.isFinal(record.status)) {
                        if (record.status == AuditStatus.COMPLETED && record.hasReport) {
                            setEffect { Effect.Navigation.Report(analysisId) }
                        }
                        return@launch
                    }
                }
                delay(POLL_INTERVAL_MILLIS)
            }
        }
    }

    private fun showRecord(record: AuditRecord) {
        setState {
            copy(
                fileName = record.fileName,
                stage = AuditStage.fromCode(record.stage),
                pages = record.pages,
                clauseCount = record.clauseCount,
                legalSources = record.legalSources,
                failureMessage = when (record.status) {
                    AuditStatus.FAILED -> documentAuditInteractor.failureMessage(record.errorMessage)
                    AuditStatus.EXPIRED -> resourceProvider.getString(R.string.audit_progress_expired)
                    else -> null
                },
            )
        }
    }
}

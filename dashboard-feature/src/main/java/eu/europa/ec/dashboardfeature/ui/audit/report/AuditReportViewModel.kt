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

import android.content.Context
import androidx.lifecycle.viewModelScope
import eu.europa.ec.dashboardfeature.interactor.DocumentAuditInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentSignInteractor
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditReport
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditRisk
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.InjectedParam
import org.koin.core.annotation.KoinViewModel

data class State(
    val isLoading: Boolean = true,
    val fileName: String = "",
    val report: AuditReport? = null,
    /** By default only clauses with something to say are listed. */
    val onlyWithIssues: Boolean = true,
    val expanded: Set<Int> = emptySet(),
    val errorMessage: String? = null,
) : ViewState {
    val visibleFindings
        get() = report?.findings.orEmpty()
            .filter { !onlyWithIssues || it.riskLevel != AuditRisk.NONE || !it.evaluated }
            .sortedWith(compareBy({ it.riskLevel.ordinal }, { it.clauseIndex }))
}

sealed class Event : ViewEvent {
    data object Pop : Event()
    data class ShowOnlyWithIssues(val value: Boolean) : Event()
    data class ToggleFinding(val clauseIndex: Int) : Event()
    data class SignAnyway(val context: Context) : Event()
    data object DontSign : Event()
    data object DismissError : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data object Home : Navigation()
    }
}

@KoinViewModel
class AuditReportViewModel(
    private val documentAuditInteractor: DocumentAuditInteractor,
    private val documentSignInteractor: DocumentSignInteractor,
    @InjectedParam private val analysisId: String,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State = State()

    init {
        viewModelScope.launch {
            val record = documentAuditInteractor.getRecord(analysisId)
            documentAuditInteractor.getReport(analysisId)
                .onSuccess { report ->
                    setState { copy(isLoading = false, fileName = record?.fileName.orEmpty(), report = report) }
                }
                .onFailure { error ->
                    setState { copy(isLoading = false, errorMessage = documentAuditInteractor.errorMessage(error)) }
                }
        }
    }

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Pop -> setEffect { Effect.Navigation.Pop }
            is Event.ShowOnlyWithIssues -> setState { copy(onlyWithIssues = event.value) }
            is Event.ToggleFinding -> setState {
                copy(expanded = if (event.clauseIndex in expanded) expanded - event.clauseIndex else expanded + event.clauseIndex)
            }

            is Event.SignAnyway -> viewModelScope.launch {
                // Sign exactly the document that was audited
                documentAuditInteractor.documentForSigning(analysisId)
                    .onSuccess { uri -> documentSignInteractor.launchRqesSdk(event.context, uri) }
                    .onFailure { error ->
                        setState { copy(errorMessage = documentAuditInteractor.errorMessage(error)) }
                    }
            }

            is Event.DontSign -> setEffect { Effect.Navigation.Home }
            is Event.DismissError -> setState { copy(errorMessage = null) }
        }
    }
}

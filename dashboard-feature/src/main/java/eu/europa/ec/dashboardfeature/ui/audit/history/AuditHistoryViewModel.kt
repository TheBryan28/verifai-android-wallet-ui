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

import androidx.lifecycle.viewModelScope
import eu.europa.ec.businesslogic.controller.audit.AuditRecord
import eu.europa.ec.dashboardfeature.interactor.DocumentAuditInteractor
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditStatus
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class State(
    val isLoading: Boolean = true,
    val records: List<AuditRecord> = emptyList(),
    /** Record waiting for the user to confirm its deletion. */
    val pendingDeletion: AuditRecord? = null,
) : ViewState

sealed class Event : ViewEvent {
    /** The screen became visible: reload and update unfinished analyses once. */
    data object Refresh : Event()
    data object Pop : Event()
    data class Open(val record: AuditRecord) : Event()
    data class AskDelete(val record: AuditRecord) : Event()
    data object ConfirmDelete : Event()
    data object CancelDelete : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data class Progress(val analysisId: String) : Navigation()
        data class Report(val analysisId: String) : Navigation()
    }
}

@KoinViewModel
class AuditHistoryViewModel(
    private val documentAuditInteractor: DocumentAuditInteractor,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State = State()

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.Refresh -> refresh()
            is Event.Pop -> setEffect { Effect.Navigation.Pop }
            is Event.Open -> setEffect {
                if (event.record.hasReport) Effect.Navigation.Report(event.record.id)
                else Effect.Navigation.Progress(event.record.id)
            }

            is Event.AskDelete -> setState { copy(pendingDeletion = event.record) }
            is Event.CancelDelete -> setState { copy(pendingDeletion = null) }
            is Event.ConfirmDelete -> {
                val record = viewState.value.pendingDeletion ?: return
                setState { copy(pendingDeletion = null) }
                viewModelScope.launch {
                    documentAuditInteractor.delete(record.id)
                    setState { copy(records = records.filterNot { it.id == record.id }) }
                }
            }
        }
    }

    private fun refresh() {
        viewModelScope.launch {
            val records = documentAuditInteractor.getRecords()
            setState { copy(isLoading = false, records = records) }
            // One update per open screen, no background polling
            val unfinished = viewState.value.records.filterNot { AuditStatus.isFinal(it.status) }
            if (unfinished.isEmpty()) return@launch
            unfinished.forEach { documentAuditInteractor.refresh(it.id) }
            val updated = documentAuditInteractor.getRecords()
            setState { copy(records = updated) }
        }
    }
}

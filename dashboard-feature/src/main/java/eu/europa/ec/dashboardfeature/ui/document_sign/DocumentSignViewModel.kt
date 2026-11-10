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

import android.content.Context
import android.net.Uri
import androidx.lifecycle.viewModelScope
import eu.europa.ec.dashboardfeature.interactor.DocumentAuditInteractor
import eu.europa.ec.dashboardfeature.interactor.DocumentSignInteractor
import eu.europa.ec.dashboardfeature.ui.document_sign.model.DocumentSignButtonUi
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.uilogic.component.AppIcons
import eu.europa.ec.uilogic.component.ListItemDataUi
import eu.europa.ec.uilogic.component.ListItemMainContentDataUi
import eu.europa.ec.uilogic.component.ListItemTrailingContentDataUi
import eu.europa.ec.uilogic.component.content.ContentErrorConfig
import eu.europa.ec.uilogic.mvi.MviViewModel
import eu.europa.ec.uilogic.mvi.ViewEvent
import eu.europa.ec.uilogic.mvi.ViewSideEffect
import eu.europa.ec.uilogic.mvi.ViewState
import kotlinx.coroutines.launch
import org.koin.core.annotation.KoinViewModel

data class State(
    val isLoading: Boolean = false,
    val error: ContentErrorConfig? = null,
    val title: String,
    val subtitle: String,
    val buttonUi: DocumentSignButtonUi,
    val historyButtonUi: DocumentSignButtonUi,
    val countries: List<String>,
    val selectedCountry: String,
    /** Document chosen by the user and waiting for "review with AI" or "sign without review". */
    val pendingDocument: Uri? = null,
    val auditError: String? = null,
) : ViewState

sealed class Event : ViewEvent {
    data object Pop : Event()
    data object OnSelectDocument : Event()
    data class DocumentUriRetrieved(val context: Context, val uri: Uri) : Event()
    data class CountrySelected(val country: String) : Event()
    data object StartAudit : Event()
    data class SignWithoutAudit(val context: Context) : Event()
    data object DismissOptions : Event()
    data object DismissAuditError : Event()
    data object OpenAuditHistory : Event()
}

sealed class Effect : ViewSideEffect {
    sealed class Navigation : Effect() {
        data object Pop : Navigation()
        data class AuditProgress(val analysisId: String) : Navigation()
        data object AuditHistory : Navigation()
    }

    data class OpenDocumentSelection(val selection: List<String>) : Effect()
}

@KoinViewModel
class DocumentSignViewModel(
    private val documentSignInteractor: DocumentSignInteractor,
    private val documentAuditInteractor: DocumentAuditInteractor,
    private val resourceProvider: ResourceProvider,
) : MviViewModel<Event, State, Effect>() {

    override fun setInitialState(): State = State(
        title = resourceProvider.getString(R.string.document_sign_title),
        subtitle = resourceProvider.getString(R.string.document_sign_subtitle),
        buttonUi = documentSignInteractor.getItemUi(),
        historyButtonUi = DocumentSignButtonUi(
            data = ListItemDataUi(
                itemId = AUDIT_HISTORY_BUTTON_ID,
                mainContentData = ListItemMainContentDataUi.Text(
                    text = resourceProvider.getString(R.string.audit_history_item)
                ),
                trailingContentData = ListItemTrailingContentDataUi.Icon(
                    iconData = AppIcons.Documents
                ),
            )
        ),
        countries = documentAuditInteractor.supportedCountries,
        selectedCountry = documentAuditInteractor.supportedCountries.first(),
    )

    override fun handleEvents(event: Event) {
        when (event) {
            is Event.OnSelectDocument -> {
                setEffect { Effect.OpenDocumentSelection(listOf("application/pdf")) }
            }

            is Event.Pop -> setEffect { Effect.Navigation.Pop }

            // The user chooses next whether to review the document with AI before signing
            is Event.DocumentUriRetrieved -> setState { copy(pendingDocument = event.uri) }

            is Event.CountrySelected -> setState { copy(selectedCountry = event.country) }

            is Event.StartAudit -> startAudit()

            is Event.SignWithoutAudit -> {
                val uri = viewState.value.pendingDocument ?: return
                setState { copy(pendingDocument = null) }
                documentSignInteractor.launchRqesSdk(event.context, uri)
            }

            is Event.DismissOptions -> setState { copy(pendingDocument = null) }

            is Event.DismissAuditError -> setState { copy(auditError = null) }

            is Event.OpenAuditHistory -> setEffect { Effect.Navigation.AuditHistory }
        }
    }

    private fun startAudit() {
        val uri = viewState.value.pendingDocument ?: return
        val country = viewState.value.selectedCountry
        setState { copy(isLoading = true, pendingDocument = null) }
        viewModelScope.launch {
            documentAuditInteractor.startAudit(uri, country)
                .onSuccess { record ->
                    setState { copy(isLoading = false) }
                    setEffect { Effect.Navigation.AuditProgress(record.id) }
                }
                .onFailure { error ->
                    setState {
                        copy(isLoading = false, auditError = documentAuditInteractor.errorMessage(error))
                    }
                }
        }
    }
}

private const val AUDIT_HISTORY_BUTTON_ID = "auditHistoryButtonId"

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

package eu.europa.ec.dashboardfeature.interactor

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import eu.europa.ec.businesslogic.controller.audit.AuditRecord
import eu.europa.ec.businesslogic.controller.audit.AuditSession
import eu.europa.ec.businesslogic.controller.audit.AuditStorageController
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditReport
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditStage
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditStatus
import eu.europa.ec.networklogic.repository.AuditApiException
import eu.europa.ec.networklogic.repository.AuditRepository
import eu.europa.ec.networklogic.repository.CreateAuditRequest
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64

/** A document chosen by the user, read through the Storage Access Framework. */
data class SelectedDocument(
    val name: String,
    val mimeType: String,
    val content: ByteArray,
)

interface DocumentReader {
    /** Reads the document; fails if it is larger than [maxBytes]. */
    suspend fun read(uri: Uri, maxBytes: Int): SelectedDocument

    /** Keeps read access to the document, so it can be signed after the audit. */
    fun keepAccess(uri: Uri)

    fun parse(uriString: String): Uri
}

class DocumentReaderImpl(
    private val resourceProvider: ResourceProvider,
) : DocumentReader {

    override suspend fun read(uri: Uri, maxBytes: Int): SelectedDocument = withContext(Dispatchers.IO) {
        val resolver = resourceProvider.provideContext().contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            ?: uri.lastPathSegment.orEmpty()
        val content = resolver.openInputStream(uri)?.use { input ->
            // Read at most maxBytes + 1 bytes (InputStream.readNBytes needs API 33)
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (output.size() <= maxBytes) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            if (output.size() > maxBytes) throw AuditFailure(AuditFailure.Reason.FILE_TOO_LARGE)
            output.toByteArray()
        } ?: throw IOException("Cannot open $uri")
        SelectedDocument(name = name, mimeType = resolver.getType(uri).orEmpty(), content = content)
    }

    override fun keepAccess(uri: Uri) {
        runCatching {
            resourceProvider.provideContext().contentResolver
                .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun parse(uriString: String): Uri = Uri.parse(uriString)
}

/** A failure the user can understand; [message] is already translated. */
class AuditFailure(val reason: Reason, detail: String? = null) : Exception(detail ?: reason.name) {
    enum class Reason {
        FILE_TOO_LARGE, UNSUPPORTED_TYPE, DAILY_LIMIT, SESSION_LIMIT, NOT_CONFIGURED,
        NETWORK, DOCUMENT_CHANGED, UNREADABLE, NOT_FOUND, UNEXPECTED,
    }
}

interface DocumentAuditInteractor {
    val supportedCountries: List<String>

    /** Creates the analysis, uploads the document and stores the record on the phone. */
    suspend fun startAudit(uri: Uri, country: String): Result<AuditRecord>

    /**
     * Updates the record from the API. When the analysis is COMPLETED, downloads the report,
     * stores it encrypted on the phone and deletes the server copy.
     */
    suspend fun refresh(analysisId: String): Result<AuditRecord>

    suspend fun getRecord(analysisId: String): AuditRecord?
    suspend fun getRecords(): List<AuditRecord>
    suspend fun getReport(analysisId: String): Result<AuditReport>

    /** Deletes the record and the report on the phone, and anything left on the server. */
    suspend fun delete(analysisId: String): Result<Unit>

    /** Returns the audited document, checking that it has not changed since the audit. */
    suspend fun documentForSigning(analysisId: String): Result<Uri>

    /** Translated message for a failure of any of the functions above. */
    fun errorMessage(error: Throwable): String

    /** Translated message for an analysis that ended in FAILED, from its error code. */
    fun failureMessage(errorCode: String?): String
}

class DocumentAuditInteractorImpl(
    private val auditRepository: AuditRepository,
    private val auditStorage: AuditStorageController,
    private val documentReader: DocumentReader,
    private val resourceProvider: ResourceProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val clock: () -> Long = System::currentTimeMillis,
) : DocumentAuditInteractor {

    private companion object {
        const val MAX_SIZE_BYTES = 10 * 1024 * 1024
        const val SESSION_RENEWAL_MARGIN_SECONDS = 5 * 60
        val ALLOWED_CONTENT_TYPES = setOf("application/pdf", "image/png", "image/jpeg")
    }

    override val supportedCountries = listOf("CO", "ES")

    override suspend fun startAudit(uri: Uri, country: String): Result<AuditRecord> = runCatching {
        val document = runCatching { documentReader.read(uri, MAX_SIZE_BYTES) }
            .getOrElse { throw if (it is AuditFailure) it else AuditFailure(AuditFailure.Reason.UNREADABLE) }
        if (document.mimeType !in ALLOWED_CONTENT_TYPES) {
            throw AuditFailure(AuditFailure.Reason.UNSUPPORTED_TYPE)
        }
        val sha256 = sha256Base64(document.content)

        val created = withSession { token ->
            auditRepository.createAnalysis(
                token,
                CreateAuditRequest(
                    country = country,
                    sha256 = sha256,
                    sizeBytes = document.content.size.toLong(),
                    contentType = document.mimeType,
                ),
            )
        }
        auditRepository.uploadDocument(created.upload, document.content).getOrThrow()
        documentReader.keepAccess(uri)

        AuditRecord(
            id = created.id,
            fileName = document.name,
            country = country,
            createdAtEpochMillis = clock(),
            documentUri = uri.toString(),
            documentSha256 = sha256,
            status = AuditStatus.PENDING,
            stage = AuditStage.RECEIVED.code,
        ).also { auditStorage.saveRecord(it) }
    }

    override suspend fun refresh(analysisId: String): Result<AuditRecord> = runCatching {
        val record = auditStorage.getRecord(analysisId) ?: throw AuditFailure(AuditFailure.Reason.NOT_FOUND)
        if (AuditStatus.isFinal(record.status)) return@runCatching record

        val status = try {
            withSession { token -> auditRepository.getAnalysis(token, analysisId) }
        } catch (_: AuditApiException.NotFound) {
            return@runCatching record.copy(status = AuditStatus.EXPIRED).also { auditStorage.saveRecord(it) }
        }

        val updated = record.copy(
            status = status.status,
            // The API reports COMPLETED or FAILED as the stage of finished analyses: keep the last pipeline stage
            stage = status.stage?.takeIf { code -> AuditStage.entries.any { it.code == code } } ?: record.stage,
            errorMessage = status.errorMessage,
            pages = status.pages ?: record.pages,
            clauseCount = status.clauseCount ?: record.clauseCount,
            legalSources = status.legalSources ?: record.legalSources,
        )
        val reportUrl = status.reportUrl
        if (status.status != AuditStatus.COMPLETED || reportUrl == null) {
            // Keep polling until the report URL is available
            val pending = if (status.status == AuditStatus.COMPLETED) updated.copy(status = AuditStatus.PROCESSING) else updated
            return@runCatching pending.also { auditStorage.saveRecord(it) }
        }

        val reportJson = auditRepository.downloadReport(reportUrl).getOrThrow()
        json.decodeFromString<AuditReport>(reportJson) // only store a report the app can read
        auditStorage.saveReport(analysisId, reportJson)
        val completed = updated.copy(hasReport = true).also { auditStorage.saveRecord(it) }

        // The phone keeps the report: delete the document and report from the server now
        runCatching { withSession { token -> auditRepository.deleteAnalysis(token, analysisId) } }
        completed
    }

    override suspend fun getRecord(analysisId: String): AuditRecord? = auditStorage.getRecord(analysisId)

    override suspend fun getRecords(): List<AuditRecord> = auditStorage.getRecords()

    override suspend fun getReport(analysisId: String): Result<AuditReport> = runCatching {
        val reportJson = auditStorage.getReport(analysisId) ?: throw AuditFailure(AuditFailure.Reason.NOT_FOUND)
        json.decodeFromString<AuditReport>(reportJson)
    }

    override suspend fun delete(analysisId: String): Result<Unit> = runCatching {
        val record = auditStorage.getRecord(analysisId)
        auditStorage.deleteRecord(analysisId)
        if (record != null && !record.hasReport) {
            // Best effort: the server copy also expires on its own
            runCatching { withSession { token -> auditRepository.deleteAnalysis(token, analysisId) } }
        }
    }

    override suspend fun documentForSigning(analysisId: String): Result<Uri> = runCatching {
        val record = auditStorage.getRecord(analysisId) ?: throw AuditFailure(AuditFailure.Reason.NOT_FOUND)
        val uri = documentReader.parse(record.documentUri)
        val document = runCatching { documentReader.read(uri, MAX_SIZE_BYTES) }
            .getOrElse { throw AuditFailure(AuditFailure.Reason.UNREADABLE) }
        if (sha256Base64(document.content) != record.documentSha256) {
            throw AuditFailure(AuditFailure.Reason.DOCUMENT_CHANGED)
        }
        uri
    }

    override fun errorMessage(error: Throwable): String {
        val reason = when (error) {
            is AuditFailure -> error.reason
            is AuditApiException.LimitReached ->
                if (error.code == "SESSION_LIMIT_REACHED") AuditFailure.Reason.SESSION_LIMIT else AuditFailure.Reason.DAILY_LIMIT

            is AuditApiException.NotFound -> AuditFailure.Reason.NOT_FOUND
            is AuditApiException.RequestFailed ->
                if (error.status == 403) AuditFailure.Reason.NOT_CONFIGURED else AuditFailure.Reason.UNEXPECTED

            is IOException -> AuditFailure.Reason.NETWORK
            else -> AuditFailure.Reason.UNEXPECTED
        }
        return when (reason) {
            AuditFailure.Reason.FILE_TOO_LARGE -> resourceProvider.getString(R.string.audit_error_file_too_large)
            AuditFailure.Reason.UNSUPPORTED_TYPE -> resourceProvider.getString(R.string.audit_error_unsupported_type)
            AuditFailure.Reason.DAILY_LIMIT -> resourceProvider.getString(R.string.audit_error_daily_limit)
            AuditFailure.Reason.SESSION_LIMIT -> resourceProvider.getString(R.string.audit_error_session_limit)
            AuditFailure.Reason.NOT_CONFIGURED -> resourceProvider.getString(R.string.audit_error_not_configured)
            AuditFailure.Reason.NETWORK -> resourceProvider.getString(R.string.audit_error_network)
            AuditFailure.Reason.DOCUMENT_CHANGED -> resourceProvider.getString(R.string.audit_error_document_changed)
            AuditFailure.Reason.UNREADABLE -> resourceProvider.getString(R.string.audit_error_unreadable)
            AuditFailure.Reason.NOT_FOUND -> resourceProvider.getString(R.string.audit_progress_expired)
            AuditFailure.Reason.UNEXPECTED ->
                resourceProvider.getString(R.string.audit_error_generic, error.javaClass.simpleName)
        }
    }

    override fun failureMessage(errorCode: String?): String = when (errorCode) {
        "ScannedPdfNotSupported" -> resourceProvider.getString(R.string.audit_failure_scanned_pdf)
        "FileTooLarge" -> resourceProvider.getString(R.string.audit_error_file_too_large)
        "UnsupportedFileType", "ContentTypeMismatch" -> resourceProvider.getString(R.string.audit_error_unsupported_type)
        "ChecksumMismatch", "FileSizeMismatch" -> resourceProvider.getString(R.string.audit_failure_changed_upload)
        "NoTextFound", "EncryptedPdf", "InvalidFile" -> resourceProvider.getString(R.string.audit_failure_unreadable)
        "UnsupportedCountry", "CorpusNotFound" -> resourceProvider.getString(R.string.audit_failure_no_corpus)
        else -> resourceProvider.getString(R.string.audit_failure_generic, errorCode.orEmpty())
    }

    /** Runs [call] with a valid session; on 401 it renews the session and retries once. */
    private suspend fun <T> withSession(call: suspend (String) -> Result<T>): T {
        val first = call(sessionToken(forceNew = false))
        if (first.exceptionOrNull() is AuditApiException.Unauthorized) {
            return call(sessionToken(forceNew = true)).getOrThrow()
        }
        return first.getOrThrow()
    }

    /** The session is requested only when the user audits a contract, never when the app opens. */
    private suspend fun sessionToken(forceNew: Boolean): String {
        val nowSeconds = clock() / 1000
        val current = auditStorage.getSession()
        if (!forceNew && current != null &&
            current.expiresAtEpochSeconds - nowSeconds > SESSION_RENEWAL_MARGIN_SECONDS
        ) {
            return current.token
        }
        val response = auditRepository.createSession().getOrThrow()
        val session = AuditSession(
            token = response.token,
            expiresAtEpochSeconds = Instant.parse(response.expiresAt).epochSecond,
        )
        auditStorage.setSession(session)
        return session.token
    }

    private fun sha256Base64(content: ByteArray): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(content))
}

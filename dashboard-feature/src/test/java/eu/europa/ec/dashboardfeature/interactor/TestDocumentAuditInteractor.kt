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

import android.net.Uri
import eu.europa.ec.businesslogic.controller.audit.AuditRecord
import eu.europa.ec.businesslogic.controller.audit.AuditSession
import eu.europa.ec.businesslogic.controller.audit.AuditStorageController
import eu.europa.ec.dashboardfeature.ui.audit.model.AuditStatus
import eu.europa.ec.networklogic.repository.AuditApiException
import eu.europa.ec.networklogic.repository.AuditRepository
import eu.europa.ec.networklogic.repository.AuditSessionResponse
import eu.europa.ec.networklogic.repository.AuditStatusResponse
import eu.europa.ec.networklogic.repository.AuditUpload
import eu.europa.ec.networklogic.repository.CreateAuditRequest
import eu.europa.ec.networklogic.repository.CreateAuditResponse
import eu.europa.ec.resourceslogic.R
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import eu.europa.ec.testlogic.rule.CoroutineTestRule
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

class TestDocumentAuditInteractor {

    @get:Rule
    val coroutineRule = CoroutineTestRule()

    @Mock
    private lateinit var resourceProvider: ResourceProvider

    @Mock
    private lateinit var uri: Uri

    private lateinit var repository: FakeAuditRepository
    private lateinit var storage: FakeAuditStorage
    private lateinit var reader: FakeDocumentReader
    private lateinit var interactor: DocumentAuditInteractor
    private lateinit var closeable: AutoCloseable

    private val nowMillis = 1_800_000_000_000L

    @Before
    fun before() {
        closeable = MockitoAnnotations.openMocks(this)
        whenever(resourceProvider.getString(any())).thenAnswer { "text-${it.arguments[0]}" }
        repository = FakeAuditRepository()
        storage = FakeAuditStorage()
        reader = FakeDocumentReader(uri)
        interactor = DocumentAuditInteractorImpl(
            auditRepository = repository,
            auditStorage = storage,
            documentReader = reader,
            resourceProvider = resourceProvider,
            clock = { nowMillis },
        )
    }

    @After
    fun after() {
        closeable.close()
    }

    //region startAudit
    @Test
    fun `Given no session, When startAudit is called, Then it creates a session, the analysis and uploads the document`() =
        runTest {
            val result = interactor.startAudit(uri, "CO").getOrThrow()

            assertEquals(1, repository.sessionsCreated)
            assertEquals("token-1", repository.lastToken)
            assertEquals(
                CreateAuditRequest(
                    country = "CO",
                    // SHA-256 of "%PDF-1.7 test" in Base64
                    sha256 = java.util.Base64.getEncoder().encodeToString(
                        java.security.MessageDigest.getInstance("SHA-256").digest(PDF_CONTENT)
                    ),
                    sizeBytes = PDF_CONTENT.size.toLong(),
                    contentType = "application/pdf",
                ),
                repository.lastCreateRequest,
            )
            assertTrue(repository.uploaded.contentEquals(PDF_CONTENT))
            assertTrue(reader.accessKept)
            assertEquals(AuditStatus.PENDING, result.status)
            assertEquals(result, storage.getRecord("analysis-1"))
        }

    @Test
    fun `Given a valid session, When startAudit is called, Then the session is reused`() = runTest {
        storage.setSession(AuditSession("saved-token", nowMillis / 1000 + 3_600))

        interactor.startAudit(uri, "ES").getOrThrow()

        assertEquals(0, repository.sessionsCreated)
        assertEquals("saved-token", repository.lastToken)
    }

    @Test
    fun `Given a session about to expire, When startAudit is called, Then a new session is requested`() = runTest {
        storage.setSession(AuditSession("old-token", nowMillis / 1000 + 60))

        interactor.startAudit(uri, "CO").getOrThrow()

        assertEquals(1, repository.sessionsCreated)
    }

    @Test
    fun `Given the API answers 401, When startAudit is called, Then the session is renewed and the call retried once`() =
        runTest {
            storage.setSession(AuditSession("revoked-token", nowMillis / 1000 + 3_600))
            repository.unauthorizedTokens += "revoked-token"

            interactor.startAudit(uri, "CO").getOrThrow()

            assertEquals(1, repository.sessionsCreated)
            assertEquals(2, repository.createCalls)
            assertEquals("token-1", repository.lastToken)
        }

    @Test
    fun `Given an unsupported file, When startAudit is called, Then nothing is sent`() = runTest {
        reader.mimeType = "text/plain"

        val result = interactor.startAudit(uri, "CO")

        assertTrue(result.isFailure)
        assertEquals(0, repository.createCalls)
        assertEquals(
            "text-${R.string.audit_error_unsupported_type}",
            interactor.errorMessage(result.exceptionOrNull()!!),
        )
    }

    @Test
    fun `Given the session limit is reached, When startAudit fails, Then the session limit message is shown`() = runTest {
        repository.createError = AuditApiException.LimitReached("SESSION_LIMIT_REACHED", "limit")

        val result = interactor.startAudit(uri, "CO")

        assertEquals(
            "text-${R.string.audit_error_session_limit}",
            interactor.errorMessage(result.exceptionOrNull()!!),
        )
        assertTrue(storage.getRecords().isEmpty())
    }
    //endregion

    //region refresh
    @Test
    fun `Given the analysis is processing, When refresh is called, Then stage and counters are stored`() = runTest {
        storage.saveRecord(pendingRecord())
        repository.status = statusResponse(AuditStatus.PROCESSING, stage = "SEARCHING_LAW", clauseCount = 17)

        val record = interactor.refresh("analysis-1").getOrThrow()

        assertEquals(AuditStatus.PROCESSING, record.status)
        assertEquals("SEARCHING_LAW", record.stage)
        assertEquals(17, record.clauseCount)
        assertEquals(0, repository.reportDownloads)
    }

    @Test
    fun `Given the analysis completed, When refresh is called, Then the report is kept on the phone and deleted on the server`() =
        runTest {
            storage.saveRecord(pendingRecord())
            repository.status = statusResponse(AuditStatus.COMPLETED, reportUrl = "https://s3/report")

            val record = interactor.refresh("analysis-1").getOrThrow()

            assertTrue(record.hasReport)
            assertEquals(AuditStatus.COMPLETED, record.status)
            assertEquals(REPORT_JSON, storage.getReport("analysis-1"))
            assertEquals(listOf("analysis-1"), repository.deleted)
            assertEquals(1, interactor.getReport("analysis-1").getOrThrow().findings.size)
        }

    @Test
    fun `Given the report is already on the phone, When refresh is called, Then the API is not called`() = runTest {
        storage.saveRecord(pendingRecord().copy(status = AuditStatus.COMPLETED, hasReport = true))

        interactor.refresh("analysis-1").getOrThrow()

        assertEquals(0, repository.statusCalls)
    }

    @Test
    fun `Given completed without report URL, When refresh is called, Then it keeps waiting`() = runTest {
        storage.saveRecord(pendingRecord())
        repository.status = statusResponse(AuditStatus.COMPLETED, reportUrl = null)

        val record = interactor.refresh("analysis-1").getOrThrow()

        assertEquals(AuditStatus.PROCESSING, record.status)
        assertFalse(record.hasReport)
    }

    @Test
    fun `Given the server no longer has the analysis, When refresh is called, Then it is marked as expired`() = runTest {
        storage.saveRecord(pendingRecord())
        repository.statusError = AuditApiException.NotFound()

        val record = interactor.refresh("analysis-1").getOrThrow()

        assertEquals(AuditStatus.EXPIRED, record.status)
    }
    //endregion

    //region documentForSigning
    @Test
    fun `Given the document changed after the audit, When documentForSigning is called, Then signing is refused`() = runTest {
        storage.saveRecord(pendingRecord().copy(documentSha256 = "other-hash"))

        val result = interactor.documentForSigning("analysis-1")

        assertEquals(
            "text-${R.string.audit_error_document_changed}",
            interactor.errorMessage(result.exceptionOrNull()!!),
        )
    }
    //endregion

    private fun pendingRecord() = AuditRecord(
        id = "analysis-1", fileName = "contrato.pdf", country = "CO", createdAtEpochMillis = nowMillis,
        documentUri = "content://test/contrato.pdf", documentSha256 = "hash",
        status = AuditStatus.PENDING, stage = "RECEIVED",
    )

    private fun statusResponse(
        status: String,
        stage: String? = null,
        clauseCount: Int? = null,
        reportUrl: String? = null,
    ) = AuditStatusResponse(
        id = "analysis-1", status = status, stage = stage, country = "CO", createdAt = "2026-11-09T10:00:00Z",
        clauseCount = clauseCount, reportUrl = reportUrl,
    )

    private class FakeDocumentReader(private val uri: Uri) : DocumentReader {
        var mimeType = "application/pdf"
        var accessKept = false
        override suspend fun read(uri: Uri, maxBytes: Int) = SelectedDocument("contrato.pdf", mimeType, PDF_CONTENT)
        override fun keepAccess(uri: Uri) {
            accessKept = true
        }

        override fun parse(uriString: String): Uri = uri
    }

    private class FakeAuditRepository : AuditRepository {
        var sessionsCreated = 0
        var createCalls = 0
        var statusCalls = 0
        var reportDownloads = 0
        var lastToken: String? = null
        var lastCreateRequest: CreateAuditRequest? = null
        var uploaded = ByteArray(0)
        val unauthorizedTokens = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        var createError: Throwable? = null
        var status: AuditStatusResponse? = null
        var statusError: Throwable? = null

        override suspend fun createSession(): Result<AuditSessionResponse> {
            sessionsCreated++
            return Result.success(AuditSessionResponse("token-$sessionsCreated", "2026-11-10T10:00:00Z"))
        }

        override suspend fun createAnalysis(token: String, request: CreateAuditRequest): Result<CreateAuditResponse> {
            createCalls++
            lastToken = token
            lastCreateRequest = request
            if (token in unauthorizedTokens) return Result.failure(AuditApiException.Unauthorized())
            createError?.let { return Result.failure(it) }
            return Result.success(
                CreateAuditResponse(
                    id = "analysis-1",
                    status = AuditStatus.PENDING,
                    upload = AuditUpload("PUT", "https://s3/upload", emptyMap(), 300),
                )
            )
        }

        override suspend fun uploadDocument(upload: AuditUpload, content: ByteArray): Result<Unit> {
            uploaded = content
            return Result.success(Unit)
        }

        override suspend fun getAnalysis(token: String, analysisId: String): Result<AuditStatusResponse> {
            statusCalls++
            statusError?.let { return Result.failure(it) }
            return Result.success(status!!)
        }

        override suspend fun deleteAnalysis(token: String, analysisId: String): Result<Unit> {
            deleted += analysisId
            return Result.success(Unit)
        }

        override suspend fun downloadReport(reportUrl: String): Result<String> {
            reportDownloads++
            return Result.success(REPORT_JSON)
        }
    }

    private class FakeAuditStorage : AuditStorageController {
        private var session: AuditSession? = null
        private val records = mutableListOf<AuditRecord>()
        private val reports = mutableMapOf<String, String>()

        override suspend fun getSession() = session
        override suspend fun setSession(session: AuditSession) {
            this.session = session
        }

        override suspend fun clearSession() {
            session = null
        }

        override suspend fun getRecords() = records.sortedByDescending { it.createdAtEpochMillis }
        override suspend fun getRecord(id: String) = records.firstOrNull { it.id == id }
        override suspend fun saveRecord(record: AuditRecord) {
            records.removeAll { it.id == record.id }
            records += record
        }

        override suspend fun deleteRecord(id: String) {
            records.removeAll { it.id == id }
            reports.remove(id)
        }

        override suspend fun saveReport(id: String, reportJson: String) {
            reports[id] = reportJson
        }

        override suspend fun getReport(id: String) = reports[id]
    }

    private companion object {
        val PDF_CONTENT = "%PDF-1.7 test".toByteArray()
        const val REPORT_JSON = """{"country":"CO","coverage":"full","summary":{"high":1},
            "findings":[{"clause_index":9,"clause_heading":"OCTAVA","risk":"high","citations":[],"verified":true}]}"""
    }
}

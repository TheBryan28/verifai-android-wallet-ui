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

package eu.europa.ec.networklogic.repository

import eu.europa.ec.businesslogic.config.ConfigLogic
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AuditSessionResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String,
)

@Serializable
data class CreateAuditRequest(
    val country: String,
    val sha256: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("content_type") val contentType: String,
)

@Serializable
data class AuditUpload(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
    @SerialName("expires_in_seconds") val expiresInSeconds: Int,
)

@Serializable
data class CreateAuditResponse(
    val id: String,
    val status: String,
    val upload: AuditUpload,
)

@Serializable
data class AuditStatusResponse(
    val id: String,
    val status: String,
    val stage: String? = null,
    val country: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("error_message") val errorMessage: String? = null,
    @SerialName("report_url") val reportUrl: String? = null,
    val pages: Int? = null,
    @SerialName("clause_count") val clauseCount: Int? = null,
    @SerialName("legal_sources") val legalSources: Int? = null,
)

/** Errors of the audit API, so callers can react to each case without parsing messages. */
sealed class AuditApiException(message: String) : Exception(message) {
    /** 401: the session token is missing, invalid or expired. */
    class Unauthorized : AuditApiException("Unauthorized")

    /** 429: `code` is DAILY_LIMIT_REACHED or SESSION_LIMIT_REACHED. */
    class LimitReached(val code: String, message: String) : AuditApiException(message)

    /** 404: the analysis expired (30 days) or was deleted. */
    class NotFound : AuditApiException("Analysis not found")

    class RequestFailed(val status: Int, message: String) : AuditApiException(message)
}

/**
 * Client of the VerifAI audit API (`verifai-api` behind API Gateway) and of the S3
 * presigned URLs it returns. It must use an HTTP client without request logging.
 */
interface AuditRepository {
    suspend fun createSession(): Result<AuditSessionResponse>
    suspend fun createAnalysis(token: String, request: CreateAuditRequest): Result<CreateAuditResponse>

    /** PUT to S3 with exactly the headers of the presigned URL and no Authorization header. */
    suspend fun uploadDocument(upload: AuditUpload, content: ByteArray): Result<Unit>
    suspend fun getAnalysis(token: String, analysisId: String): Result<AuditStatusResponse>
    suspend fun deleteAnalysis(token: String, analysisId: String): Result<Unit>

    /** Downloads the report from its presigned URL (valid for 5 minutes). */
    suspend fun downloadReport(reportUrl: String): Result<String>
}

class AuditRepositoryImpl(
    private val httpClient: HttpClient,
    private val configLogic: ConfigLogic,
    private val json: Json,
) : AuditRepository {

    private companion object {
        const val APP_KEY_HEADER = "x-verifai-app-key"
        const val CHECKSUM_HEADER = "x-amz-checksum-sha256"
    }

    private val baseUrl get() = configLogic.auditApiBaseUrl.trimEnd('/')

    override suspend fun createSession(): Result<AuditSessionResponse> = runCatching {
        httpClient.post("$baseUrl/sessions") {
            header(APP_KEY_HEADER, configLogic.auditAppKey)
        }.decode()
    }

    override suspend fun createAnalysis(
        token: String,
        request: CreateAuditRequest,
    ): Result<CreateAuditResponse> = runCatching {
        httpClient.post("$baseUrl/analyses") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(request))
        }.decode()
    }

    override suspend fun uploadDocument(upload: AuditUpload, content: ByteArray): Result<Unit> =
        runCatching {
            val headers = upload.headers.mapKeys { it.key.lowercase() }
            httpClient.put(upload.url) {
                headers[HttpHeaders.ContentType.lowercase()]?.let { contentType(ContentType.parse(it)) }
                headers[CHECKSUM_HEADER]?.let { header(CHECKSUM_HEADER, it) }
                setBody(content)
            }.ensureSuccess()
        }

    override suspend fun getAnalysis(token: String, analysisId: String): Result<AuditStatusResponse> =
        runCatching {
            httpClient.get("$baseUrl/analyses/$analysisId") { bearerAuth(token) }.decode()
        }

    override suspend fun deleteAnalysis(token: String, analysisId: String): Result<Unit> =
        runCatching {
            httpClient.delete("$baseUrl/analyses/$analysisId") { bearerAuth(token) }.ensureSuccess()
        }

    override suspend fun downloadReport(reportUrl: String): Result<String> = runCatching {
        httpClient.get(reportUrl).also { it.ensureSuccess() }.bodyAsText()
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T {
        ensureSuccess()
        return json.decodeFromString<T>(bodyAsText())
    }

    private suspend fun HttpResponse.ensureSuccess() {
        if (status.isSuccess()) return
        val body = runCatching { bodyAsText() }.getOrDefault("")
        val fields = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val error = fields?.get("error")?.jsonPrimitive?.content
            ?: fields?.get("message")?.jsonPrimitive?.content
            ?: status.description
        throw when (status) {
            HttpStatusCode.Unauthorized -> AuditApiException.Unauthorized()
            HttpStatusCode.NotFound -> AuditApiException.NotFound()
            HttpStatusCode.TooManyRequests -> AuditApiException.LimitReached(
                code = fields?.get("code")?.jsonPrimitive?.content.orEmpty(),
                message = error,
            )

            else -> AuditApiException.RequestFailed(status.value, error)
        }
    }
}

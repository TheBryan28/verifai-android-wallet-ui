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

package eu.europa.ec.dashboardfeature.ui.audit.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The report written by the `analyze` Lambda (reports/{id}/report.json). */
@Serializable
data class AuditReport(
    val country: String,
    val coverage: String = "full",
    @SerialName("corpus_version") val corpusVersion: String = "",
    @SerialName("generated_at") val generatedAt: String = "",
    val summary: Map<String, Int> = emptyMap(),
    val findings: List<AuditFinding> = emptyList(),
) {
    val isPartialCoverage: Boolean get() = coverage == "partial_eu_only"
}

@Serializable
data class AuditFinding(
    @SerialName("clause_index") val clauseIndex: Int,
    @SerialName("clause_heading") val clauseHeading: String = "",
    val page: Int? = null,
    val risk: String = AuditRisk.NONE.code,
    val category: String = "",
    val explanation: String = "",
    val recommendation: String = "",
    val citations: List<AuditCitation> = emptyList(),
    val verified: Boolean = false,
    val evaluated: Boolean = true,
) {
    val riskLevel: AuditRisk get() = AuditRisk.fromCode(risk)
}

@Serializable
data class AuditCitation(
    @SerialName("source_id") val sourceId: String,
    val norm: String? = null,
    val article: String? = null,
    val url: String? = null,
    val layer: String? = null,
)

/** Risk levels, from most to least severe. */
enum class AuditRisk(val code: String) {
    HIGH("high"), MEDIUM("medium"), LOW("low"), NONE("none");

    companion object {
        fun fromCode(code: String): AuditRisk = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** Statuses of an analysis, as returned by the audit API plus EXPIRED (404 on the server). */
object AuditStatus {
    const val PENDING = "PENDING"
    const val PROCESSING = "PROCESSING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val EXPIRED = "EXPIRED"

    fun isFinal(status: String) = status == COMPLETED || status == FAILED || status == EXPIRED
}

/** Pipeline stages reported by the backend (lambdas/shared/progress.py), in order. */
enum class AuditStage(val code: String) {
    RECEIVED("RECEIVED"),
    EXTRACTING_TEXT("EXTRACTING_TEXT"),
    PROTECTING_PERSONAL_DATA("PROTECTING_PERSONAL_DATA"),
    IDENTIFYING_CLAUSES("IDENTIFYING_CLAUSES"),
    SEARCHING_LAW("SEARCHING_LAW"),
    ANALYZING_WITH_AI("ANALYZING_WITH_AI"),
    PREPARING_REPORT("PREPARING_REPORT");

    companion object {
        /** WAITING_FOR_UPLOAD and unknown codes count as the first step. */
        fun fromCode(code: String?): AuditStage = entries.firstOrNull { it.code == code } ?: RECEIVED
    }
}

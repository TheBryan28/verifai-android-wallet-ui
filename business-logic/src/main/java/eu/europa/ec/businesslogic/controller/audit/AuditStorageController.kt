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

package eu.europa.ec.businesslogic.controller.audit

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesFileSerializer
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import eu.europa.ec.resourceslogic.provider.ResourceProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Session issued by the audit API: a bearer token valid for one day. */
@Serializable
data class AuditSession(
    val token: String,
    val expiresAtEpochSeconds: Long,
)

/** An audit the user started from this phone. The phone is the only place that keeps this list. */
@Serializable
data class AuditRecord(
    val id: String,
    val fileName: String,
    val country: String,
    val createdAtEpochMillis: Long,
    val documentUri: String,
    val documentSha256: String,
    val status: String,
    val stage: String,
    val errorMessage: String? = null,
    val pages: Int? = null,
    val clauseCount: Int? = null,
    val legalSources: Int? = null,
    val hasReport: Boolean = false,
)

/**
 * Encrypted storage for the contract audit: the audit session, the list of audits and the
 * downloaded reports. It uses its own DataStore file and keyset, separate from the wallet
 * preferences, so large reports never slow down the rest of the app.
 */
interface AuditStorageController {
    suspend fun getSession(): AuditSession?
    suspend fun setSession(session: AuditSession)
    suspend fun clearSession()

    /** Newest first. */
    suspend fun getRecords(): List<AuditRecord>
    suspend fun getRecord(id: String): AuditRecord?

    /** Inserts or replaces the record; only the newest [MAX_RECORDS] are kept. */
    suspend fun saveRecord(record: AuditRecord)

    /** Deletes the record and its report. */
    suspend fun deleteRecord(id: String)

    suspend fun saveReport(id: String, reportJson: String)
    suspend fun getReport(id: String): String?

    companion object {
        const val MAX_RECORDS = 20
    }
}

class AuditStorageControllerImpl(
    private val resourceProvider: ResourceProvider,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : AuditStorageController {

    private companion object {
        const val DATASTORE_FILE = "verifai-audit.preferences_pb"
        const val KEYSET_PREFS_FILE = "verifai-audit-datastore-keyset"
        const val KEYSET_PREFS_KEY = "audit_datastore_keyset"
        const val MASTER_KEY_URI = "android-keystore://verifai-audit-datastore-master-key"
        val SESSION_KEY = stringPreferencesKey("session")
        val RECORDS_KEY = stringPreferencesKey("records")
        fun reportKey(id: String) = stringPreferencesKey("report_$id")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val dataStore: DataStore<Preferences> by lazy {
        val context = resourceProvider.provideContext().applicationContext
        DataStoreFactory.create(
            serializer = AeadSerializer(
                aead = provideAead(context),
                wrappedSerializer = PreferencesFileSerializer,
                associatedData = DATASTORE_FILE.toByteArray(Charsets.UTF_8)
            ),
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(DATASTORE_FILE) }
        )
    }

    override suspend fun getSession(): AuditSession? =
        read()[SESSION_KEY]?.let { runCatching { json.decodeFromString<AuditSession>(it) }.getOrNull() }

    override suspend fun setSession(session: AuditSession) {
        dataStore.edit { it[SESSION_KEY] = json.encodeToString(session) }
    }

    override suspend fun clearSession() {
        dataStore.edit { it.remove(SESSION_KEY) }
    }

    override suspend fun getRecords(): List<AuditRecord> = decodeRecords(read()[RECORDS_KEY])

    override suspend fun getRecord(id: String): AuditRecord? = getRecords().firstOrNull { it.id == id }

    override suspend fun saveRecord(record: AuditRecord) {
        dataStore.edit { prefs ->
            val others = decodeRecords(prefs[RECORDS_KEY]).filterNot { it.id == record.id }
            val records = (listOf(record) + others).sortedByDescending { it.createdAtEpochMillis }
            records.drop(AuditStorageController.MAX_RECORDS).forEach { prefs.remove(reportKey(it.id)) }
            prefs[RECORDS_KEY] = json.encodeToString(records.take(AuditStorageController.MAX_RECORDS))
        }
    }

    override suspend fun deleteRecord(id: String) {
        dataStore.edit { prefs ->
            val records = decodeRecords(prefs[RECORDS_KEY]).filterNot { it.id == id }
            prefs[RECORDS_KEY] = json.encodeToString(records)
            prefs.remove(reportKey(id))
        }
    }

    override suspend fun saveReport(id: String, reportJson: String) {
        dataStore.edit { it[reportKey(id)] = reportJson }
    }

    override suspend fun getReport(id: String): String? = read()[reportKey(id)]

    private fun decodeRecords(value: String?): List<AuditRecord> =
        value?.let { runCatching { json.decodeFromString<List<AuditRecord>>(it) }.getOrNull() }
            .orEmpty()

    private suspend fun read(): Preferences = dataStore.data.first()

    private fun provideAead(context: Context): Aead {
        AeadConfig.register()
        val keysetHandle = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_PREFS_KEY, KEYSET_PREFS_FILE)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
        return keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }
}

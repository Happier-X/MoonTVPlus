package com.moontvplus.phone.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.moontvplus.phone.core.model.AuthSession
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json

private val Context.sessionDataStore: DataStore<Preferences> by preferencesDataStore("session")

@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) {

    private val dataStore = context.sessionDataStore

    suspend fun read(): AuthSession? {
        val raw = dataStore.data.first()[KEY_SESSION] ?: return null
        return runCatching { json.decodeFromString<AuthSession>(raw) }.getOrNull()
    }

    suspend fun write(session: AuthSession) {
        val raw = json.encodeToString(AuthSession.serializer(), session)
        dataStore.edit { it[KEY_SESSION] = raw }
    }

    suspend fun clear() {
        dataStore.edit { it.remove(KEY_SESSION) }
    }

    private companion object {
        val KEY_SESSION = stringPreferencesKey("auth_session")
    }
}

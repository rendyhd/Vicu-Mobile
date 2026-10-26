package com.rendyhd.vicu.ai.ask

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.rendyhd.vicu.ai.AiContract
import com.rendyhd.vicu.ai.provider.ChatMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** How a proposal card ended, kept so an old conversation still shows it. */
@Serializable
data class ProposalOutcome(val status: String, val count: Int, val summary: String)

/** An Ask conversation, kept on the device only (`docs/ai-assistant-v1.md`, section 5.1). */
@Serializable
data class Conversation(
    val id: String,
    val title: String = "",
    val updatedAt: Long = 0,
    val messages: List<ChatMessage> = emptyList(),
    /** Ids of tasks a tool returned in this conversation: only these render as rows. */
    val knownTaskIds: Set<Long> = emptySet(),
    /** When the last user message was sent (epoch ms) and on which local day, for the context refresh. */
    val lastSentAt: Long = 0,
    val lastDay: String? = null,
    /** Proposal cards by tool call id. */
    val outcomes: Map<String, ProposalOutcome> = emptyMap(),
    /** Spoken (Talk) rather than typed. */
    val talk: Boolean = false,
)

/** The last conversations (at most `conversations_kept`), newest first. Never synced, never backed up. */
class ConversationStore(private val dataStore: DataStore<Preferences>) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val serializer = ListSerializer(Conversation.serializer())

    val conversations: Flow<List<Conversation>> = dataStore.data.map { prefs ->
        prefs[KEY]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
    }

    suspend fun all(): List<Conversation> = conversations.first()

    suspend fun save(conversation: Conversation) {
        val trimmed = conversation.copy(messages = trim(conversation.messages, AiContract.limits.conversationMaxMessages))
        dataStore.edit { prefs ->
            val list = prefs[KEY]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
            val next = (listOf(trimmed) + list.filter { it.id != trimmed.id })
                .sortedByDescending { it.updatedAt }
                .take(AiContract.limits.conversationsKept)
            prefs[KEY] = json.encodeToString(serializer, next)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val list = prefs[KEY]?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()
            prefs[KEY] = json.encodeToString(serializer, list.filter { it.id != id })
        }
    }

    suspend fun clear() {
        dataStore.edit { it.clear() }
    }

    private fun <T> List<T>?.orEmpty(): List<T> = this ?: emptyList()

    companion object {
        private val KEY = stringPreferencesKey("conversations")

        /**
         * The last [max] messages, starting at a user message, so a tool result never comes
         * without its call and the turns still alternate.
         */
        fun trim(messages: List<ChatMessage>, max: Int): List<ChatMessage> {
            if (messages.size <= max) return messages
            val tail = messages.takeLast(max)
            val start = tail.indexOfFirst { it is ChatMessage.User }
            return if (start < 0) emptyList() else tail.drop(start)
        }
    }
}

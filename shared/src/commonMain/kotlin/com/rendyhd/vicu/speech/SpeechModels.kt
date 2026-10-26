package com.rendyhd.vicu.speech

import com.rendyhd.vicu.ai.AiContract
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** A speech model of `speech.models` (`docs/ai-assistant-v1.md`, section 3.5). */
data class SpeechModelSpec(
    val id: String,
    /** `whisper` or `parakeet`. */
    val engine: String,
    val label: String,
    val repo: String,
    val commit: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val languageCount: Int,
    /** Null means every language Whisper knows. */
    val languageCodes: List<String>?,
    val license: String,
    val attribution: String?,
    val defaultFor: List<String>,
) {
    val url: String get() = "https://huggingface.co/$repo/resolve/$commit/$file"
    val engineCode: Int get() = if (engine == "parakeet") SpeechEngineNative.ENGINE_PARAKEET else SpeechEngineNative.ENGINE_WHISPER
    val takesPrompt: Boolean get() = engine == "whisper"

    companion object {
        val catalog: List<SpeechModelSpec> by lazy {
            AiContract.speech.getValue("models").jsonArray.map { element ->
                val o = element.jsonObject
                SpeechModelSpec(
                    id = o.getValue("id").jsonPrimitive.content,
                    engine = o.getValue("engine").jsonPrimitive.content,
                    label = o.getValue("label").jsonPrimitive.content,
                    repo = o.getValue("repo").jsonPrimitive.content,
                    commit = o.getValue("commit").jsonPrimitive.content,
                    file = o.getValue("file").jsonPrimitive.content,
                    size = o.getValue("size").jsonPrimitive.long,
                    sha256 = o.getValue("sha256").jsonPrimitive.content,
                    languageCount = o.getValue("language_count").jsonPrimitive.int,
                    languageCodes = (o["language_codes"] as? JsonArray)?.map { it.jsonPrimitive.content },
                    license = o.getValue("license").jsonPrimitive.content,
                    attribution = (o["attribution"] as? JsonPrimitive)?.contentOrNull,
                    defaultFor = o.getValue("default_for").jsonArray.map { it.jsonPrimitive.content },
                )
            }
        }

        fun byId(id: String?): SpeechModelSpec? = catalog.firstOrNull { it.id == id }

        /** The starting suggestion for a phone: `phone`, or `phone_low_memory` under 6 GB. */
        fun suggestedForPhone(totalMemoryBytes: Long): SpeechModelSpec {
            val kind = if (totalMemoryBytes in 1 until LOW_MEMORY_BYTES) "phone_low_memory" else "phone"
            return catalog.firstOrNull { kind in it.defaultFor } ?: catalog.first()
        }

        private const val LOW_MEMORY_BYTES = 6L * 1024 * 1024 * 1024
    }
}

sealed interface ModelFileState {
    data object Absent : ModelFileState
    data class Downloading(val bytes: Long, val total: Long) : ModelFileState {
        val fraction: Float get() = if (total <= 0) 0f else (bytes.toFloat() / total).coerceIn(0f, 1f)
    }
    data object Checking : ModelFileState
    data object Installed : ModelFileState
    data class Failed(val message: String) : ModelFileState
}

/**
 * Downloads, checks and deletes speech models (section 3.5): from the pinned Hugging Face commit,
 * resumable with HTTP `Range`, Wi-Fi only unless allowed, checked against the SHA-256 before use
 * (a file that fails is deleted), kept in [SpeechStorage.modelsDirectory] outside backups.
 */
class SpeechModelManager(
    engine: HttpClientEngine,
    private val storage: SpeechStorage,
    private val scope: CoroutineScope,
    private val io: kotlinx.coroutines.CoroutineDispatcher,
) {
    private val http = HttpClient(engine) {
        install(HttpTimeout)
        expectSuccess = false
        followRedirects = true
    }

    private val _states = MutableStateFlow<Map<String, ModelFileState>>(emptyMap())
    val states: StateFlow<Map<String, ModelFileState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()

    fun pathOf(spec: SpeechModelSpec): String = storage.modelsDirectory().trimEnd('/') + "/" + spec.file

    private fun partOf(spec: SpeechModelSpec): String = pathOf(spec) + ".part"

    fun isInstalled(spec: SpeechModelSpec): Boolean = runCatching {
        SystemFileSystem.metadataOrNull(Path(pathOf(spec)))?.size == spec.size
    }.getOrDefault(false)

    /** Reads the files on disk into [states]. */
    fun refresh() {
        _states.update { current ->
            SpeechModelSpec.catalog.associate { spec ->
                val now = current[spec.id]
                spec.id to when {
                    now is ModelFileState.Downloading || now is ModelFileState.Checking -> now
                    isInstalled(spec) -> ModelFileState.Installed
                    now is ModelFileState.Failed -> now
                    else -> ModelFileState.Absent
                }
            }
        }
    }

    /** False when the connection is metered and mobile data is not allowed. */
    fun canDownloadNow(allowMobileData: Boolean): Boolean = allowMobileData || storage.isUnmeteredNetwork()

    fun download(spec: SpeechModelSpec) {
        if (jobs[spec.id]?.isActive == true) return
        jobs[spec.id] = scope.launch {
            try {
                withContext(io) { fetch(spec) }
            } catch (e: CancellationException) {
                set(spec, if (isInstalled(spec)) ModelFileState.Installed else ModelFileState.Absent)
                throw e
            } catch (e: Exception) {
                set(spec, ModelFileState.Failed(e.message ?: "The download failed."))
            }
        }
    }

    fun cancel(spec: SpeechModelSpec) {
        jobs.remove(spec.id)?.cancel()
    }

    suspend fun delete(spec: SpeechModelSpec) {
        cancel(spec)
        withContext(io) {
            runCatching { SystemFileSystem.delete(Path(pathOf(spec)), mustExist = false) }
            runCatching { SystemFileSystem.delete(Path(partOf(spec)), mustExist = false) }
        }
        set(spec, ModelFileState.Absent)
    }

    private suspend fun fetch(spec: SpeechModelSpec) {
        val dir = Path(storage.modelsDirectory())
        SystemFileSystem.createDirectories(dir)
        if (storage.freeBytes() in 0 until spec.size + FREE_MARGIN_BYTES) throw IllegalStateException("Not enough free space for ${spec.label}.")
        val part = Path(partOf(spec))
        var have = SystemFileSystem.metadataOrNull(part)?.size ?: 0L
        if (have > spec.size) {
            SystemFileSystem.delete(part)
            have = 0L
        }
        set(spec, ModelFileState.Downloading(have, spec.size))
        if (have < spec.size) {
            http.prepareGet(spec.url) {
                if (have > 0) header("Range", "bytes=$have-")
                timeout { connectTimeoutMillis = 15_000; socketTimeoutMillis = 60_000 }
            }.execute { response ->
                val status = response.status.value
                val append = status == 206
                if (status != 200 && status != 206) throw IllegalStateException("The download failed (error $status).")
                if (!append) have = 0L
                val sink = SystemFileSystem.sink(part, append = append).buffered()
                try {
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(BUFFER_BYTES)
                    var lastReport = have
                    while (true) {
                        val n = channel.readAvailable(buffer, 0, buffer.size)
                        if (n < 0) break
                        if (n == 0) {
                            if (channel.isClosedForRead) break
                            continue
                        }
                        sink.write(buffer, 0, n)
                        have += n
                        if (have - lastReport >= REPORT_BYTES) {
                            lastReport = have
                            set(spec, ModelFileState.Downloading(have, spec.size))
                        }
                    }
                } finally {
                    sink.close()
                }
            }
        }
        set(spec, ModelFileState.Checking)
        val digest = Sha256()
        SystemFileSystem.source(part).buffered().use { source ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val n = source.readAtMostTo(buffer, 0, buffer.size)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        if (digest.hexDigest() != spec.sha256.lowercase()) {
            SystemFileSystem.delete(part, mustExist = false)
            throw IllegalStateException("${spec.label} did not pass its check and was deleted. Try again.")
        }
        SystemFileSystem.atomicMove(part, Path(pathOf(spec)))
        set(spec, ModelFileState.Installed)
    }

    private fun set(spec: SpeechModelSpec, state: ModelFileState) {
        _states.update { it + (spec.id to state) }
    }

    private companion object {
        const val BUFFER_BYTES = 256 * 1024
        const val REPORT_BYTES = 2L * 1024 * 1024
        const val FREE_MARGIN_BYTES = 200L * 1024 * 1024
    }
}

package com.rendyhd.vicu.data.repository

import com.rendyhd.vicu.data.local.dao.AttachmentDao
import com.rendyhd.vicu.data.local.entity.AttachmentEntity
import com.rendyhd.vicu.data.mapper.AttachmentMapper
import com.rendyhd.vicu.data.remote.api.AttachmentDto
import com.rendyhd.vicu.data.remote.api.VikunjaApiService
import com.rendyhd.vicu.util.Logger
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException

/** The server took the request but refused the file itself (its answer's `errors`). */
class AttachmentRefusedException(message: String) : IllegalStateException(message)

/**
 * Uploads a file to [taskId] and stores what it made, for the editor and the background queue alike.
 *
 * The server's answer names the attachment it created, so that row is stored straight away and the
 * task's list shows it at once. The list is then read to catch up with other changes, but only as an
 * extra: a read that fails, or answers without the new file, keeps the stored row and is not a
 * failure of the upload (which would show the old list, or send the file a second time).
 *
 * An answer without the attachment (it could not be read) falls back to the task's list: the file
 * with the same name and the highest id. Returns null when that finds nothing.
 * A file the server refused in its answer throws [AttachmentRefusedException].
 */
internal suspend fun uploadAndStore(
    api: VikunjaApiService,
    attachmentDao: AttachmentDao,
    mapper: AttachmentMapper,
    taskId: Long,
    fileName: String,
    size: Long?,
    content: () -> ByteReadChannel,
): AttachmentEntity? {
    val answer = api.uploadAttachment(taskId, fileName, size, content)
    val made = answer.success.orEmpty().map { with(mapper) { it.toEntity(taskId) } }
    if (made.isNotEmpty()) {
        attachmentDao.upsertAll(made)
        val list = try {
            api.getAttachments(taskId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(UPLOADS_TAG, "Uploaded to task $taskId; reading its list failed (${e.message})")
            null
        }
        if (list != null) {
            val listed = list.map { with(mapper) { it.toEntity(taskId) } }
            val listedIds = listed.map { it.id }.toSet()
            attachmentDao.replaceForTask(taskId, listed + made.filter { it.id !in listedIds })
        }
        return made.maxBy { it.id }
    }
    answer.errors.orEmpty().firstOrNull()?.let { refused ->
        throw AttachmentRefusedException(refused.message.ifBlank { "The server did not accept \"$fileName\"" })
    }
    val list = api.getAttachments(taskId)
    attachmentDao.replaceForTask(taskId, list.map { with(mapper) { it.toEntity(taskId) } })
    return newestNamed(list, fileName)?.let { with(mapper) { it.toEntity(taskId) } }
}

private fun newestNamed(list: List<AttachmentDto>, fileName: String): AttachmentDto? =
    list.filter { it.file?.name == fileName }.maxByOrNull { it.id }

private const val UPLOADS_TAG = "AttachmentUploads"

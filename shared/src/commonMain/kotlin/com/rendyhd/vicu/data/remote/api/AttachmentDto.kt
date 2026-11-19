package com.rendyhd.vicu.data.remote.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AttachmentDto(
    val id: Long = 0,
    @SerialName("task_id") val taskId: Long = 0,
    val file: FileDto? = null,
    val created: String = "",
    @SerialName("created_by") val createdBy: UserDto? = null,
)

/**
 * The answer to an upload (`AttachmentUploadResult`): the attachments it created, and the files
 * the server refused, which do not fail the request (it is still a 201).
 */
@Serializable
data class AttachmentUploadResultDto(
    val success: List<AttachmentDto>? = null,
    val errors: List<AttachmentUploadErrorDto>? = null,
)

@Serializable
data class AttachmentUploadErrorDto(
    val code: Long? = null,
    val message: String = "",
)

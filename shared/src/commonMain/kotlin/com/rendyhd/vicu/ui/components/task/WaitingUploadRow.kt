package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rendyhd.vicu.domain.model.UploadingFile
import com.rendyhd.vicu.domain.model.WaitingUpload
import com.rendyhd.vicu.util.WaitingUploadText

/**
 * A file attached while the server could not be reached, in the task's attachments: the copy's
 * thumbnail (dimmed) or a file icon with a clock, "Waiting to upload", and Remove. Refused by the
 * server, it says why in the error colour and offers Retry where retrying can help.
 */
@Composable
fun WaitingUploadRow(
    upload: WaitingUpload,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .then(if (upload.failed) Modifier.background(colors.errorContainer.copy(alpha = 0.45f)) else Modifier)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UploadTile(
            imageModel = upload.localPath?.takeIf { upload.isImage },
            imageKey = upload.id,
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = WaitingUploadText.description(upload) },
        ) {
            Icon(
                if (upload.failed) Icons.Outlined.ErrorOutline else Icons.Outlined.Schedule,
                contentDescription = null,
                tint = if (upload.failed) colors.error else colors.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = upload.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = WaitingUploadText.status(upload),
                style = MaterialTheme.typography.bodySmall,
                color = if (upload.failed) colors.error else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (upload.failed && upload.canRetry) {
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Close, contentDescription = "Remove ${upload.fileName}", modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * A file being sent to the server right now, at the end of the task's attachments: the waiting
 * row's shape with a spinner in the badge and "Uploading · 2.4 MB". It has no buttons; once the
 * attachment it makes is listed, that row takes its place.
 */
@Composable
fun UploadingFileRow(
    file: UploadingFile,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = WaitingUploadText.uploadingDescription(file.fileName)
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UploadTile(imageModel = file.previewUri?.takeIf { file.isImage }, imageKey = file.key) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = colors.primary)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = WaitingUploadText.uploading(file.fileSize),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The 48 dp tile of a file that is not on the server yet: the picture dimmed when [imageModel]
 * draws, else a file icon, with [badge] in a small circle at the bottom corner.
 */
@Composable
private fun UploadTile(
    imageModel: String?,
    imageKey: Long,
    modifier: Modifier = Modifier,
    badge: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var thumbnailFailed by remember(imageKey) { mutableStateOf(false) }
    Box(modifier = modifier.size(48.dp)) {
        if (imageModel != null && !thumbnailFailed) {
            AsyncImage(
                model = imageModel,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onError = { thumbnailFailed = true },
                modifier = Modifier
                    .size(48.dp)
                    .alpha(0.55f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainer),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.AttachFile, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 4.dp, y = 4.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            badge()
        }
    }
}

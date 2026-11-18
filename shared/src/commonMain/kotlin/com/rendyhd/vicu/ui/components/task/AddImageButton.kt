package com.rendyhd.vicu.ui.components.task

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * "Add image" under the notes. On a phone with a camera ([onTakePhoto] set) it opens a menu with
 * Take photo and Choose photo; without one it opens the photo picker at once. [beforeOpen] runs
 * on the tap, before either.
 */
@Composable
internal fun AddImageButton(
    onChoosePhoto: () -> Unit,
    onTakePhoto: (() -> Unit)?,
    beforeOpen: () -> Unit = {},
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = {
                beforeOpen()
                if (onTakePhoto == null) onChoosePhoto() else menuOpen = true
            },
        ) {
            Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Add image")
        }
        if (onTakePhoto != null) {
            ImageSourceMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                onTakePhoto = onTakePhoto,
                onChoosePhoto = onChoosePhoto,
            )
        }
    }
}

/** Take photo, then Choose photo: the two ways to add an image. Shared with the comments' paperclip. */
@Composable
internal fun ImageSourceMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onChoosePhoto: () -> Unit,
    more: @Composable () -> Unit = {},
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text("Take photo") },
            leadingIcon = { Icon(Icons.Outlined.PhotoCamera, contentDescription = null) },
            onClick = {
                onDismiss()
                onTakePhoto()
            },
        )
        DropdownMenuItem(
            text = { Text("Choose photo") },
            leadingIcon = { Icon(Icons.Outlined.Image, contentDescription = null) },
            onClick = {
                onDismiss()
                onChoosePhoto()
            },
        )
        more()
    }
}

package com.rendyhd.vicu.ui.navigation

/**
 * A link into the app, for platforms where widgets, notifications, controls and shortcuts open the
 * app by URL (iOS): `vicu://task/42`, `vicu://view/TODAY`, `vicu://view/PROJECT/7`,
 * `vicu://view/CUSTOM_LIST/<id>`, `vicu://add`, `vicu://add?project=7`, `vicu://dictate`. The scheme is the one the
 * build registers ("vicu", or "vicu-debug" for the debug build) and is not checked when parsing.
 * View names are the frozen [ViewTarget.typeName]s.
 */
sealed interface AppLink {
    data class OpenTask(val taskId: Long) : AppLink
    data class OpenView(val target: ViewTarget) : AppLink
    data class AddTask(val projectId: Long? = null) : AppLink

    /** Dictation: smart dictation, listening, when it is set up; otherwise the voice card. */
    data object Dictate : AppLink

    /** This link as a URL with [scheme]. */
    fun toUrl(scheme: String): String = when (this) {
        is OpenTask -> "$scheme://task/$taskId"
        is OpenView -> buildString {
            append("$scheme://view/").append(target.typeName)
            if (target.idOrEmpty.isNotEmpty()) append('/').append(percentEncode(target.idOrEmpty))
        }
        is AddTask -> if (projectId == null) "$scheme://add" else "$scheme://add?project=$projectId"
        Dictate -> "$scheme://dictate"
    }

    companion object {
        /** The link [url] names, or null when it names nothing the app can open. */
        fun parse(url: String): AppLink? {
            val rest = url.substringAfter("://", missingDelimiterValue = "").ifEmpty { return null }
            val path = rest.substringBefore('?').trimEnd('/')
            val query = rest.substringAfter('?', missingDelimiterValue = "")
                .split('&')
                .filter { '=' in it }
                .associate { it.substringBefore('=') to percentDecode(it.substringAfter('=')) }
            val parts = path.split('/').map(::percentDecode)
            return when (parts.firstOrNull()) {
                "task" -> parts.getOrNull(1)?.toLongOrNull()?.takeIf { it != 0L }?.let { OpenTask(it) }
                "view" -> ViewTarget.parse(parts.getOrNull(1), parts.getOrNull(2))?.let { OpenView(it) }
                "add" -> AddTask(query["project"]?.toLongOrNull()?.takeIf { it != 0L })
                "dictate" -> Dictate
                else -> null
            }
        }

        private const val HEX = "0123456789ABCDEF"

        private fun percentEncode(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val b = byte.toInt() and 0xFF
                val c = b.toChar()
                if (b < 0x80 && (c.isLetterOrDigit() || c in "-._~")) append(c)
                else append('%').append(HEX[b shr 4]).append(HEX[b and 0x0F])
            }
        }

        private fun percentDecode(value: String): String {
            if ('%' !in value) return value
            val bytes = ArrayList<Byte>(value.length)
            var i = 0
            while (i < value.length) {
                val c = value[i]
                val hex = if (c == '%' && i + 2 <= value.lastIndex) value.substring(i + 1, i + 3).toIntOrNull(16) else null
                if (hex != null) {
                    bytes += hex.toByte()
                    i += 3
                } else {
                    c.toString().encodeToByteArray().forEach { bytes += it }
                    i++
                }
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}

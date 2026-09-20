package io.github.mangi.eta.ui.components

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val messageTimestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)

internal fun formatMessageTimestamp(timestamp: Long, zone: ZoneId): String =
    messageTimestampFormat.format(Instant.ofEpochMilli(timestamp).atZone(zone))

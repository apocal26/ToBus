package com.example.data.network

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class DiagCategory(
    val tag: String,
    val labelIt: String,
) {
    POLL("POLL", "Polling"),
    SOURCE("SRC", "Fonti"),
    MQTT("MQTT", "MQTT"),
    ROUTE("ROUTE", "Percorsi"),
    STOP("STOP", "Fermate"),
    ALERTS("ALERT", "Avvisi"),
    OUTAGE("OUTAGE", "Disservizi"),
    NET("NET", "Rete"),
    CACHE("CACHE", "Cache"),
    DEBUG("DEBUG", "Debug"),
}

enum class DiagSeverity(val symbol: String) {
    INFO("·"),
    OK("✓"),
    WARN("!"),
    ERROR("✕"),
}

data class DiagnosticLogEntry(
    val seq: Long,
    val timestampMillis: Long,
    val category: DiagCategory,
    val severity: DiagSeverity,
    val message: String,
    val detail: String? = null,
) {
    fun formatLine(timeFormatter: SimpleDateFormat): String {
        val stamp = timeFormatter.format(Date(timestampMillis))
        val suffix = detail?.takeIf { it.isNotBlank() }?.let { " | $it" }.orEmpty()
        return "[$stamp] ${severity.symbol} ${category.tag}: $message$suffix"
    }
}

/**
 * Lightweight, zero-disk-I/O, thread-safe in-memory ring buffer capturing structured technical
 * telemetry across data sources, MQTT handshakes/SUBACKs, route/stop ingestion, caches, and
 * GTT upstream outage evaluations so future issues can be diagnosed in seconds from the hidden
 * debug menu.
 */
object ToBusDiagnosticsLog {

    private const val MAX_ENTRIES = 220

    private val lock = Any()
    private val seqGen = AtomicLong(0L)
    private val buffer = ArrayDeque<DiagnosticLogEntry>(MAX_ENTRIES)

    fun record(
        category: DiagCategory,
        message: String,
        detail: String? = null,
        severity: DiagSeverity = DiagSeverity.INFO,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        val cleanMsg = message.trim()
        if (cleanMsg.isEmpty()) return
        val cleanDetail = detail?.trim()?.takeIf { it.isNotEmpty() }?.take(220)
        val entry = DiagnosticLogEntry(
            seq = seqGen.incrementAndGet(),
            timestampMillis = timestampMillis,
            category = category,
            severity = severity,
            message = cleanMsg.take(220),
            detail = cleanDetail,
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
        }
    }

    /**
     * Returns entries newest-first so the most recent diagnostic events are immediately visible.
     */
    fun snapshot(
        categories: Set<DiagCategory>? = null,
        maxEntries: Int = MAX_ENTRIES,
    ): List<DiagnosticLogEntry> {
        synchronized(lock) {
            if (buffer.isEmpty()) return emptyList()
            val out = ArrayList<DiagnosticLogEntry>(minOf(buffer.size, maxEntries))
            val iter = buffer.asReversed().iterator()
            while (iter.hasNext() && out.size < maxEntries) {
                val entry = iter.next()
                if (categories == null || entry.category in categories) {
                    out.add(entry)
                }
            }
            return out
        }
    }

    fun count(): Int = synchronized(lock) { buffer.size }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
    }

    fun formatReport(
        headerLines: List<String>,
        categories: Set<DiagCategory>? = null,
        maxEntries: Int = 90,
    ): String {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY)
        val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ITALY)
        val logs = snapshot(categories = categories, maxEntries = maxEntries).asReversed()
        return buildString {
            appendLine("=== ToBus Diagnostic Report (${dateFmt.format(Date())}) ===")
            headerLines.forEach { line ->
                appendLine(line)
            }
            appendLine("--- Event Log (${logs.size} eventi) ---")
            if (logs.isEmpty()) {
                appendLine("(nessun evento registrato)")
            } else {
                logs.forEach { entry ->
                    appendLine(entry.formatLine(fmt))
                }
            }
        }.trimEnd()
    }
}

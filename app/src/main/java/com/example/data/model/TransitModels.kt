package com.example.data.model

import androidx.compose.runtime.Immutable

@Immutable
data class GeoPoint(
    val lat: Double,
    val lon: Double,
)

enum class VehicleStopStatus(val labelIt: String) {
    INCOMING_AT("In arrivo alla fermata"),
    STOPPED_AT("Fermo alla fermata"),
    IN_TRANSIT_TO("In transito"),
    UNKNOWN("In servizio"),
}

enum class VehicleFreshness(val labelIt: String) {
    LIVE("Segnale live"),
    SLOW("Aggiornamento lento"),
    STALE("Segnale fermo / datato"),
}

@Immutable
data class StopTimePrediction(
    val stopId: String,
    val arrivalEpochSeconds: Long?,
    val departureEpochSeconds: Long?,
    val delaySeconds: Int?,
)

@Immutable
data class TripDelayInfo(
    val tripId: String?,
    val vehicleId: String?,
    val routeId: String?,
    val delaySeconds: Int?,
    val nextStopId: String?,
    val timestampSeconds: Long,
    val stopPredictions: List<StopTimePrediction> = emptyList(),
)

@Immutable
data class AlertTimeRange(
    val startEpochSeconds: Long?,
    val endEpochSeconds: Long?,
)

@Immutable
data class ServiceAlertInfo(
    val id: String,
    val headerText: String,
    val descriptionText: String,
    val affectedLines: Set<String>,
    val activePeriods: List<AlertTimeRange> = emptyList(),
) {
    /**
     * Keeps ONLY immediate, temporary, or upcoming GTT alerts (roadworks, temporary deviations,
     * matches, events, service boosts, recent changes) starting within [maxHorizonDays] days.
     * Filters out expired alerts and old permanent/standing notices ("sino a nuove comunicazioni",
     * "ripristino percorso", or alerts started weeks/months ago with a lazy 31/12 end date).
     */
    fun isCurrentOrUpcoming(
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        maxHorizonDays: Int = 7,
    ): Boolean {
        val daySec = 86_400L
        val maxStartSec = nowEpochSeconds + maxHorizonDays * daySec
        val maxRecentStartAgeSec = 14L * daySec
        val maxStandingNoticeStartAgeSec = 10L * daySec
        val maxTemporaryDurationSec = 35L * daySec

        val fullText = "$headerText $descriptionText"
        val lowerText = fullText.lowercase(java.util.Locale.ITALY)
        val isIndefiniteOrStandingNotice =
            lowerText.contains("nuove comunicazioni") ||
                lowerText.contains("ripristino percorso") ||
                lowerText.contains("ripristino capolinea") ||
                lowerText.contains("fino al termine") ||
                lowerText.contains("sino al termine") ||
                lowerText.contains("a tempo indeterminato") ||
                lowerText.contains("modifica definitiva")

        if (activePeriods.isNotEmpty()) {
            val matchesPeriod = activePeriods.any { period ->
                val start = period.startEpochSeconds?.takeIf { it > 0L }
                val end = period.endEpochSeconds?.takeIf { it > 0L }

                // 1. Reject already expired periods
                if (end != null && end < nowEpochSeconds) return@any false
                // 2. Reject future events starting more than maxHorizonDays from now
                if (start != null && start > maxStartSec) return@any false

                // 3. Reject old standing/long-term alerts that started long ago
                if (start != null && start < nowEpochSeconds) {
                    val ageSinceStart = nowEpochSeconds - start
                    if (isIndefiniteOrStandingNotice && ageSinceStart > maxStandingNoticeStartAgeSec) {
                        return@any false
                    }
                    if (end == null) {
                        // Open-ended alert: keep only if started very recently (<= 14 days)
                        if (ageSinceStart > maxRecentStartAgeSec) return@any false
                    } else {
                        val totalDuration = end - start
                        // Long-term or end-of-year placeholder (>35 days total): keep only if newly started (<= 10 days)
                        if (totalDuration > maxTemporaryDurationSec && ageSinceStart > maxStandingNoticeStartAgeSec) {
                            return@any false
                        }
                        // Even medium-term works: reject if started >14 days ago unless ending within 5 days
                        if (ageSinceStart > maxRecentStartAgeSec && (end - nowEpochSeconds) > 5L * daySec) {
                            return@any false
                        }
                    }
                }
                true
            }
            if (!matchesPeriod) return false
        }

        // 4. Secondary Italian text-date guard (handles numeric dd/MM/yyyy AND "10 luglio", "7 luglio", etc.)
        val numericDates = extractItalianDatesEpochSeconds(fullText)
        val monthDates = extractItalianMonthDatesEpochSeconds(fullText, nowEpochSeconds)
        val allTextDates = (numericDates + monthDates)

        if (allTextDates.isNotEmpty()) {
            val latestDateEndOfDay = allTextDates.max() + 86_399L
            val earliestDate = allTextDates.min()

            // Every mentioned date is strictly in the past (>1 day ago) and NOT an ongoing short event
            if (latestDateEndOfDay < nowEpochSeconds - daySec) {
                // If it's a "Da 10 luglio e sino a nuove comunicazioni" or >14 days old, drop it!
                if (isIndefiniteOrStandingNotice || latestDateEndOfDay < nowEpochSeconds - maxRecentStartAgeSec) {
                    return false
                }
                if (numericDates.isNotEmpty() && activePeriods.isEmpty()) {
                    return false
                }
            }
            // No active_period and earliest date is beyond our 7-day horizon
            if (activePeriods.isEmpty() && earliestDate > maxStartSec) {
                return false
            }
            // Indefinite notice with an old starting date (>10 days ago) and no recent/upcoming date
            val hasRecentOrUpcomingDate = allTextDates.any { d ->
                d >= nowEpochSeconds - maxStandingNoticeStartAgeSec && d <= maxStartSec
            }
            if (isIndefiniteOrStandingNotice && !hasRecentOrUpcomingDate) {
                return false
            }
        } else if (activePeriods.isEmpty()) {
            // No active_period and no parseable date: reject old past years or undated standing notices
            if (isIndefiniteOrStandingNotice) return false
            val calYear = java.util.Calendar.getInstance().apply {
                timeInMillis = nowEpochSeconds * 1000L
            }.get(java.util.Calendar.YEAR)
            val years = FOUR_DIGIT_YEAR_REGEX
                .findAll(fullText)
                .mapNotNull { it.groupValues[1].toIntOrNull() }
                .toList()
            if (years.isNotEmpty() && years.max() < calYear) return false
        }
        return true
    }

    fun isActiveNow(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Boolean {
        if (activePeriods.isEmpty()) return true
        return activePeriods.any { p ->
            val s = p.startEpochSeconds?.takeIf { it > 0L }
            val e = p.endEpochSeconds?.takeIf { it > 0L }
            (s == null || s <= nowEpochSeconds) && (e == null || e >= nowEpochSeconds)
        }
    }

    fun earliestRelevantStartSeconds(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): Long {
        if (isActiveNow(nowEpochSeconds)) {
            // Among active alerts, rank most recently started first (smaller negative value = more recent)
            val activeStart = activePeriods
                .mapNotNull { it.startEpochSeconds?.takeIf { s -> s in 1L..nowEpochSeconds } }
                .maxOrNull() ?: 0L
            return -activeStart
        }
        return activePeriods
            .mapNotNull { it.startEpochSeconds?.takeIf { s -> s > nowEpochSeconds } }
            .minOrNull() ?: Long.MAX_VALUE
    }

    fun validityLabel(nowEpochSeconds: Long = System.currentTimeMillis() / 1000L): String? {
        if (activePeriods.isEmpty()) return "Attivo ora"
        val fmt = java.text.SimpleDateFormat("dd/MM", java.util.Locale.ITALY)
        val activeNowPeriod = activePeriods.firstOrNull { p ->
            val s = p.startEpochSeconds?.takeIf { it > 0L }
            val e = p.endEpochSeconds?.takeIf { it > 0L }
            (s == null || s <= nowEpochSeconds) && (e == null || e >= nowEpochSeconds)
        }
        if (activeNowPeriod != null) {
            val end = activeNowPeriod.endEpochSeconds?.takeIf { it > 0L }
            return if (end != null && end - nowEpochSeconds <= 21L * 86_400L) {
                "Attivo · fino al ${fmt.format(java.util.Date(end * 1000L))}"
            } else {
                "Attivo ora"
            }
        }
        val nextStart = activePeriods
            .mapNotNull { it.startEpochSeconds?.takeIf { s -> s > nowEpochSeconds } }
            .minOrNull()
        return if (nextStart != null) {
            "Dal ${fmt.format(java.util.Date(nextStart * 1000L))}"
        } else {
            null
        }
    }
}

private val FOUR_DIGIT_YEAR_REGEX = Regex("\\b(20\\d{2})\\b")
private val ITALIAN_NUMERIC_DATE_REGEX = Regex("\\b([0-3]?\\d)[/\\-.]([01]?\\d)[/\\-.](20\\d{2})\\b")
private val ITALIAN_MONTH_DATE_REGEX = Regex(
    "\\b([0-3]?\\d)(?:°|º)?\\s+(gennaio|febbraio|marzo|aprile|maggio|giugno|luglio|agosto|settembre|ottobre|novembre|dicembre)(?:\\s+(20\\d{2}))?\\b",
    RegexOption.IGNORE_CASE,
)
private val WHITESPACE_COLLAPSE_REGEX = Regex("\\s+")
private val TAIL_TO_PARENS_REGEX = Regex("\\s*\\(TO\\)\\s*$", RegexOption.IGNORE_CASE)
private val FERMATA_PREFIX_REGEX = Regex("^Fermata\\s+\\d+\\s*[-–:]\\s*", RegexOption.IGNORE_CASE)
private val NUMERIC_PREFIX_REGEX = Regex("^#?\\d{2,5}\\s*[-–:]\\s*")
private val LINEA_ARROW_PREFIX_REGEX = Regex("^Linea\\s+[A-Za-z0-9/]+\\s*(?:->|→)\\s*", RegexOption.IGNORE_CASE)

private fun extractItalianDatesEpochSeconds(text: String): List<Long> {
    val matches = ITALIAN_NUMERIC_DATE_REGEX.findAll(text)
    val cal = java.util.Calendar.getInstance()
    val out = mutableListOf<Long>()
    for (m in matches) {
        val day = m.groupValues[1].toIntOrNull() ?: continue
        val month = m.groupValues[2].toIntOrNull() ?: continue
        val year = m.groupValues[3].toIntOrNull() ?: continue
        if (day !in 1..31 || month !in 1..12 || year !in 2015..2040) continue
        cal.set(year, month - 1, day, 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        out.add(cal.timeInMillis / 1000L)
    }
    return out
}

private val ITALIAN_MONTHS = mapOf(
    "gennaio" to 1,
    "febbraio" to 2,
    "marzo" to 3,
    "aprile" to 4,
    "maggio" to 5,
    "giugno" to 6,
    "luglio" to 7,
    "agosto" to 8,
    "settembre" to 9,
    "ottobre" to 10,
    "novembre" to 11,
    "dicembre" to 12,
)

/**
 * Extracts Italian written dates such as "10 luglio", "7 luglio 2026", "1° maggio".
 * When year is omitted, resolves to the nearest plausible year around [nowEpochSeconds]
 * (preferring the past year if the month is >3 months ahead of now, or next year for Dec -> Jan rollover).
 */
private fun extractItalianMonthDatesEpochSeconds(text: String, nowEpochSeconds: Long): List<Long> {
    val nowCal = java.util.Calendar.getInstance().apply { timeInMillis = nowEpochSeconds * 1000L }
    val nowYear = nowCal.get(java.util.Calendar.YEAR)
    val nowMonth = nowCal.get(java.util.Calendar.MONTH) + 1
    val cal = java.util.Calendar.getInstance()
    val out = mutableListOf<Long>()

    for (m in ITALIAN_MONTH_DATE_REGEX.findAll(text)) {
        val day = m.groupValues[1].toIntOrNull() ?: continue
        val month = ITALIAN_MONTHS[m.groupValues[2].lowercase(java.util.Locale.ITALY)] ?: continue
        if (day !in 1..31) continue
        val explicitYear = m.groupValues[3].toIntOrNull()
        val year = explicitYear ?: when {
            // Year rollover: e.g. now is December and alert says "5 gennaio" -> next year
            nowMonth >= 11 && month <= 2 -> nowYear + 1
            // e.g. now is January and alert says "10 novembre" -> previous year
            nowMonth <= 2 && month >= 10 -> nowYear - 1
            else -> nowYear
        }
        cal.set(year, month - 1, day, 0, 0, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        out.add(cal.timeInMillis / 1000L)
    }
    return out
}

@Immutable
data class DataSourceStatus(
    val id: String,
    val name: String,
    val isOnline: Boolean,
    val latencyMs: Long,
    val itemCount: Int,
    val detail: String,
)

@Immutable
data class BusVehicle(
    val entityId: String,
    val vehicleId: String,
    val label: String,
    val rawRouteId: String,
    val lineCode: String,
    val tripId: String?,
    val directionId: Int?,
    val latitude: Double,
    val longitude: Double,
    val bearing: Float?,
    val speedKmh: Float?,
    val timestampSeconds: Long,
    val stopId: String?,
    val status: VehicleStopStatus,
    val sourceName: String = "GTT GTFS-RT",
    val contributingSources: Set<String> = setOf("GTT GTFS-RT"),
    val delaySeconds: Int? = null,
    val nextStopId: String? = null,
    val lastMovedAtMillis: Long? = null,
    val stationarySinceMillis: Long? = null,
    val fetchedAtMillis: Long = System.currentTimeMillis(),
) {
    /**
     * Exact age in seconds since the bus's onboard GPS last emitted this position.
     * Falls back to feed fetch time if GPS timestamp is missing (0).
     */
    fun gpsAgeSeconds(nowMillis: Long): Long? {
        if (timestampSeconds <= 0L) return null
        return ((nowMillis / 1000L) - timestampSeconds).coerceAtLeast(0L)
    }

    fun freshness(nowMillis: Long): VehicleFreshness {
        val age = gpsAgeSeconds(nowMillis) ?: return VehicleFreshness.SLOW
        return when {
            age <= 45L -> VehicleFreshness.LIVE
            age <= 150L -> VehicleFreshness.SLOW
            else -> VehicleFreshness.STALE
        }
    }
}

@Immutable
data class TransitStop(
    val stopId: String,
    val stopCode: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val directionId: Int,
    val sequence: Int = 0,
) {
    val displayCode: String
        get() = stopCode.ifBlank { stopId }
}

@Immutable
data class FavoriteStop(
    val stopId: String,
    val stopCode: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lineCode: String,
    val directionId: Int,
    val terminusName: String? = null,
) {
    val displayCode: String
        get() = stopCode.ifBlank { stopId }

    fun toTransitStop(): TransitStop = TransitStop(
        stopId = stopId,
        stopCode = stopCode,
        name = name,
        lat = lat,
        lon = lon,
        directionId = directionId,
    )
}

@Immutable
data class StopArrival(
    val lineCode: String,
    val headsign: String,
    val arrivalEpochSeconds: Long,
    val isRealtime: Boolean,
    val delaySeconds: Int? = null,
    val vehicleId: String? = null,
    val tripId: String? = null,
    val sourceLabel: String = "GTT Live",
) {
    fun minutesUntil(nowMillis: Long): Int {
        val diffMs = (arrivalEpochSeconds * 1000L) - nowMillis
        return (diffMs / 60_000L).toInt()
    }
}

@Immutable
data class RoutePattern(
    val id: String,
    val name: String,
    val directionId: Int,
    val segments: List<List<GeoPoint>>,
    val stops: List<TransitStop> = emptyList(),
) {
    val points: List<GeoPoint>
        get() = segments.flatten()

    /**
     * Returns the real human-readable terminus (capolinea) stop name for this direction.
     * Prefers the actual final stop name of the route (e.g. "Siccardi Cap", "Allamano Cap")
     * whenever the pattern has its ordered stop sequence, avoiding overly generic zone headsigns
     * like "Centro" or "Torino".
     */
    fun terminusName(): String? {
        val lastStop = if (stops.size >= 2) {
            stops.lastOrNull()?.name?.trim()
                ?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() || ch == '#' } }
        } else {
            null
        }
        if (lastStop != null) {
            return shortTurinPlaceName(lastStop)
        }

        val rawName = name.trim()
        val cleanedHeadsign = rawName
            .substringAfter(" to ", rawName)
            .substringAfter(" -> ", rawName)
            .replace(TAIL_TO_PARENS_REGEX, "")
            .trim()
        val isGeneric = cleanedHeadsign.isBlank() ||
            cleanedHeadsign.startsWith("Linea ", ignoreCase = true) ||
            cleanedHeadsign.contains(" - ") ||
            cleanedHeadsign.length <= 2
        val chosen = if (!isGeneric) {
            cleanedHeadsign
        } else {
            stops.lastOrNull()?.name?.trim().orEmpty()
        }
        return chosen.takeIf { it.isNotBlank() }?.let(::shortTurinPlaceName)
    }
}

/**
 * Returns true when a direction/headsign label is too generic (e.g. "Centro", "Torino", "Linea 5")
 * to tell a commuter the actual terminus stop name (such as "Siccardi Cap").
 */
private val GENERIC_DIRECTION_ZONES = setOf(
    "centro",
    "torino",
    "torino centro",
    "nord",
    "sud",
    "est",
    "ovest",
    "andata",
    "ritorno",
    "circolare",
    "mirafiori",
    "mirafiori nord",
    "mirafiori sud",
    "grugliasco",
    "collegno",
    "orbassano",
    "moncalieri",
    "nichelino",
    "venaria",
    "settimo",
    "rivoli",
    "beinasco",
    "san mauro",
)

fun isGenericDirectionLabel(raw: String?): Boolean {
    val cleaned = raw?.trim()?.replace(WHITESPACE_COLLAPSE_REGEX, " ")?.lowercase(java.util.Locale.ITALY)
        ?: return true
    if (cleaned.isEmpty() || cleaned == "?" || cleaned.startsWith("linea ")) return true
    return cleaned in GENERIC_DIRECTION_ZONES
}

/**
 * Formats ALL-CAPS Turin GTT stop/terminus labels into clean readable Title Case
 * while preserving abbreviations like FS, GTT, M1 and already mixed-case names.
 */
private val TURIN_KEEP_UPPER = setOf("FS", "GTT", "M1", "TO", "II", "III", "IV", "VI", "XX", "XXI")
private val TURIN_SMALL_WORDS = setOf("di", "da", "del", "della", "delle", "dei", "degli", "dello", "in", "su", "per", "a", "e", "al", "alla", "alle", "ai")
private val TURIN_ITALIAN_ABBR = mapOf(
    "p.za" to "P.za",
    "c.so" to "C.so",
    "l.go" to "L.go",
    "v.le" to "V.le",
    "s.ta" to "S.ta",
)

fun formatTurinLabel(raw: String): String {
    val trimmed = raw.trim().replace(WHITESPACE_COLLAPSE_REGEX, " ")
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.contains(',')) {
        val formattedParts = trimmed.split(",")
            .map { formatTurinLabel(it) }
            .filter { it.isNotEmpty() }
        if (formattedParts.isNotEmpty()) {
            return formattedParts.joinToString(", ")
        }
    }
    val hasLower = trimmed.any { it.isLowerCase() }
    if (hasLower) return trimmed

    return trimmed.split(" ").mapIndexed { idx, token ->
        val lower = token.lowercase()
        when {
            lower in TURIN_ITALIAN_ABBR -> TURIN_ITALIAN_ABBR.getValue(lower)
            token in TURIN_KEEP_UPPER -> token
            idx > 0 && lower in TURIN_SMALL_WORDS -> lower
            else -> {
                // Handle apostrophes (e.g., D'AZEGLIO -> D'Azeglio, L'UNIONE -> L'Unione) and slashes/hyphens
                val sb = StringBuilder(lower.length)
                var capNext = true
                for (ch in lower) {
                    if (capNext && ch.isLetter()) {
                        sb.append(ch.titlecaseChar())
                        capNext = false
                    } else {
                        sb.append(ch)
                        if (ch == '\'' || ch == '-' || ch == '/' || ch == '.') {
                            capNext = true
                        }
                    }
                }
                sb.toString()
            }
        }
    }.joinToString(" ")
}

/**
 * Cleans non-essential boilerplate from Turin GTT stop/terminus names while ALWAYS preserving
 * the exact recognizable stop/capolinea name:
 * - "SICCARDI CAP" -> "Siccardi Cap" (keeps "Cap"!)
 * - "Fermata 3284 - POLITECNICO" -> "Politecnico"
 * - "CENTRO, Via Siccardi" -> "Via Siccardi" (never discards the street to show generic "Centro")
 * - "TORINO, Piazza Arbarello" -> "Piazza Arbarello"
 * - "MIRAFIORI NORD, Via Allason" -> "Mirafiori Nord, Via Allason"
 */
fun shortTurinPlaceName(raw: String): String {
    val stripped = raw.trim()
        .replace(FERMATA_PREFIX_REGEX, "")
        .replace(NUMERIC_PREFIX_REGEX, "")
        .replace(LINEA_ARROW_PREFIX_REGEX, "")
        .replace(TAIL_TO_PARENS_REGEX, "")
        .trim()
    val formatted = formatTurinLabel(stripped)
    val commaIdx = formatted.indexOf(',')
    if (commaIdx > 0) {
        val left = formatted.substring(0, commaIdx).trim()
        val right = formatted.substring(commaIdx + 1).trim()
        val leftLower = left.lowercase(java.util.Locale.ITALY)
        if (right.length >= 3 && (leftLower == "torino" || leftLower == "centro" || leftLower == "torino centro")) {
            return right
        }
    }
    return formatted
}

@Immutable
data class LineSummary(
    val lineCode: String,
    val rawRouteIds: Set<String>,
    val activeVehicleCount: Int,
)

@Immutable
data class StopSearchEntry(
    val stopId: String,
    val stopCode: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val lines: List<String> = emptyList(),
    val headsigns: List<String> = emptyList(),
) {
    // Pre-computed display & lowercase search fields (allocated once on background thread
    // when the stop index loads -> zero regexes & zero string allocations during scroll/search).
    val displayName: String = shortTurinPlaceName(name)
    val headsignSummary: String? = headsigns.take(2).joinToString(" · ").takeIf { it.isNotBlank() }
    internal val searchCodeLower: String = stopCode.lowercase(java.util.Locale.ITALY)
    internal val searchIdLower: String = stopId.lowercase(java.util.Locale.ITALY)
    internal val searchNameLower: String = name.lowercase(java.util.Locale.ITALY)
    internal val searchHeadLower: String = headsigns.joinToString(" ").lowercase(java.util.Locale.ITALY)
    internal val searchLinesLower: List<String> = lines.map { it.lowercase(java.util.Locale.ITALY) }
    internal val searchLinesUpper: List<String> = lines.map { it.trim().uppercase().replace(" ", "") }

    fun toTransitStop(directionId: Int = 0): TransitStop = TransitStop(
        stopId = stopId,
        stopCode = stopCode.ifBlank { stopId },
        name = name,
        lat = lat,
        lon = lon,
        directionId = directionId,
        sequence = 0,
    )
}

data class LiveFeedSnapshot(
    val feedTimestampSeconds: Long,
    val fetchedAtMillis: Long,
    val allVehicles: List<BusVehicle>,
    val availableLines: List<LineSummary>,
    val sourceStatuses: List<DataSourceStatus> = emptyList(),
    val alerts: List<ServiceAlertInfo> = emptyList(),
)

enum class MapStyle(
    val id: String,
    val labelIt: String,
    val subtitleIt: String,
    val isDark: Boolean,
) {
    DARK(
        id = "dark",
        labelIt = "Scuro",
        subtitleIt = "Contrasto scuro OLED",
        isDark = true,
    ),
    POSITRON(
        id = "positron",
        labelIt = "Chiaro",
        subtitleIt = "Minimalista morbido",
        isDark = false,
    ),
    VOYAGER(
        id = "voyager",
        labelIt = "Classico",
        subtitleIt = "Vettoriale a colori",
        isDark = false,
    ),
    SATELLITE(
        id = "satellite",
        labelIt = "Satellite",
        subtitleIt = "Vista aerea ibrida HD",
        isDark = true,
    );

    companion object {
        fun fromId(id: String?): MapStyle = entries.firstOrNull { it.id == id } ?: DARK
    }
}

enum class WidgetCameraMode(
    val id: String,
    val labelIt: String,
    val descriptionIt: String,
) {
    AUTO_FIT_BUSES(
        id = "auto_fit",
        labelIt = "Automatica sui pullman",
        descriptionIt = "Inquadra sempre tutti i mezzi attivi della linea",
    ),
    FIXED_AREA(
        id = "fixed_area",
        labelIt = "Area fissa personalizzata",
        descriptionIt = "Resta centrata sulla tua zona o fermata preferita",
    );

    companion object {
        fun fromId(id: String?): WidgetCameraMode =
            entries.firstOrNull { it.id == id } ?: AUTO_FIT_BUSES
    }
}

enum class RefreshInterval(
    val minutes: Int,
    val labelIt: String,
) {
    MANUAL(0, "Manuale"),
    MIN_2(2, "2 min"),
    MIN_5(5, "5 min"),
    MIN_15(15, "15 min");

    companion object {
        fun fromMinutes(minutes: Int): RefreshInterval =
            entries.firstOrNull { it.minutes == minutes } ?: MIN_5
    }
}

enum class DataSourceMode(
    val id: String,
    val labelIt: String,
    val shortLabelIt: String,
    val descriptionIt: String,
) {
    AUTO(
        id = "auto",
        labelIt = "Automatico",
        shortLabelIt = "Auto",
        descriptionIt = "Combina MaTO Live, GTT e BusOne con fallback automatico se i GPS si bloccano",
    ),
    MATO_LIVE(
        id = "mato_live",
        labelIt = "MaTO Live",
        shortLabelIt = "MaTO",
        descriptionIt = "Usa solo Muoversi a Torino (MQTT + GraphQL in tempo reale)",
    ),
    GTT_OFFICIAL(
        id = "gtt_official",
        labelIt = "GTT Ufficiale",
        shortLabelIt = "GTT",
        descriptionIt = "Usa solo il feed GTFS-RT ufficiale GTT senza fallback",
    ),
    BUSONE_PROXY(
        id = "busone_proxy",
        labelIt = "BusOne Proxy",
        shortLabelIt = "BusOne",
        descriptionIt = "Usa solo il proxy GTFS-RT BusOne normalizzato",
    );

    val isFixed: Boolean
        get() = this != AUTO

    companion object {
        fun fromId(id: String?): DataSourceMode =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

enum class LivePollInterval(
    val seconds: Int,
    val labelIt: String,
) {
    SEC_15(15, "15 s"),
    SEC_25(25, "25 s"),
    SEC_45(45, "45 s");

    val millis: Long
        get() = seconds * 1_000L

    companion object {
        fun fromSeconds(seconds: Int): LivePollInterval =
            entries.firstOrNull { it.seconds == seconds } ?: SEC_25
    }
}

@Immutable
data class CameraState(
    val centerLat: Double = TURIN_CENTER_LAT,
    val centerLon: Double = TURIN_CENTER_LON,
    val zoom: Double = 13.2,
) {
    companion object {
        const val TURIN_CENTER_LAT = 45.0703
        const val TURIN_CENTER_LON = 7.6869
        const val MIN_ZOOM = 10.5
        const val MAX_ZOOM = 18.8
    }
}

@Immutable
data class UserWidgetSettings(
    val selectedLine: String = "",
    val mapStyle: MapStyle = MapStyle.DARK,
    val cameraMode: WidgetCameraMode = WidgetCameraMode.AUTO_FIT_BUSES,
    val fixedCamera: CameraState = CameraState(),
    val showVehicleLabels: Boolean = true,
    val showRouteLine: Boolean = true,
    val colorByDirection: Boolean = true,
    val hideInactiveInWidget: Boolean = true,
    val refreshInterval: RefreshInterval = RefreshInterval.MIN_5,
    val dataSourceMode: DataSourceMode = DataSourceMode.AUTO,
    val livePollInterval: LivePollInterval = LivePollInterval.SEC_25,
    val keepScreenOn: Boolean = false,
    val showServiceAlerts: Boolean = true,
    val hideInactiveOnMap: Boolean = false,
) {
    val hasSelectedLine: Boolean
        get() = selectedLine.isNotBlank()
}

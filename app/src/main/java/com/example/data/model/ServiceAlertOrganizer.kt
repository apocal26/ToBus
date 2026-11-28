package com.example.data.model

import java.text.Normalizer
import java.util.Locale

/**
 * Organized, deduplicated view of GTT service alerts grouped into non-overlapping
 * commuter-first buckets:
 * 1. [selectedLineAlerts] — alerts affecting the currently viewed line
 * 2. [favoriteLineAlerts] — alerts affecting other saved favorite lines (★)
 * 3. [networkWideAlerts] — general / all-lines Turin network notices (strikes, holiday hours, city-wide events)
 * 4. [otherLineAlerts] — remaining line-specific notices across the GTT network
 */
data class OrganizedServiceAlerts(
    val selectedLineAlerts: List<ServiceAlertInfo>,
    val favoriteLineAlerts: List<ServiceAlertInfo>,
    val networkWideAlerts: List<ServiceAlertInfo>,
    val otherLineAlerts: List<ServiceAlertInfo>,
) {
    val totalCount: Int
        get() = selectedLineAlerts.size + favoriteLineAlerts.size + networkWideAlerts.size + otherLineAlerts.size

    val allOrdered: List<ServiceAlertInfo>
        get() = selectedLineAlerts + favoriteLineAlerts + networkWideAlerts + otherLineAlerts
}

/**
 * Pure-Kotlin sanitizer, deduplicator, and smart organizer for GTT GTFS-RT service alerts.
 *
 * Solves common real-world issues in GTT's `alerts.aspx` protobuf feed:
 * - Multiple feed entities for the exact same event/detour (e.g., one entity per affected line
 *   or per direction with identical text or `"Linea X - <same title>"`) are merged into a SINGLE
 *   canonical alert whose [ServiceAlertInfo.affectedLines] is the sorted union of all affected lines.
 * - Raw HTML tags (`<br>`, `<p>`) and Italian HTML entities (`&egrave;`, `&nbsp;`, `&rsquo;`)
 *   are cleaned into crisp readable Italian text.
 * - Duplicate description text that merely repeats the header is suppressed.
 * - General / all-lines notices (`affectedLines.isEmpty()` or city-wide network phrases) are
 *   identified cleanly so they can be surfaced in their own prominent section instead of getting
 *   buried among dozens of single-line notices.
 */
object ServiceAlertOrganizer {

    private val HTML_BREAK_REGEX = Regex("<\\s*(?:br|/?p|/?div|li)\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val HTML_TAG_REGEX = Regex("<[^>]+>")
    private val MULTI_SPACE_REGEX = Regex("[ \\t\\x0B\\f\\u00A0]+")
    private val MULTI_NEWLINE_REGEX = Regex("\\n{3,}")
    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM_REGEX = Regex("[^a-z0-9]+")
    private const val LINE_TOKEN_PATTERN =
        "(?:metro|m1s?|star\\s*[12]|sf\\s*[12]|ve\\s*1|[a-z]{0,2}\\d{1,4}[a-z/+]{0,2})"
    private val LEADING_LINE_PREFIX_REGEX = Regex(
        pattern = "^(?:linee|linea)\\s+$LINE_TOKEN_PATTERN(?:\\s*(?:,|\\be\\b)\\s*$LINE_TOKEN_PATTERN){0,8}\\s*[:\\-–—•]+\\s*(.+)$",
        option = RegexOption.IGNORE_CASE,
    )
    private val EXPLICIT_LINE_MENTION_REGEX = Regex(
        pattern = "\\b(?:linea|linee)\\s+([0-9]{1,4}[a-z/+]?|metro|m1s?|star\\s*[12]|sf\\s*[12]|ve\\s*1|n[0-9]{1,2}[a-z]?|w[0-9]{1,2})\\b",
        option = RegexOption.IGNORE_CASE,
    )
    private val NETWORK_WIDE_KEYWORDS = listOf(
        "tutte le linee",
        "tutta la rete",
        "intera rete",
        "rete urbana",
        "rete di superficie",
        "servizio urbano e suburbano",
        "trasporto pubblico",
        "sciopero",
        "orario festivo",
        "orario estivo",
        "orario invernale",
        "servizio serale",
        "night buster",
    )

    private val HTML_ENTITIES = mapOf(
        "&nbsp;" to " ",
        "&amp;" to "&",
        "&quot;" to "\"",
        "&apos;" to "'",
        "&#39;" to "'",
        "&rsquo;" to "’",
        "&lsquo;" to "‘",
        "&ldquo;" to "“",
        "&rdquo;" to "”",
        "&ndash;" to "–",
        "&mdash;" to "—",
        "&agrave;" to "à",
        "&egrave;" to "è",
        "&eacute;" to "é",
        "&igrave;" to "ì",
        "&ograve;" to "ò",
        "&ugrave;" to "ù",
        "&Agrave;" to "À",
        "&Egrave;" to "È",
        "&Eacute;" to "É",
        "&Igrave;" to "Ì",
        "&Ograve;" to "Ò",
        "&Ugrave;" to "Ù",
        "&deg;" to "°",
        "&euro;" to "€",
    )

    fun cleanText(raw: String): String {
        if (raw.isBlank()) return ""
        var text = raw.replace("\r\n", "\n").replace('\r', '\n')
        if (text.contains('<')) {
            text = HTML_BREAK_REGEX.replace(text, "\n")
            text = HTML_TAG_REGEX.replace(text, " ")
        }
        if (text.contains('&')) {
            for ((entity, replacement) in HTML_ENTITIES) {
                if (text.contains(entity)) {
                    text = text.replace(entity, replacement)
                }
            }
        }
        val lines = text.split('\n').map { line ->
            MULTI_SPACE_REGEX.replace(line, " ").trim()
        }
        val joined = lines.joinToString("\n").trim()
        return MULTI_NEWLINE_REGEX.replace(joined, "\n\n")
    }

    fun normalizeForMatch(raw: String): String {
        if (raw.isBlank()) return ""
        val decomposed = Normalizer.normalize(raw.trim().lowercase(Locale.ITALY), Normalizer.Form.NFD)
        val noAccents = DIACRITICS_REGEX.replace(decomposed, "")
        return NON_ALNUM_REGEX.replace(noAccents, " ").trim()
    }

    internal fun stripLeadingLinePrefix(header: String): String {
        val trimmed = header.trim()
        val match = LEADING_LINE_PREFIX_REGEX.matchEntire(trimmed) ?: return trimmed
        val rest = match.groupValues[1].trim()
        return if (rest.length >= 8) {
            rest.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.ITALY) else ch.toString()
            }
        } else {
            trimmed
        }
    }

    fun isNetworkWide(alert: ServiceAlertInfo): Boolean {
        if (alert.affectedLines.size >= 10) return true
        val fullLower = "${alert.headerText} ${alert.descriptionText}".lowercase(Locale.ITALY)
        val hasNetworkPhrase = NETWORK_WIDE_KEYWORDS.any { fullLower.contains(it) }
        if (alert.affectedLines.isEmpty()) {
            // Empty affectedLines means agency-wide unless text strictly names a single specific line
            if (hasNetworkPhrase) return true
            val mentionedLines = EXPLICIT_LINE_MENTION_REGEX.findAll(fullLower).count()
            return mentionedLines == 0
        }
        return false
    }

    /**
     * Cleans, filters to current/upcoming notices, deduplicates & merges identical or per-line
     * cloned entities, and sorts by active status -> semantic severity -> recency.
     */
    fun cleanAndDeduplicate(
        rawAlerts: List<ServiceAlertInfo>,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
        maxHorizonDays: Int = 7,
    ): List<ServiceAlertInfo> {
        if (rawAlerts.isEmpty()) return emptyList()

        val sanitized = rawAlerts.mapNotNull { raw ->
            val cleanDesc = cleanText(raw.descriptionText)
            val rawHeaderClean = cleanText(raw.headerText)
            val cleanHeader = when {
                rawHeaderClean.isNotBlank() -> rawHeaderClean
                cleanDesc.isNotBlank() -> extractSmartHeadline(cleanDesc)
                else -> return@mapNotNull null
            }
            val effectiveDesc = if (normalizeForMatch(cleanDesc) == normalizeForMatch(cleanHeader)) {
                ""
            } else {
                cleanDesc
            }
            val cleanLines = raw.affectedLines
                .map { it.trim().uppercase(Locale.ROOT) }
                .filter { it.isNotEmpty() && it != "?" }
                .toSortedSet(naturalLineComparator)

            val candidate = raw.copy(
                id = raw.id.trim().ifEmpty { normalizeForMatch(cleanHeader).take(32) },
                headerText = cleanHeader,
                descriptionText = effectiveDesc,
                affectedLines = cleanLines,
            )
            if (!candidate.isCurrentOrUpcoming(nowEpochSeconds = nowEpochSeconds, maxHorizonDays = maxHorizonDays)) {
                return@mapNotNull null
            }
            candidate
        }

        if (sanitized.isEmpty()) return emptyList()

        // Group duplicates by canonical content key.
        // Handles:
        // 1) Exact same normalized header + description (regardless of capitalization/punctuation/entity ID).
        // 2) Same substantial normalized description (>= 36 chars) with headers that match after stripping
        //    per-line prefixes (e.g. "Linea 4 - Deviazione..." & "Linea 11 - Deviazione...").
        // 3) Same normalized header when both have empty description (or one is a description-less stub of the other).
        val buckets = LinkedHashMap<String, MutableList<ServiceAlertInfo>>()
        val headerToExistingBucket = HashMap<String, String>()

        for (alert in sanitized) {
            val normHeader = normalizeForMatch(alert.headerText)
            val strippedHeader = normalizeForMatch(stripLeadingLinePrefix(alert.headerText))
            val normDesc = normalizeForMatch(alert.descriptionText)

            val key = when {
                normDesc.length >= 36 -> "d:$strippedHeader|$normDesc"
                normDesc.isNotEmpty() -> "hd:$normHeader|$normDesc"
                else -> headerToExistingBucket[strippedHeader] ?: "h:$strippedHeader"
            }

            // If we previously stored a description-less stub with the same stripped header, fold it into this richer bucket
            val stubKey = "h:$strippedHeader"
            if (normDesc.isNotEmpty() && stubKey != key && buckets.containsKey(stubKey)) {
                val moved = buckets.remove(stubKey).orEmpty()
                val target = buckets.getOrPut(key) { mutableListOf() }
                target.addAll(moved)
                target.add(alert)
            } else {
                buckets.getOrPut(key) { mutableListOf() }.add(alert)
            }

            if (strippedHeader.isNotEmpty() && normDesc.isNotEmpty()) {
                headerToExistingBucket.putIfAbsent(strippedHeader, key)
            }
        }

        val merged = buckets.values.map { group ->
            if (group.size == 1) {
                group.first()
            } else {
                mergeDuplicateGroup(group)
            }
        }

        return merged.sortedWith(smartAlertComparator(nowEpochSeconds))
    }

    /**
     * Organizes deduplicated alerts into 4 non-overlapping commuter-first sections:
     * active line -> other favorite lines -> general network-wide notices -> other lines.
     */
    fun organize(
        alerts: List<ServiceAlertInfo>,
        selectedLine: String,
        favoriteLines: List<String>,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L,
    ): OrganizedServiceAlerts {
        if (alerts.isEmpty()) {
            return OrganizedServiceAlerts(emptyList(), emptyList(), emptyList(), emptyList())
        }
        val cleanSelected = selectedLine.trim().uppercase(Locale.ROOT)
        val favSet = favoriteLines
            .map { it.trim().uppercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && !it.equals(cleanSelected, ignoreCase = true) }
            .toSet()

        val selectedList = mutableListOf<ServiceAlertInfo>()
        val favoriteList = mutableListOf<ServiceAlertInfo>()
        val networkList = mutableListOf<ServiceAlertInfo>()
        val otherList = mutableListOf<ServiceAlertInfo>()

        for (alert in alerts) {
            val hitsSelected = cleanSelected.isNotEmpty() && matchesLine(alert, cleanSelected)
            when {
                hitsSelected -> selectedList.add(alert)
                favSet.isNotEmpty() && favSet.any { fav -> matchesLine(alert, fav) } -> favoriteList.add(alert)
                isNetworkWide(alert) -> networkList.add(alert)
                else -> otherList.add(alert)
            }
        }

        val comparator = smartAlertComparator(nowEpochSeconds)
        return OrganizedServiceAlerts(
            selectedLineAlerts = selectedList.sortedWith(comparator),
            favoriteLineAlerts = favoriteList.sortedWith(comparator),
            networkWideAlerts = networkList.sortedWith(comparator),
            otherLineAlerts = otherList.sortedWith(comparator),
        )
    }

    fun matchesLine(alert: ServiceAlertInfo, targetLine: String): Boolean {
        val target = targetLine.trim().uppercase(Locale.ROOT)
        if (target.isEmpty()) return false
        if (alert.affectedLines.isNotEmpty()) {
            return alert.affectedLines.any { it.equals(target, ignoreCase = true) }
        }
        val mentionRegex = Regex(
            pattern = "\\b(?:linea|linee)\\b[^.\\n]{0,45}\\b${Regex.escape(target)}\\b",
            option = RegexOption.IGNORE_CASE,
        )
        return mentionRegex.containsMatchIn(alert.headerText) ||
            mentionRegex.containsMatchIn(alert.descriptionText)
    }

    private fun mergeDuplicateGroup(group: List<ServiceAlertInfo>): ServiceAlertInfo {
        val mergedLines = sortedSetOf(naturalLineComparator)
        val mergedPeriods = LinkedHashSet<AlertTimeRange>()
        var bestCause: Int? = null
        var bestEffect: Int? = null
        var bestCategoryPriority = -1
        var canonicalId = group.first().id

        for (item in group) {
            mergedLines.addAll(item.affectedLines)
            mergedPeriods.addAll(item.activePeriods)
            if (item.id.isNotBlank() && item.id < canonicalId) {
                canonicalId = item.id
            }
            val prio = item.classifyCategory().priority
            if (prio > bestCategoryPriority) {
                bestCategoryPriority = prio
                bestCause = item.gtfsCause ?: bestCause
                bestEffect = item.gtfsEffect ?: bestEffect
            } else {
                if (bestCause == null && item.gtfsCause != null) bestCause = item.gtfsCause
                if (bestEffect == null && item.gtfsEffect != null) bestEffect = item.gtfsEffect
            }
        }

        // If duplicates came from per-line clones ("Linea 4 - Foo" + "Linea 11 - Foo"),
        // prefer the unprefixed clean headline when multiple distinct lines are merged.
        val distinctRawHeaders = group.map { normalizeForMatch(it.headerText) }.distinct()
        val chosenHeader = if (mergedLines.size > 1 && distinctRawHeaders.size > 1) {
            stripLeadingLinePrefix(group.first().headerText)
        } else {
            group.maxByOrNull { it.headerText.length }?.headerText ?: group.first().headerText
        }

        val chosenDesc = group
            .map { it.descriptionText }
            .filter { it.isNotBlank() && normalizeForMatch(it) != normalizeForMatch(chosenHeader) }
            .maxByOrNull { it.length }
            .orEmpty()

        return ServiceAlertInfo(
            id = canonicalId,
            headerText = chosenHeader,
            descriptionText = chosenDesc,
            affectedLines = mergedLines,
            activePeriods = mergedPeriods.toList(),
            gtfsCause = bestCause,
            gtfsEffect = bestEffect,
        )
    }

    private fun extractSmartHeadline(desc: String): String {
        val firstLine = desc.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        if (firstLine.length <= 92) return firstLine
        val sentenceEnd = firstLine.indexOf(". ").takeIf { it in 18..96 }
        if (sentenceEnd != null) return firstLine.substring(0, sentenceEnd)
        val lastSpace = firstLine.lastIndexOf(' ', startIndex = 88).takeIf { it >= 30 }
        return if (lastSpace != null) {
            "${firstLine.substring(0, lastSpace)}…"
        } else {
            "${firstLine.take(88)}…"
        }
    }

    private fun smartAlertComparator(nowEpochSeconds: Long): Comparator<ServiceAlertInfo> =
        compareByDescending<ServiceAlertInfo> { it.isActiveNow(nowEpochSeconds) }
            .thenByDescending { it.classifyCategory().priority }
            .thenBy { it.earliestRelevantStartSeconds(nowEpochSeconds) }
            .thenBy { it.headerText.lowercase(Locale.ITALY) }

    val naturalLineComparator: Comparator<String> = Comparator { a, b ->
        val aUpper = a.trim().uppercase(Locale.ROOT)
        val bUpper = b.trim().uppercase(Locale.ROOT)
        if (aUpper == bUpper) return@Comparator 0
        if (aUpper == "METRO") return@Comparator -1
        if (bUpper == "METRO") return@Comparator 1
        val aNum = aUpper.takeWhile { it.isDigit() }.toIntOrNull()
        val bNum = bUpper.takeWhile { it.isDigit() }.toIntOrNull()
        when {
            aNum != null && bNum != null && aNum != bNum -> aNum.compareTo(bNum)
            aNum != null && bNum == null -> -1
            aNum == null && bNum != null -> 1
            else -> aUpper.compareTo(bUpper)
        }
    }
}

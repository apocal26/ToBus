package com.example

import com.example.data.model.AlertTimeRange
import com.example.data.model.LineSummary
import com.example.data.model.ServiceAlertInfo
import com.example.data.network.GttRealtimeRepository
import com.example.notifications.FavoriteAlertNotifier
import com.example.widget.FavoriteAlertsWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteAlertNotifierAndCatalogTest {

    @Test
    fun mergeLiveSummariesWithStaticCatalog_keepsLiveCountsAddsMetroAndMergesM1WhileKeepingM1S() {
        val staticCatalog = listOf(
            LineSummary(lineCode = "M1", rawRouteIds = setOf("M1U"), activeVehicleCount = 0),
            LineSummary(lineCode = "M1S", rawRouteIds = setOf("M1SU"), activeVehicleCount = 0),
            LineSummary(lineCode = "4", rawRouteIds = setOf("4U"), activeVehicleCount = 9),
            LineSummary(lineCode = "15", rawRouteIds = setOf("15U"), activeVehicleCount = 5),
            LineSummary(lineCode = "99", rawRouteIds = setOf("99U"), activeVehicleCount = 3),
            LineSummary(lineCode = "N4", rawRouteIds = setOf("N4U"), activeVehicleCount = 0),
        )
        val liveSummaries = listOf(
            LineSummary(lineCode = "4", rawRouteIds = setOf("4U"), activeVehicleCount = 12),
            LineSummary(lineCode = "SF2", rawRouteIds = setOf("SF2U"), activeVehicleCount = 2),
        )

        val merged = GttRealtimeRepository.mergeLiveSummariesWithStaticCatalog(
            liveSummaries = liveSummaries,
            staticCatalog = staticCatalog,
        )

        val byCode = merged.associateBy { it.lineCode }
        assertEquals(7, merged.size)
        // M1 is normalized away into METRO; M1S (replacement bus) stays intact
        assertTrue(byCode["M1"] == null)
        assertTrue(byCode["METRO"] != null)
        assertTrue(byCode["M1S"] != null)
        assertEquals(0, byCode["METRO"]?.activeVehicleCount)
        assertEquals(0, byCode["M1S"]?.activeVehicleCount)
        // Active line gets fresh live count (not old catalog count)
        assertEquals(12, byCode["4"]?.activeVehicleCount)
        // Lines absent from live feed MUST drop to 0 (never preserve stale positive counts)
        assertEquals(0, byCode["15"]?.activeVehicleCount)
        assertEquals(0, byCode["99"]?.activeVehicleCount)
        assertEquals(0, byCode["N4"]?.activeVehicleCount)
        // Live-only line not in static catalog is preserved
        assertEquals(2, byCode["SF2"]?.activeVehicleCount)
        // METRO appears first, followed by numeric-first natural order
        assertEquals(listOf("METRO", "4", "15", "99", "M1S", "N4", "SF2"), merged.map { it.lineCode })
    }

    @Test
    fun normalizeGttLineCode_unifiesM1ToMetroAndPreservesM1S() {
        assertEquals("METRO", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("M1"))
        assertEquals("METRO", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("M 1"))
        assertEquals("METRO", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("M01"))
        assertEquals("METRO", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("M1U"))
        assertEquals("METRO", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("gtt:M1U"))
        assertEquals("METRO", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("METRO"))
        assertEquals("M1S", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("M1S"))
        assertEquals("M1S", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("M1 S"))
        assertEquals("M1S", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("M1SU"))
        assertEquals("M1S", com.example.data.network.GtfsRtProtobufParser.normalizeGttLineCode("M1SF"))
        assertTrue(com.example.data.network.GtfsRtProtobufParser.isNonLiveTrackedLine("METRO"))
        assertTrue(com.example.data.network.GtfsRtProtobufParser.isNonLiveTrackedLine("M1"))
        assertTrue(!com.example.data.network.GtfsRtProtobufParser.isNonLiveTrackedLine("M1S"))
        assertTrue(!com.example.data.network.GtfsRtProtobufParser.isNonLiveTrackedLine("4"))
    }

    @Test
    fun normalizeGttLineCode_unifiesBarratePlusSpacingSuffixesAndLeadingZerosWithoutFalseMerges() {
        val norm = com.example.data.network.GtfsRtProtobufParser::normalizeGttLineCode

        // Barrate: slash, spaces, U/F/UF/E suffixes, BARRATO -> canonical B
        for (variant in listOf("17/", "17 /", "17/U", "17/F", "17/UF", "17/E", "17B", "17BU", "17BF", "17BUF", "17BE", "17 BARRATO", "17 BARR", "gtt:17BU", "1:17/")) {
            assertEquals("Variant $variant should normalize to 17B", "17B", norm(variant))
        }
        assertEquals("5B", norm("5/"))
        assertEquals("58B", norm("58/"))
        assertEquals("72B", norm("72/"))
        assertEquals("N1B", norm("N1/"))
        // Distinct base line vs barrata MUST remain distinct
        assertNotEquals(norm("17"), norm("17/"))

        // Plus lines
        for (variant in listOf("13+", "13 +", "13+U", "13+F", "13+UF", "13+E")) {
            assertEquals("Variant $variant should normalize to 13+", "13+", norm(variant))
        }
        assertNotEquals(norm("13"), norm("13+"))

        // Spaced alphanumeric codes vs compact route_id forms
        assertEquals("STAR1", norm("STAR 1"))
        assertEquals("STAR1", norm("STAR1U"))
        assertEquals("STAR1", norm("STAR1F"))
        assertEquals("16CS", norm("16 CS"))
        assertEquals("16CS", norm("16CSU"))
        assertEquals("16CS", norm("16CSF"))
        assertEquals("16CD", norm("16 CD"))
        assertEquals("16CD", norm("16CDF"))
        assertEquals("VE1", norm("VE 1"))
        assertEquals("SF2", norm("SF 2"))
        // Multi-word descriptive names without digits keep their spacing
        assertEquals("VENARIA EXPRESS", norm("Venaria Express"))
        assertEquals("BLU", norm("BLU"))

        // Navette, Extraurbano E, leading zeros & Night Buster
        assertEquals("10N", norm("10NU"))
        assertEquals("10N", norm("10NF"))
        assertEquals("1510", norm("1510E"))
        assertEquals("265", norm("265E"))
        assertEquals("E68", norm("E68"))
        assertEquals("4", norm("04"))
        assertEquals("W1", norm("W01U"))
        assertEquals("S4", norm("S04"))
        assertEquals("N4", norm("N04U"))

        // Alternate feed query codes for GraphQL / Overpass / MQTT fallback
        assertTrue(com.example.data.network.GtfsRtProtobufParser.alternateFeedQueryCodes("17B").contains("17/"))
        assertTrue(com.example.data.network.GtfsRtProtobufParser.alternateFeedQueryCodes("METRO").contains("M1"))
        assertTrue(com.example.data.network.GtfsRtProtobufParser.alternateFeedQueryCodes("STAR1").contains("STAR 1"))
        assertTrue(com.example.data.network.GtfsRtProtobufParser.alternateFeedQueryCodes("16CS").contains("16 CS"))
        assertTrue(com.example.data.network.GtfsRtProtobufParser.alternateFeedQueryCodes("W1").contains("W01"))
    }

    @Test
    fun mergeLiveSummariesWithStaticCatalog_deduplicatesBarrateSpacedAndAliasedPlusRoutes() {
        val staticCatalog = listOf(
            LineSummary(lineCode = "17/", rawRouteIds = setOf("17BU", "17/"), activeVehicleCount = 0),
            LineSummary(lineCode = "58/", rawRouteIds = setOf("58BU", "58/"), activeVehicleCount = 0),
            LineSummary(lineCode = "STAR 1", rawRouteIds = setOf("STAR1U", "STAR 1"), activeVehicleCount = 0),
            LineSummary(lineCode = "13+", rawRouteIds = setOf("13PU", "13+"), activeVehicleCount = 0),
            LineSummary(lineCode = "1510", rawRouteIds = setOf("1510E", "1510"), activeVehicleCount = 0),
            LineSummary(lineCode = "W01", rawRouteIds = setOf("W01U", "W01"), activeVehicleCount = 0),
        )
        val liveSummaries = listOf(
            LineSummary(lineCode = "17B", rawRouteIds = setOf("17BU"), activeVehicleCount = 5),
            LineSummary(lineCode = "58BF", rawRouteIds = setOf("58BF"), activeVehicleCount = 2),
            LineSummary(lineCode = "STAR1", rawRouteIds = setOf("STAR1U"), activeVehicleCount = 3),
            // Simulate a GTFS-RT feed sending route_id 13PU (-> 13P) while static shortName was 13+
            LineSummary(lineCode = "13P", rawRouteIds = setOf("13PU"), activeVehicleCount = 4),
            LineSummary(lineCode = "1510E", rawRouteIds = setOf("1510E"), activeVehicleCount = 1),
        )

        val merged = GttRealtimeRepository.mergeLiveSummariesWithStaticCatalog(
            liveSummaries = liveSummaries,
            staticCatalog = staticCatalog,
        )
        val byCode = merged.associateBy { it.lineCode }

        // ZERO slash/spaced/alias duplicates!
        assertTrue("17/ must not duplicate 17B", byCode["17/"] == null)
        assertTrue("58/ must not duplicate 58B", byCode["58/"] == null)
        assertTrue("STAR 1 must not duplicate STAR1", byCode["STAR 1"] == null)
        assertTrue("13+ must merge with aliased live 13P", byCode["13+"] == null)
        assertTrue("1510E must merge into 1510", byCode["1510E"] == null)
        assertTrue("W01 must normalize to W1", byCode["W01"] == null)

        assertEquals(5, byCode["17B"]?.activeVehicleCount)
        assertTrue(byCode["17B"]?.rawRouteIds?.containsAll(setOf("17BU", "17/")) == true)
        assertEquals(2, byCode["58B"]?.activeVehicleCount)
        assertEquals(3, byCode["STAR1"]?.activeVehicleCount)
        assertEquals(4, byCode["13P"]?.activeVehicleCount)
        assertEquals(1, byCode["1510"]?.activeVehicleCount)
        assertEquals(0, byCode["W1"]?.activeVehicleCount)
        assertEquals(7, merged.size) // METRO, 13P, 17B, 58B, 1510, STAR1, W1
    }

    @Test
    fun findNewFavoriteLineAlerts_blocksDailyRegeneratedIdWithSameContent() {
        val now = 1_764_200_000L
        val day1Alert = ServiceAlertInfo(
            id = "gtt_feed_2026_11_27_001",
            headerText = "Deviazione in Corso Vittorio Emanuele II",
            descriptionText = "Cantiere stradale: deviazione da via Nizza a piazza Carlo Felice.",
            affectedLines = setOf("4", "15"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now - 3600L, endEpochSeconds = now + 86_400L * 5L)),
        )

        val day1Matches = FavoriteAlertNotifier.findNewFavoriteLineAlerts(
            alerts = listOf(day1Alert),
            favoriteLines = listOf("4", "15"),
            seenFingerprints = emptySet(),
            nowEpochSeconds = now,
        )
        assertEquals(1, day1Matches.size)
        // Single grouped notification covering both favorite lines
        assertEquals(listOf("4", "15"), day1Matches.first().matchedLines)

        val seen = day1Matches.flatMap { it.allFingerprints }.toSet()

        // Day 2: GTT regenerates a brand-new entity ID, tweaks capitalization & punctuation
        val day2Regenerated = day1Alert.copy(
            id = "gtt_feed_2026_11_28_999",
            headerText = "  DEVIAZIONE in corso Vittorio Emanuele II! ",
            descriptionText = "Cantiere stradale — deviazione da Via Nizza a Piazza Carlo Felice",
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now + 86_400L, endEpochSeconds = now + 86_400L * 6L)),
        )
        val day2Matches = FavoriteAlertNotifier.findNewFavoriteLineAlerts(
            alerts = listOf(day2Regenerated),
            favoriteLines = listOf("4", "15"),
            seenFingerprints = seen,
            nowEpochSeconds = now + 86_400L,
        )
        assertTrue("Regenerated daily ID with identical normalized content must NOT re-notify", day2Matches.isEmpty())
    }

    @Test
    fun findNewFavoriteLineAlerts_blocksTextEditWhenSameEntityId() {
        val now = 1_764_200_000L
        val original = ServiceAlertInfo(
            id = "gtt_alert_7788",
            headerText = "Lavori in Piazza Castello",
            descriptionText = "Deviazione fino alle ore 18:00.",
            affectedLines = setOf("13"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now - 600L, endEpochSeconds = now + 10_000L)),
        )
        val seen = FavoriteAlertNotifier.computeAlertFingerprints(original)

        val editedSameId = original.copy(
            descriptionText = "Deviazione prorogata fino alle ore 20:00.",
        )
        val matches = FavoriteAlertNotifier.findNewFavoriteLineAlerts(
            alerts = listOf(editedSameId),
            favoriteLines = listOf("13"),
            seenFingerprints = seen,
            nowEpochSeconds = now + 300L,
        )
        assertTrue("Same alert ID with minor edit must NOT re-notify", matches.isEmpty())
    }

    @Test
    fun findNewFavoriteLineAlerts_deduplicatesWithinSameBatchAndIgnoresNonFavoritesOrExpired() {
        val now = 1_764_200_000L
        val alertA = ServiceAlertInfo(
            id = "entity_a",
            headerText = "Chiusura Via Po",
            descriptionText = "Manifestazione in centro",
            affectedLines = setOf("15", "55"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now - 300L, endEpochSeconds = now + 7200L)),
        )
        val duplicateInBatch = alertA.copy(id = "entity_b")
        val nonFavoriteAlert = ServiceAlertInfo(
            id = "entity_c",
            headerText = "Fermata spostata",
            descriptionText = "Fermata 471 spostata di 30 metri",
            affectedLines = setOf("33"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now - 300L, endEpochSeconds = now + 7200L)),
        )
        val expiredAlert = ServiceAlertInfo(
            id = "entity_d",
            headerText = "Vecchio avviso",
            descriptionText = "Terminato ieri",
            affectedLines = setOf("15"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now - 172_800L, endEpochSeconds = now - 3600L)),
        )
        val distinctNewAlert = ServiceAlertInfo(
            id = "entity_e",
            headerText = "Interruzione tram",
            descriptionText = "Servizio sostitutivo bus in corso",
            affectedLines = setOf("4"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now - 120L, endEpochSeconds = now + 3600L)),
        )

        val matches = FavoriteAlertNotifier.findNewFavoriteLineAlerts(
            alerts = listOf(alertA, duplicateInBatch, nonFavoriteAlert, expiredAlert, distinctNewAlert),
            favoriteLines = listOf("4", "15"),
            seenFingerprints = emptySet(),
            nowEpochSeconds = now,
        )

        assertEquals(2, matches.size)
        assertEquals(listOf("15"), matches[0].matchedLines)
        assertEquals(listOf("4"), matches[1].matchedLines)
        assertNotEquals(matches[0].contentFingerprint, matches[1].contentFingerprint)
    }

    @Test
    fun favoriteAlertsWidget_buildAlertRows_ordersActiveFirstDeduplicatesAndGroupsFavoriteLines() {
        val now = 1_764_200_000L
        val upcoming = ServiceAlertInfo(
            id = "up_1",
            headerText = "Deviazione programmata domenica",
            descriptionText = "Manifestazione sportiva in centro",
            affectedLines = setOf("4", "99"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now + 86_400L, endEpochSeconds = now + 172_800L)),
        )
        val activeMulti = ServiceAlertInfo(
            id = "act_1",
            headerText = "Cantiere Via Roma",
            descriptionText = "Percorso deviato su corso Galileo Ferraris",
            affectedLines = setOf("15", "4", "33"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now - 3_600L, endEpochSeconds = now + 14_400L)),
        )
        val duplicateActive = activeMulti.copy(id = "act_1_dup")
        val unrelated = ServiceAlertInfo(
            id = "other_1",
            headerText = "Avviso linea 68",
            descriptionText = "Non tra le preferite",
            affectedLines = setOf("68"),
            activePeriods = listOf(AlertTimeRange(startEpochSeconds = now - 600L, endEpochSeconds = now + 3_600L)),
        )

        val rows = FavoriteAlertsWidgetProvider.buildAlertRows(
            alerts = listOf(upcoming, activeMulti, duplicateActive, unrelated),
            favoriteLines = listOf("4", "15"),
            nowEpochSeconds = now,
        )

        assertEquals(2, rows.size)
        // Active-now notice comes before upcoming notice
        assertTrue(rows[0].isActiveNow)
        assertEquals(listOf("4", "15"), rows[0].matchedLines)
        assertEquals("Cantiere Via Roma", rows[0].title)
        assertEquals("4", rows[0].primaryLine)

        assertTrue(!rows[1].isActiveNow)
        assertEquals(listOf("4"), rows[1].matchedLines)
        assertEquals(com.example.data.model.ServiceAlertCategory.DISRUPTION, rows[0].category)
    }

    @Test
    fun serviceAlert_classifyCategory_distinguishesCriticalDisruptionFacilityPositiveAndInfo() {
        val strike = ServiceAlertInfo(
            id = "a1",
            headerText = "Sciopero nazionale del trasporto pubblico",
            descriptionText = "Possibili cancellazioni e servizio sospeso dalle 9 alle 12.",
            affectedLines = setOf("METRO", "4"),
        )
        val diversion = ServiceAlertInfo(
            id = "a2",
            headerText = "Deviazione linea 15 per lavori in via Po",
            descriptionText = "Fermata 471 temporaneamente soppressa.",
            affectedLines = setOf("15"),
        )
        val metroElevator = ServiceAlertInfo(
            id = "a3",
            headerText = "Stazione Porta Nuova: ascensori temporaneamente fuori servizio",
            descriptionText = "Gli ascensori di accesso alla banchina direzione Bengasi non sono disponibili.",
            affectedLines = setOf("METRO"),
            gtfsEffect = 1, // Even if GTFS-RT lazily tags NO_SERVICE, elevator outage must stay NOTICE (amber)
        )
        val extraBuses = ServiceAlertInfo(
            id = "a4",
            headerText = "Partita Allianz Stadium: potenziamento linee 9 e 72",
            descriptionText = "Previsto aumento dei pullman disponibili e corse straordinarie prima e dopo l'evento.",
            affectedLines = setOf("9", "72"),
        )
        val metroExtendedHours = ServiceAlertInfo(
            id = "a5",
            headerText = "Prolungamento orario della Metropolitana",
            descriptionText = "Servizio prolungato fino all'01:30 di sabato notte.",
            affectedLines = setOf("METRO"),
        )
        val elevatorReopened = ServiceAlertInfo(
            id = "a6",
            headerText = "Riattivato l'ascensore alla stazione Lingotto",
            descriptionText = "Impianto tornato in funzione regolarmente.",
            affectedLines = setOf("METRO"),
        )
        val generalInfo = ServiceAlertInfo(
            id = "a7",
            headerText = "Orario festivo in vigore lunedì 8 dicembre",
            descriptionText = "Le linee urbane e suburbane seguono l'orario festivo.",
            affectedLines = setOf("4"),
        )

        assertEquals(com.example.data.model.ServiceAlertCategory.CRITICAL, strike.classifyCategory())
        assertEquals(com.example.data.model.ServiceAlertCategory.DISRUPTION, diversion.classifyCategory())
        assertEquals(com.example.data.model.ServiceAlertCategory.NOTICE, metroElevator.classifyCategory())
        assertEquals(com.example.data.model.ServiceAlertCategory.POSITIVE, extraBuses.classifyCategory())
        assertEquals(com.example.data.model.ServiceAlertCategory.POSITIVE, metroExtendedHours.classifyCategory())
        assertEquals(com.example.data.model.ServiceAlertCategory.POSITIVE, elevatorReopened.classifyCategory())
        assertEquals(com.example.data.model.ServiceAlertCategory.INFO, generalInfo.classifyCategory())

        // Highest priority selection for bell & dialog header
        assertEquals(
            com.example.data.model.ServiceAlertCategory.NOTICE,
            listOf(generalInfo, metroExtendedHours, metroElevator).let {
                com.example.data.model.ServiceAlertCategory.entries
                it.maxByOrNull { a -> a.classifyCategory().priority }?.classifyCategory()
            },
        )
        assertEquals(
            com.example.data.model.ServiceAlertCategory.CRITICAL,
            listOf(extraBuses, metroElevator, diversion, strike).maxByOrNull { it.classifyCategory().priority }?.classifyCategory(),
        )
    }

    @Test
    fun serviceAlertOrganizer_cleansHtmlMergesPerLineClonesAndOrganizesWithoutDuplicates() {
        val now = 1_764_200_000L
        val activePeriod = listOf(AlertTimeRange(startEpochSeconds = now - 600L, endEpochSeconds = now + 7_200L))

        val rawAlerts = listOf(
            // Per-line clones of the same event (should merge into 1 alert covering lines 4 and 11)
            ServiceAlertInfo(
                id = "e_101",
                headerText = "Linea 4 - Deviazione in Piazza Castello",
                descriptionText = "Cantiere &amp; chiusura temporanea:<br>percorso deviato su corso Vittorio Emanuele II.",
                affectedLines = setOf("4"),
                activePeriods = activePeriod,
            ),
            ServiceAlertInfo(
                id = "e_102",
                headerText = "Linea 11 - Deviazione in Piazza Castello",
                descriptionText = "Cantiere & chiusura temporanea: percorso deviato su corso Vittorio Emanuele II.",
                affectedLines = setOf("11"),
                activePeriods = activePeriod,
            ),
            // Exact duplicate with punctuation & whitespace difference
            ServiceAlertInfo(
                id = "e_103",
                headerText = "  Linea 11 - deviazione in piazza castello! ",
                descriptionText = "Cantiere &amp; chiusura temporanea: percorso deviato su corso Vittorio Emanuele II",
                affectedLines = setOf("11", "?"),
                activePeriods = activePeriod,
            ),
            // Favorite-only line alert
            ServiceAlertInfo(
                id = "e_200",
                headerText = "Fermata spostata",
                descriptionText = "Fermata 471 spostata di 30 metri per lavori.",
                affectedLines = setOf("15"),
                activePeriods = activePeriod,
            ),
            // General network-wide strike (empty affectedLines)
            ServiceAlertInfo(
                id = "e_300",
                headerText = "Sciopero nazionale del trasporto pubblico",
                descriptionText = "Possibili cancellazioni su tutte le linee urbane e suburbane.&nbsp;",
                affectedLines = emptySet(),
                activePeriods = activePeriod,
            ),
            // Other city line alert
            ServiceAlertInfo(
                id = "e_400",
                headerText = "Potenziamento linea 68",
                descriptionText = "Corse aggiuntive per evento serale.",
                affectedLines = setOf("68"),
                activePeriods = activePeriod,
            ),
        )

        val cleaned = com.example.data.model.ServiceAlertOrganizer.cleanAndDeduplicate(rawAlerts, nowEpochSeconds = now)
        assertEquals(4, cleaned.size)

        val mergedDetour = cleaned.first { it.affectedLines.contains("4") }
        assertEquals(setOf("4", "11"), mergedDetour.affectedLines)
        assertEquals("Deviazione in Piazza Castello", mergedDetour.headerText)
        assertTrue(!mergedDetour.descriptionText.contains("<br>"))
        assertTrue(!mergedDetour.descriptionText.contains("&amp;"))

        val organized = com.example.data.model.ServiceAlertOrganizer.organize(
            alerts = cleaned,
            selectedLine = "4",
            favoriteLines = listOf("4", "15"),
            nowEpochSeconds = now,
        )
        assertEquals(1, organized.selectedLineAlerts.size)
        assertEquals(1, organized.favoriteLineAlerts.size)
        assertEquals(1, organized.networkWideAlerts.size)
        assertEquals(1, organized.otherLineAlerts.size)
        assertEquals(4, organized.totalCount)
        assertEquals(setOf("15"), organized.favoriteLineAlerts.first().affectedLines)
        assertTrue(organized.networkWideAlerts.first().affectedLines.isEmpty())
        assertEquals(setOf("68"), organized.otherLineAlerts.first().affectedLines)
    }

    @Test
    fun findNewFavoriteLineAlerts_respectsAllowedCategoriesCheckboxesAndNetworkCritical() {
        val now = 1_764_200_000L
        val period = listOf(AlertTimeRange(startEpochSeconds = now - 300L, endEpochSeconds = now + 7_200L))

        val strikeGeneral = ServiceAlertInfo(
            id = "cat_crit",
            headerText = "Sciopero generale GTT",
            descriptionText = "Servizio sospeso su tutte le linee dalle 9 alle 12.",
            affectedLines = emptySet(),
            activePeriods = period,
        )
        val detourFav = ServiceAlertInfo(
            id = "cat_detour",
            headerText = "Deviazione linea 12 per lavori",
            descriptionText = "Percorso deviato in corso Orbassano.",
            affectedLines = setOf("12"),
            activePeriods = period,
        )
        val infoFav = ServiceAlertInfo(
            id = "cat_info",
            headerText = "Orario festivo linea 12",
            descriptionText = "Domenica in vigore l'orario festivo.",
            affectedLines = setOf("12"),
            activePeriods = period,
        )
        val allAlerts = listOf(strikeGeneral, detourFav, infoFav)

        // Only disruptions selected -> only detourFav notifies
        val onlyDisruptions = FavoriteAlertNotifier.findNewFavoriteLineAlerts(
            alerts = allAlerts,
            favoriteLines = listOf("12"),
            seenFingerprints = emptySet(),
            nowEpochSeconds = now,
            allowedCategories = setOf(com.example.data.model.ServiceAlertCategory.DISRUPTION),
        )
        assertEquals(1, onlyDisruptions.size)
        assertEquals("cat_detour", onlyDisruptions.first().alert.id)

        // Only critical selected -> general GTT strike notifies
        val onlyCritical = FavoriteAlertNotifier.findNewFavoriteLineAlerts(
            alerts = allAlerts,
            favoriteLines = listOf("12"),
            seenFingerprints = emptySet(),
            nowEpochSeconds = now,
            allowedCategories = setOf(com.example.data.model.ServiceAlertCategory.CRITICAL),
        )
        assertEquals(1, onlyCritical.size)
        assertEquals("cat_crit", onlyCritical.first().alert.id)

        // Empty selection -> 0 notifications
        val noneAllowed = FavoriteAlertNotifier.findNewFavoriteLineAlerts(
            alerts = allAlerts,
            favoriteLines = listOf("12"),
            seenFingerprints = emptySet(),
            nowEpochSeconds = now,
            allowedCategories = emptySet(),
        )
        assertTrue(noneAllowed.isEmpty())
    }

    @Test
    fun findNewFavoriteLineAlerts_blocksSingleVehicleNoiseAndGenericNetworkInfoSpam() {
        val now = 1_764_200_000L
        val period = listOf(AlertTimeRange(startEpochSeconds = now - 300L, endEpochSeconds = now + 7_200L))

        val vehicleCapacityNoise = ServiceAlertInfo(
            id = "veh_9204",
            headerText = "Informazione sul servizio",
            descriptionText = "La capienza sul mezzo 9204 non è garantita.",
            affectedLines = setOf("12"),
            activePeriods = period,
        )
        val exactScreenshotVehicle8008 = ServiceAlertInfo(
            id = "veh_8008",
            headerText = "Capacità posti disponibili non garantita sul mezzo 8008",
            descriptionText = "La capacità massima dei posti disponibili è stata raggiunta e potrebbe non essere garantita la salita a bordo.",
            affectedLines = emptySet(),
            activePeriods = period,
            gtfsEffect = 1, // NO_SERVICE sent by GTT on full vehicles!
        )
        assertEquals(
            com.example.data.model.ServiceAlertCategory.INFO,
            exactScreenshotVehicle8008.classifyCategory(),
        )
        val bulkGenericNotice = ServiceAlertInfo(
            id = "bulk_info",
            headerText = "Avviso agli utenti GTT",
            descriptionText = "Promemoria sulle norme di accesso e bigliettazione a bordo.",
            affectedLines = setOf("2", "3", "4", "9", "10", "11", "12", "13", "15", "16CD", "18"),
            activePeriods = period,
        )
        val genuineLineDetour = ServiceAlertInfo(
            id = "real_detour_12",
            headerText = "Deviazione linea 12",
            descriptionText = "Chiusura al traffico in corso Vittorio Emanuele II: percorso deviato.",
            affectedLines = setOf("12"),
            activePeriods = period,
        )

        val matched = FavoriteAlertNotifier.findNewFavoriteLineAlerts(
            alerts = listOf(vehicleCapacityNoise, exactScreenshotVehicle8008, bulkGenericNotice, genuineLineDetour),
            favoriteLines = listOf("12"),
            seenFingerprints = emptySet(),
            nowEpochSeconds = now,
            allowedCategories = com.example.data.model.ServiceAlertCategory.entries.toSet(),
        )
        assertEquals(listOf("real_detour_12"), matched.map { it.alert.id })
    }
}

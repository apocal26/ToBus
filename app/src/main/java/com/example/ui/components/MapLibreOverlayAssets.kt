package com.example.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.RectF
import com.example.data.model.BusVehicle
import com.example.data.model.MapStyle
import com.example.data.model.RoutePattern
import com.example.data.model.TransitStop
import com.example.data.model.VehicleHealthAssessment
import com.example.data.model.shortTurinPlaceName
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

internal object MapLibreTransitIds {
    const val CURATED_LANDMARKS_SOURCE = "tobus-curated-landmarks-source"
    const val CURATED_LANDMARKS_PRIMARY_LAYER = "tobus-curated-landmarks-primary"
    const val CURATED_LANDMARKS_SECONDARY_LAYER = "tobus-curated-landmarks-secondary"
    const val MAJOR_LANDMARKS_LAYER = "tobus-major-landmarks"
    const val MINOR_LANDMARKS_LAYER = "tobus-minor-landmarks"

    const val ROUTE_SOURCE = "tobus-routes-source"
    const val ROUTE_CASING_LAYER = "tobus-route-casing"
    const val ROUTE_GLOW_LAYER = "tobus-route-glow"
    const val ROUTE_CORE_LAYER = "tobus-route-core"
    const val ROUTE_ARROWS_LAYER = "tobus-route-arrows"

    const val EXTERNAL_ROUTE_SOURCE = "tobus-external-route-source"
    const val EXTERNAL_ROUTE_CASING_LAYER = "tobus-external-route-casing"
    const val EXTERNAL_ROUTE_GLOW_LAYER = "tobus-external-route-glow"
    const val EXTERNAL_ROUTE_CORE_LAYER = "tobus-external-route-core"

    const val STOPS_SOURCE = "tobus-stops-source"
    const val STOPS_HALO_LAYER = "tobus-stops-halo"
    const val STOPS_DOT_LAYER = "tobus-stops-dot"
    const val STOPS_FAV_STAR_LAYER = "tobus-stops-fav-star"
    const val STOPS_SELECTED_LABEL_LAYER = "tobus-stops-selected-label"

    const val USER_SOURCE = "tobus-user-source"
    const val USER_PULSE_LAYER = "tobus-user-pulse"
    const val USER_DOT_LAYER = "tobus-user-dot"

    const val BUSES_SOURCE = "tobus-buses-source"
    const val BUSES_SELECTED_HALO_LAYER = "tobus-buses-selected-halo"
    const val BUSES_ICON_LAYER = "tobus-buses-icon"
    const val BUSES_LABEL_LAYER = "tobus-buses-label"

    // Dedicated isolated source & top-most layers exclusively for a focused bus of another line
    const val EXTERNAL_BUS_SOURCE = "tobus-external-bus-source"
    const val EXTERNAL_BUS_HALO_LAYER = "tobus-external-bus-halo"
    const val EXTERNAL_BUS_CORE_LAYER = "tobus-external-bus-core"
    const val EXTERNAL_BUS_ICON_LAYER = "tobus-external-bus-icon"
    const val EXTERNAL_BUS_LABEL_LAYER = "tobus-external-bus-label"

    const val ICON_ROUTE_CHEVRON = "tobus-route-chevron"
    const val ICON_FAV_STOP_STAR = "tobus-fav-stop-star"

    const val ICON_GOLD = "tobus-bus-gold"
    const val ICON_GOLD_SEL = "tobus-bus-gold-sel"
    const val ICON_GOLD_NODIR = "tobus-bus-gold-nodir"
    const val ICON_GOLD_NODIR_SEL = "tobus-bus-gold-nodir-sel"
    const val ICON_TERMINUS_GOLD = "tobus-bus-terminus-gold"
    const val ICON_TERMINUS_GOLD_SEL = "tobus-bus-terminus-gold-sel"

    const val ICON_CYAN = "tobus-bus-cyan"
    const val ICON_CYAN_SEL = "tobus-bus-cyan-sel"
    const val ICON_CYAN_NODIR = "tobus-bus-cyan-nodir"
    const val ICON_CYAN_NODIR_SEL = "tobus-bus-cyan-nodir-sel"
    const val ICON_TERMINUS_CYAN = "tobus-bus-terminus-cyan"
    const val ICON_TERMINUS_CYAN_SEL = "tobus-bus-terminus-cyan-sel"

    const val ICON_EXT_ARROW = "tobus-bus-ext-arrow"
    const val ICON_EXT_NODIR = "tobus-bus-ext-nodir"

    const val ICON_DEPOT = "tobus-bus-depot"
    const val ICON_DEPOT_SEL = "tobus-bus-depot-sel"

    const val ICON_STALE = "tobus-bus-stale"
    const val ICON_STALE_SEL = "tobus-bus-stale-sel"

    const val LM_PARK = "tobus-lm-park"
    const val LM_RAIL = "tobus-lm-rail"
    const val LM_CULTURE = "tobus-lm-culture"
    const val LM_PIAZZA = "tobus-lm-piazza"
    const val LM_HOSPITAL = "tobus-lm-hospital"
    const val LM_STADIUM = "tobus-lm-stadium"
    const val LM_DEFAULT = "tobus-lm-default"
}

/**
 * Adds a single curated Major Landmarks layer above the clean Turin vector basemap:
 * stations, hospitals, universities, stadiums, major parks, museums, castles, theatres, monuments.
 * Explicitly excludes retail shops, bakeries, bars, parking lots, and duplicate bus stops.
 */
internal fun installMajorLandmarksLayer(
    style: Style,
    mapStyle: MapStyle,
    density: Float,
    preRenderedLandmarks: Map<String, Bitmap>? = null,
) {
    val isDark = mapStyle.isDark

    registerLandmarkDotSprites(style, isDark = isDark, density = density, preRendered = preRenderedLandmarks)

    val haloColor = if (isDark) "rgba(10, 14, 22, 0.94)" else "rgba(248, 250, 253, 0.96)"

    val hasVectorSource = style.getSource(TurinVectorStyleBuilder.VECTOR_SOURCE_ID) != null
    if (hasVectorSource) {
        val landmarkClassFilter = Expression.any(
            Expression.eq(Expression.get("class"), Expression.literal("railway")),
            Expression.eq(Expression.get("class"), Expression.literal("hospital")),
            Expression.eq(Expression.get("class"), Expression.literal("university")),
            Expression.eq(Expression.get("class"), Expression.literal("college")),
            Expression.eq(Expression.get("class"), Expression.literal("stadium")),
            Expression.eq(Expression.get("class"), Expression.literal("park")),
            Expression.eq(Expression.get("class"), Expression.literal("museum")),
            Expression.eq(Expression.get("class"), Expression.literal("castle")),
            Expression.eq(Expression.get("class"), Expression.literal("monument")),
            Expression.eq(Expression.get("class"), Expression.literal("theatre")),
            Expression.eq(Expression.get("class"), Expression.literal("town_hall")),
            Expression.eq(Expression.get("class"), Expression.literal("attraction")),
            Expression.eq(Expression.get("class"), Expression.literal("library")),
        )
        val excludeSmallStops = Expression.all(
            Expression.neq(Expression.get("subclass"), Expression.literal("bus_stop")),
            Expression.neq(Expression.get("subclass"), Expression.literal("tram_stop")),
            Expression.neq(Expression.get("subclass"), Expression.literal("platform")),
            Expression.neq(Expression.get("subclass"), Expression.literal("parking")),
        )
        val nameExpr = Expression.coalesce(
            Expression.get("name:it"),
            Expression.get("name:latin"),
            Expression.get("name"),
            Expression.literal(""),
        )
        val rankNumberExpr = Expression.toNumber(
            Expression.coalesce(Expression.get("rank"), Expression.literal(99)),
        )

        val majorVectorCriteria = Expression.any(
            Expression.all(
                Expression.eq(Expression.get("class"), Expression.literal("railway")),
                Expression.eq(Expression.get("subclass"), Expression.literal("station")),
                Expression.neq(Expression.get("subclass"), Expression.literal("subway")),
            ),
            Expression.eq(Expression.get("class"), Expression.literal("castle")),
            Expression.eq(Expression.get("class"), Expression.literal("university")),
            Expression.all(
                Expression.eq(Expression.get("class"), Expression.literal("museum")),
                Expression.lte(rankNumberExpr, Expression.literal(10)),
            ),
            Expression.all(
                Expression.eq(Expression.get("class"), Expression.literal("attraction")),
                Expression.lte(rankNumberExpr, Expression.literal(8)),
            ),
        )

        // Exclude OpenMapTiles POI entries already covered cleanly by CURATED_TURIN_LANDMARKS
        // (including lowercase/variant OSM names such as "parco Rignon" or "Ospedale Mauriziano Umberto I")
        val notAlreadyCuratedFilter = Expression.match(
            Expression.downcase(nameExpr),
            Expression.literal(true),
            Expression.stop("torino porta nuova", false),
            Expression.stop("stazione di torino porta nuova", false),
            Expression.stop("porta nuova", false),
            Expression.stop("torino porta susa", false),
            Expression.stop("stazione di torino porta susa", false),
            Expression.stop("porta susa", false),
            Expression.stop("mole antonelliana", false),
            Expression.stop("palazzo reale", false),
            Expression.stop("palazzo reale di torino", false),
            Expression.stop("lingotto", false),
            Expression.stop("torino lingotto", false),
            Expression.stop("basilica di superga", false),
            Expression.stop("piazza castello", false),
            Expression.stop("piazza vittorio veneto", false),
            Expression.stop("piazza san carlo", false),
            Expression.stop("piazza statuto", false),
            Expression.stop("parco del valentino", false),
            Expression.stop("parco della pellerina", false),
            Expression.stop("parco ruffini", false),
            Expression.stop("parco dora", false),
            Expression.stop("politecnico di torino", false),
            Expression.stop("campus luigi einaudi", false),
            Expression.stop("palazzo nuovo", false),
            Expression.stop("allianz stadium", false),
            Expression.stop("juventus stadium", false),
            Expression.stop("stadio olimpico", false),
            Expression.stop("stadio olimpico grande torino", false),
            Expression.stop("inalpi arena", false),
            Expression.stop("pala alpitour", false),
            Expression.stop("palaalpitour", false),
            Expression.stop("ospedale molinette", false),
            Expression.stop("molinette", false),
            Expression.stop("ospedale mauriziano", false),
            Expression.stop("ospedale mauriziano umberto i", false),
            Expression.stop("ospedale cto", false),
            Expression.stop("cto", false),
            Expression.stop("centro traumatologico ortopedico", false),
            Expression.stop("ospedale maria vittoria", false),
            Expression.stop("ospedale san giovanni bosco", false),
            Expression.stop("ospedale giovanni bosco", false),
            Expression.stop("ospedale martini", false),
            Expression.stop("museo egizio", false),
            Expression.stop("palazzo madama", false),
            Expression.stop("castello del valentino", false),
            Expression.stop("villa della regina", false),
            Expression.stop("galleria civica d'arte moderna e contemporanea", false),
            Expression.stop("gam", false),
            Expression.stop("ogr", false),
            Expression.stop("ogr torino", false),
            Expression.stop("museo nazionale dell'automobile", false),
            Expression.stop("mauto", false),
            Expression.stop("giardini reali", false),
            Expression.stop("parco della colletta", false),
            Expression.stop("parco rignon", false),
            Expression.stop("parco cavalieri di vittorio veneto", false),
            Expression.stop("piazza d'armi", false),
            Expression.stop("parco della tesoriera", false),
            Expression.stop("parco europa", false),
            Expression.stop("parco colonnetti", false),
            Expression.stop("scuola di management ed economia", false),
            Expression.stop("università degli studi di torino - economia", false),
            Expression.stop("unito economia", false),
        )

        val majorLandmarkFilter = Expression.all(
            Expression.has("name"),
            landmarkClassFilter,
            excludeSmallStops,
            notAlreadyCuratedFilter,
            majorVectorCriteria,
        )
        val minorLandmarkFilter = Expression.all(
            Expression.has("name"),
            landmarkClassFilter,
            excludeSmallStops,
            notAlreadyCuratedFilter,
            Expression.not(majorVectorCriteria),
        )

        val landmarkIconExpr = Expression.match(
            Expression.get("class"),
            Expression.literal(MapLibreTransitIds.LM_DEFAULT),
            Expression.stop("park", Expression.literal(MapLibreTransitIds.LM_PARK)),
            Expression.stop("railway", Expression.literal(MapLibreTransitIds.LM_RAIL)),
            Expression.stop("hospital", Expression.literal(MapLibreTransitIds.LM_HOSPITAL)),
            Expression.stop("stadium", Expression.literal(MapLibreTransitIds.LM_STADIUM)),
            Expression.stop("university", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
            Expression.stop("college", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
            Expression.stop("museum", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
            Expression.stop("castle", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
            Expression.stop("monument", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
            Expression.stop("theatre", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
            Expression.stop("attraction", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
            Expression.stop("town_hall", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        )

        val landmarkTextColorExpr = if (isDark) {
            Expression.match(
                Expression.get("class"),
                Expression.literal("#DAE4F2"),
                Expression.stop("park", Expression.literal("#8DE2B9")),
                Expression.stop("railway", Expression.literal("#84CEFF")),
                Expression.stop("hospital", Expression.literal("#F6A4AB")),
                Expression.stop("stadium", Expression.literal("#B5C4FF")),
                Expression.stop("university", Expression.literal("#F2D794")),
                Expression.stop("college", Expression.literal("#F2D794")),
                Expression.stop("museum", Expression.literal("#F2D794")),
                Expression.stop("castle", Expression.literal("#F2D794")),
                Expression.stop("monument", Expression.literal("#F2D794")),
                Expression.stop("theatre", Expression.literal("#F2D794")),
                Expression.stop("attraction", Expression.literal("#F2D794")),
                Expression.stop("town_hall", Expression.literal("#F2D794")),
            )
        } else {
            Expression.match(
                Expression.get("class"),
                Expression.literal("#2D3B4E"),
                Expression.stop("park", Expression.literal("#1F6B43")),
                Expression.stop("railway", Expression.literal("#1B5A8C")),
                Expression.stop("hospital", Expression.literal("#B53344")),
                Expression.stop("stadium", Expression.literal("#43479B")),
                Expression.stop("university", Expression.literal("#7A5011")),
                Expression.stop("college", Expression.literal("#7A5011")),
                Expression.stop("museum", Expression.literal("#7A5011")),
                Expression.stop("castle", Expression.literal("#7A5011")),
                Expression.stop("monument", Expression.literal("#7A5011")),
                Expression.stop("theatre", Expression.literal("#7A5011")),
                Expression.stop("attraction", Expression.literal("#7A5011")),
                Expression.stop("town_hall", Expression.literal("#7A5011")),
            )
        }

        // Bottom-most POI tier: neighborhood gardens, metro stops, clinics, libraries (z 15.4+)
        if (style.getLayer(MapLibreTransitIds.MINOR_LANDMARKS_LAYER) == null) {
            val minorLayer = SymbolLayer(
                MapLibreTransitIds.MINOR_LANDMARKS_LAYER,
                TurinVectorStyleBuilder.VECTOR_SOURCE_ID,
            )
                .withSourceLayer("poi")
                .withFilter(minorLandmarkFilter)
                .withProperties(
                    PropertyFactory.iconImage(landmarkIconExpr),
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(15.4, 0.72f),
                            Expression.stop(17.5, 0.86f),
                        ),
                    ),
                    PropertyFactory.iconPadding(12f),
                    PropertyFactory.iconAllowOverlap(false),
                    PropertyFactory.textField(nameExpr),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(15.4, 10.2f),
                            Expression.stop(17.5, 11.4f),
                        ),
                    ),
                    PropertyFactory.textMaxWidth(8.5f),
                    PropertyFactory.textVariableAnchor(
                        arrayOf(
                            Property.TEXT_ANCHOR_TOP,
                            Property.TEXT_ANCHOR_BOTTOM,
                            Property.TEXT_ANCHOR_LEFT,
                            Property.TEXT_ANCHOR_RIGHT,
                        ),
                    ),
                    PropertyFactory.textRadialOffset(0.7f),
                    PropertyFactory.textColor(landmarkTextColorExpr),
                    PropertyFactory.textHaloColor(haloColor),
                    PropertyFactory.textHaloWidth(1.7f),
                    PropertyFactory.textPadding(14f),
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textOptional(true),
                    PropertyFactory.symbolSortKey(rankNumberExpr),
                )
            minorLayer.minZoom = 16.0f
            style.addLayer(minorLayer)
        }

        // Mid POI tier: additional vector landmarks & surrounding municipalities (z 14.8+)
        if (style.getLayer(MapLibreTransitIds.MAJOR_LANDMARKS_LAYER) == null) {
            val majorLayer = SymbolLayer(
                MapLibreTransitIds.MAJOR_LANDMARKS_LAYER,
                TurinVectorStyleBuilder.VECTOR_SOURCE_ID,
            )
                .withSourceLayer("poi")
                .withFilter(majorLandmarkFilter)
                .withProperties(
                    PropertyFactory.iconImage(landmarkIconExpr),
                    PropertyFactory.iconSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(14.8, 0.84f),
                            Expression.stop(16.8, 0.98f),
                        ),
                    ),
                    PropertyFactory.iconPadding(9f),
                    PropertyFactory.iconAllowOverlap(false),
                    PropertyFactory.textField(nameExpr),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(14.8, 10.8f),
                            Expression.stop(16.8, 12.0f),
                        ),
                    ),
                    PropertyFactory.textMaxWidth(9.0f),
                    PropertyFactory.textVariableAnchor(
                        arrayOf(
                            Property.TEXT_ANCHOR_TOP,
                            Property.TEXT_ANCHOR_BOTTOM,
                            Property.TEXT_ANCHOR_LEFT,
                            Property.TEXT_ANCHOR_RIGHT,
                        ),
                    ),
                    PropertyFactory.textRadialOffset(0.72f),
                    PropertyFactory.textColor(landmarkTextColorExpr),
                    PropertyFactory.textHaloColor(haloColor),
                    PropertyFactory.textHaloWidth(1.8f),
                    PropertyFactory.textPadding(10f),
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textOptional(false),
                    PropertyFactory.symbolSortKey(rankNumberExpr),
                )
            majorLayer.minZoom = 14.8f
            style.addLayer(majorLayer)
        }
    }

    // Curated Torino City Landmarks (always visible from overview zoom, independent of OpenMapTiles z14 POI cutoff)
    if (style.getSource(MapLibreTransitIds.CURATED_LANDMARKS_SOURCE) == null) {
        style.addSource(
            GeoJsonSource(
                MapLibreTransitIds.CURATED_LANDMARKS_SOURCE,
                buildCuratedTurinLandmarksFeatureCollection(),
            ),
        )
    }

    val curatedIconExpr = Expression.match(
        Expression.get("kind"),
        Expression.literal(MapLibreTransitIds.LM_DEFAULT),
        Expression.stop("rail", Expression.literal(MapLibreTransitIds.LM_RAIL)),
        Expression.stop("park", Expression.literal(MapLibreTransitIds.LM_PARK)),
        Expression.stop("piazza", Expression.literal(MapLibreTransitIds.LM_PIAZZA)),
        Expression.stop("culture", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("university", Expression.literal(MapLibreTransitIds.LM_CULTURE)),
        Expression.stop("hospital", Expression.literal(MapLibreTransitIds.LM_HOSPITAL)),
        Expression.stop("stadium", Expression.literal(MapLibreTransitIds.LM_STADIUM)),
    )
    val curatedTextColorExpr = if (isDark) {
        Expression.match(
            Expression.get("kind"),
            Expression.literal("#E2E8F0"),
            Expression.stop("rail", Expression.literal("#84CEFF")),
            Expression.stop("park", Expression.literal("#8DE2B9")),
            Expression.stop("piazza", Expression.literal("#F5DE9E")),
            Expression.stop("culture", Expression.literal("#F5D076")),
            Expression.stop("university", Expression.literal("#F5D076")),
            Expression.stop("hospital", Expression.literal("#F6A4AB")),
            Expression.stop("stadium", Expression.literal("#B5C4FF")),
        )
    } else {
        Expression.match(
            Expression.get("kind"),
            Expression.literal("#1E293B"),
            Expression.stop("rail", Expression.literal("#1B5A8C")),
            Expression.stop("park", Expression.literal("#1F6B43")),
            Expression.stop("piazza", Expression.literal("#6E4A12")),
            Expression.stop("culture", Expression.literal("#7A5011")),
            Expression.stop("university", Expression.literal("#7A5011")),
            Expression.stop("hospital", Expression.literal("#B53344")),
            Expression.stop("stadium", Expression.literal("#43479B")),
        )
    }

    // Tier 2 Curated Landmarks (secondary piazze, hospitals, parks, museums at z 12.5+)
    if (style.getLayer(MapLibreTransitIds.CURATED_LANDMARKS_SECONDARY_LAYER) == null) {
        val secondaryLayer = SymbolLayer(
            MapLibreTransitIds.CURATED_LANDMARKS_SECONDARY_LAYER,
            MapLibreTransitIds.CURATED_LANDMARKS_SOURCE,
        )
            .withFilter(Expression.eq(Expression.get("tier"), Expression.literal(2)))
            .withProperties(
                PropertyFactory.iconImage(curatedIconExpr),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(12.5, 0.80f),
                        Expression.stop(15.0, 0.96f),
                    ),
                ),
                PropertyFactory.iconPadding(5f),
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(12.5, 10.6f),
                        Expression.stop(14.8, 12.0f),
                        Expression.stop(17.5, 12.8f),
                    ),
                ),
                PropertyFactory.textMaxWidth(9.5f),
                PropertyFactory.textVariableAnchor(
                    arrayOf(
                        Property.TEXT_ANCHOR_TOP,
                        Property.TEXT_ANCHOR_BOTTOM,
                        Property.TEXT_ANCHOR_LEFT,
                        Property.TEXT_ANCHOR_RIGHT,
                    ),
                ),
                PropertyFactory.textRadialOffset(0.7f),
                PropertyFactory.textColor(curatedTextColorExpr),
                PropertyFactory.textHaloColor(haloColor),
                PropertyFactory.textHaloWidth(1.9f),
                PropertyFactory.textPadding(5f),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textOptional(false),
                PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("priority"))),
            )
        secondaryLayer.minZoom = 12.5f
        style.addLayer(secondaryLayer)
    }

    // Tier 1 Curated Anchors (Porta Nuova, Porta Susa, Mole, Palazzo Reale, Piazza Castello,
    // Valentino, Pellerina, Ruffini, Dora, Politecnico, Campus Einaudi, Molinette, Stadi — top priority at z 11.2+)
    if (style.getLayer(MapLibreTransitIds.CURATED_LANDMARKS_PRIMARY_LAYER) == null) {
        val primaryLayer = SymbolLayer(
            MapLibreTransitIds.CURATED_LANDMARKS_PRIMARY_LAYER,
            MapLibreTransitIds.CURATED_LANDMARKS_SOURCE,
        )
            .withFilter(Expression.eq(Expression.get("tier"), Expression.literal(1)))
            .withProperties(
                PropertyFactory.iconImage(curatedIconExpr),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(11.2, 0.84f),
                        Expression.stop(14.2, 1.02f),
                    ),
                ),
                PropertyFactory.iconPadding(4f),
                PropertyFactory.iconAllowOverlap(false),
                PropertyFactory.textField(Expression.get("name")),
                PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                PropertyFactory.textSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(11.2, 10.8f),
                        Expression.stop(13.8, 12.0f),
                        Expression.stop(16.8, 13.0f),
                    ),
                ),
                PropertyFactory.textMaxWidth(10.0f),
                PropertyFactory.textVariableAnchor(
                    arrayOf(
                        Property.TEXT_ANCHOR_TOP,
                        Property.TEXT_ANCHOR_BOTTOM,
                        Property.TEXT_ANCHOR_LEFT,
                        Property.TEXT_ANCHOR_RIGHT,
                    ),
                ),
                PropertyFactory.textRadialOffset(0.75f),
                PropertyFactory.textColor(curatedTextColorExpr),
                PropertyFactory.textHaloColor(haloColor),
                PropertyFactory.textHaloWidth(2.0f),
                PropertyFactory.textPadding(4f),
                PropertyFactory.textAllowOverlap(false),
                PropertyFactory.textOptional(false),
                PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("priority"))),
            )
        primaryLayer.minZoom = 11.2f
        style.addLayer(primaryLayer)
    }
}

private data class CuratedTurinLandmark(
    val name: String,
    val lat: Double,
    val lon: Double,
    val kind: String,
    val tier: Int,
    val priority: Int,
)

private val CURATED_TURIN_LANDMARKS = listOf(
    // Tier 1: Core Turin Rail Hubs
    CuratedTurinLandmark("Porta Nuova", 45.0625, 7.6789, "rail", tier = 1, priority = 1),
    CuratedTurinLandmark("Porta Susa", 45.0725, 7.6658, "rail", tier = 1, priority = 1),

    // Tier 1: Iconic Monuments, Palaces & Culture
    CuratedTurinLandmark("Mole Antonelliana", 45.0690, 7.6930, "culture", tier = 1, priority = 2),
    CuratedTurinLandmark("Palazzo Reale", 45.0727, 7.6860, "culture", tier = 1, priority = 4),
    CuratedTurinLandmark("Lingotto", 45.0310, 7.6661, "culture", tier = 1, priority = 4),
    CuratedTurinLandmark("Basilica di Superga", 45.0808, 7.7675, "culture", tier = 1, priority = 5),

    // Tier 1: Major Piazze
    CuratedTurinLandmark("Piazza Castello", 45.0709, 7.6859, "piazza", tier = 1, priority = 2),
    CuratedTurinLandmark("Piazza Vittorio Veneto", 45.0649, 7.6952, "piazza", tier = 1, priority = 3),
    CuratedTurinLandmark("Piazza San Carlo", 45.0676, 7.6828, "piazza", tier = 1, priority = 4),
    CuratedTurinLandmark("Piazza Statuto", 45.0767, 7.6701, "piazza", tier = 1, priority = 4),

    // Tier 1: Major Parks
    CuratedTurinLandmark("Parco del Valentino", 45.0524, 7.6849, "park", tier = 1, priority = 2),
    CuratedTurinLandmark("Parco della Pellerina", 45.0867, 7.6389, "park", tier = 1, priority = 3),
    CuratedTurinLandmark("Parco Ruffini", 45.0591, 7.6317, "park", tier = 1, priority = 3),
    CuratedTurinLandmark("Parco Dora", 45.0897, 7.6667, "park", tier = 1, priority = 3),

    // Tier 1: Major Universities
    CuratedTurinLandmark("Politecnico di Torino", 45.0625, 7.6622, "university", tier = 1, priority = 3),
    CuratedTurinLandmark("Campus Luigi Einaudi", 45.0739, 7.6997, "university", tier = 1, priority = 4),
    CuratedTurinLandmark("Palazzo Nuovo · UniTo", 45.0679, 7.6944, "university", tier = 1, priority = 5),

    // Tier 1: Stadiums & Flagship Hospital
    CuratedTurinLandmark("Allianz Stadium", 45.1094, 7.6411, "stadium", tier = 1, priority = 3),
    CuratedTurinLandmark("Stadio Olimpico", 45.0417, 7.6500, "stadium", tier = 1, priority = 4),
    CuratedTurinLandmark("Ospedale Molinette", 45.0395, 7.6742, "hospital", tier = 1, priority = 4),

    // Tier 2: Secondary Piazze (Transit & Neighborhood Hubs)
    CuratedTurinLandmark("Piazza Solferino", 45.0693, 7.6777, "piazza", tier = 2, priority = 6),
    CuratedTurinLandmark("Piazza Arbarello", 45.0730, 7.6752, "piazza", tier = 2, priority = 7),
    CuratedTurinLandmark("Piazza Bernini", 45.0758, 7.6558, "piazza", tier = 2, priority = 7),
    CuratedTurinLandmark("Piazza Sabotino", 45.0647, 7.6487, "piazza", tier = 2, priority = 7),
    CuratedTurinLandmark("Piazza Rivoli", 45.0756, 7.6444, "piazza", tier = 2, priority = 8),
    CuratedTurinLandmark("Piazza Massaua", 45.0744, 7.6203, "piazza", tier = 2, priority = 8),
    CuratedTurinLandmark("Piazza Carducci", 45.0421, 7.6706, "piazza", tier = 2, priority = 8),
    CuratedTurinLandmark("Piazza Bengasi", 45.0177, 7.6618, "piazza", tier = 2, priority = 7),
    CuratedTurinLandmark("Piazza Sofia", 45.0934, 7.7200, "piazza", tier = 2, priority = 7),
    CuratedTurinLandmark("Piazza Rebaudengo", 45.1030, 7.6945, "piazza", tier = 2, priority = 8),
    CuratedTurinLandmark("Piazza Gran Madre", 45.0625, 7.6998, "piazza", tier = 2, priority = 8),

    // Tier 2: Palaces, Museums & Venues
    CuratedTurinLandmark("Museo Egizio", 45.0684, 7.6843, "culture", tier = 2, priority = 6),
    CuratedTurinLandmark("Palazzo Madama", 45.0709, 7.6858, "culture", tier = 2, priority = 8),
    CuratedTurinLandmark("Castello del Valentino", 45.0543, 7.6855, "culture", tier = 2, priority = 7),
    CuratedTurinLandmark("Villa della Regina", 45.0590, 7.7064, "culture", tier = 2, priority = 8),
    CuratedTurinLandmark("GAM · Arte Moderna", 45.0651, 7.6694, "culture", tier = 2, priority = 8),
    CuratedTurinLandmark("OGR Torino", 45.0666, 7.6608, "culture", tier = 2, priority = 8),
    CuratedTurinLandmark("MAUTO · Museo Auto", 45.0319, 7.6714, "culture", tier = 2, priority = 7),
    CuratedTurinLandmark("Inalpi Arena", 45.0417, 7.6522, "stadium", tier = 2, priority = 7),

    // Tier 2: Parks & Universities
    CuratedTurinLandmark("Giardini Reali", 45.0721, 7.6903, "park", tier = 2, priority = 7),
    CuratedTurinLandmark("Parco della Colletta", 45.0782, 7.7249, "park", tier = 2, priority = 7),
    CuratedTurinLandmark("Parco Rignon", 45.0436, 7.6371, "park", tier = 2, priority = 7),
    CuratedTurinLandmark("Parco Piazza d'Armi", 45.0440, 7.6529, "park", tier = 2, priority = 7),
    CuratedTurinLandmark("Parco della Tesoriera", 45.0761, 7.6384, "park", tier = 2, priority = 8),
    CuratedTurinLandmark("Parco Europa", 45.0309, 7.6842, "park", tier = 2, priority = 8),
    CuratedTurinLandmark("Parco Colonnetti", 45.0120, 7.6456, "park", tier = 2, priority = 8),
    CuratedTurinLandmark("UniTo · Economia", 45.0374, 7.6508, "university", tier = 2, priority = 7),

    // Tier 2: Other Major Hospitals
    CuratedTurinLandmark("Ospedale Mauriziano", 45.0518, 7.6656, "hospital", tier = 2, priority = 6),
    CuratedTurinLandmark("Ospedale CTO", 45.0333, 7.6746, "hospital", tier = 2, priority = 7),
    CuratedTurinLandmark("Ospedale Maria Vittoria", 45.0819, 7.6560, "hospital", tier = 2, priority = 7),
    CuratedTurinLandmark("Ospedale S. Giovanni Bosco", 45.0969, 7.7011, "hospital", tier = 2, priority = 7),
    CuratedTurinLandmark("Ospedale Martini", 45.0658, 7.6253, "hospital", tier = 2, priority = 8),
)

internal fun buildCuratedTurinLandmarksFeatureCollection(): FeatureCollection {
    val features = CURATED_TURIN_LANDMARKS.map { lm ->
        val f = Feature.fromGeometry(Point.fromLngLat(lm.lon, lm.lat))
        f.addStringProperty("name", lm.name)
        f.addStringProperty("kind", lm.kind)
        f.addNumberProperty("tier", lm.tier)
        f.addNumberProperty("priority", lm.priority)
        f
    }
    return FeatureCollection.fromFeatures(features)
}

private val landmarkSpriteCache = java.util.concurrent.ConcurrentHashMap<String, Map<String, Bitmap>>()
private val transitSpriteCache = java.util.concurrent.ConcurrentHashMap<Int, Map<String, Bitmap>>()

/**
 * Generates and caches landmark dot bitmaps off the main thread so MapLibre style setup
 * never stalls UI frames on low-end devices.
 */
internal fun getOrCreateLandmarkDotSprites(isDark: Boolean, density: Float): Map<String, Bitmap> {
    val dpi = (density * 160f).roundToInt().coerceAtLeast(160)
    val cacheKey = "${dpi}_${if (isDark) 1 else 0}"
    landmarkSpriteCache[cacheKey]?.let { return it }

    val rim = if (isDark) AndroidColor.parseColor("#111722") else AndroidColor.WHITE
    val out = LinkedHashMap<String, Bitmap>(7)

    fun addDot(id: String, hex: String, diamond: Boolean = false) {
        val fill = AndroidColor.parseColor(hex)
        val sizePx = (14f * density).roundToInt().coerceAtLeast(20)
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        bmp.density = dpi
        val canvas = Canvas(bmp)
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        if (diamond) {
            val rOuter = 4.6f * density
            val rInner = 3.2f * density
            val outerPath = AndroidPath().apply {
                moveTo(cx, cy - rOuter)
                lineTo(cx + rOuter, cy)
                lineTo(cx, cy + rOuter)
                lineTo(cx - rOuter, cy)
                close()
            }
            val innerPath = AndroidPath().apply {
                moveTo(cx, cy - rInner)
                lineTo(cx + rInner, cy)
                lineTo(cx, cy + rInner)
                lineTo(cx - rInner, cy)
                close()
            }
            paint.style = Paint.Style.FILL
            paint.color = rim
            paint.alpha = 245
            canvas.drawPath(outerPath, paint)

            paint.color = fill
            paint.alpha = 250
            canvas.drawPath(innerPath, paint)
        } else {
            paint.style = Paint.Style.FILL
            paint.color = fill
            paint.alpha = if (isDark) 76 else 54
            canvas.drawCircle(cx, cy, 5.8f * density, paint)

            paint.color = rim
            paint.alpha = 245
            canvas.drawCircle(cx, cy, 4.0f * density, paint)

            paint.color = fill
            paint.alpha = 250
            canvas.drawCircle(cx, cy, 2.9f * density, paint)
        }

        out[id] = bmp
    }

    addDot(MapLibreTransitIds.LM_PARK, if (isDark) "#6CD6A2" else "#2B7A4F")
    addDot(MapLibreTransitIds.LM_RAIL, if (isDark) "#62BCFA" else "#25639B")
    addDot(MapLibreTransitIds.LM_CULTURE, if (isDark) "#EAC772" else "#A96E18")
    addDot(MapLibreTransitIds.LM_PIAZZA, if (isDark) "#F3DC9C" else "#8A5E14", diamond = true)
    addDot(MapLibreTransitIds.LM_HOSPITAL, if (isDark) "#F0828C" else "#C23242")
    addDot(MapLibreTransitIds.LM_STADIUM, if (isDark) "#9AAEF8" else "#5056B8")
    addDot(MapLibreTransitIds.LM_DEFAULT, if (isDark) "#BFD0E4" else "#475569")

    landmarkSpriteCache[cacheKey] = out
    return out
}

private fun registerLandmarkDotSprites(
    style: Style,
    isDark: Boolean,
    density: Float,
    preRendered: Map<String, Bitmap>? = null,
) {
    val sprites = preRendered ?: getOrCreateLandmarkDotSprites(isDark, density)
    for ((id, bmp) in sprites) {
        style.addImage(id, bmp, false)
    }
}

/**
 * Generates and caches all bus/status/chevron/star marker bitmaps off the main thread.
 */
internal fun getOrCreateTransitSprites(density: Float): Map<String, Bitmap> {
    val dpi = (density * 160f).roundToInt().coerceAtLeast(160)
    transitSpriteCache[dpi]?.let { return it }

    val gold = AndroidColor.parseColor("#FFB300")
    val cyan = AndroidColor.parseColor("#38BDF8")
    val emerald = AndroidColor.parseColor("#10B981")
    val darkInk = AndroidColor.parseColor("#0B0F17")
    val out = LinkedHashMap<String, Bitmap>(20)

    fun add(id: String, fill: Int, hasArrow: Boolean, selected: Boolean) {
        out[id] = createBusMarkerBitmap(
            fillArgb = fill,
            inkArgb = darkInk,
            hasArrow = hasArrow,
            selected = selected,
            density = density,
        )
    }

    add(MapLibreTransitIds.ICON_GOLD, gold, hasArrow = true, selected = false)
    add(MapLibreTransitIds.ICON_GOLD_SEL, gold, hasArrow = true, selected = true)
    add(MapLibreTransitIds.ICON_GOLD_NODIR, gold, hasArrow = false, selected = false)
    add(MapLibreTransitIds.ICON_GOLD_NODIR_SEL, gold, hasArrow = false, selected = true)

    add(MapLibreTransitIds.ICON_CYAN, cyan, hasArrow = true, selected = false)
    add(MapLibreTransitIds.ICON_CYAN_SEL, cyan, hasArrow = true, selected = true)
    add(MapLibreTransitIds.ICON_CYAN_NODIR, cyan, hasArrow = false, selected = false)
    add(MapLibreTransitIds.ICON_CYAN_NODIR_SEL, cyan, hasArrow = false, selected = true)

    add(MapLibreTransitIds.ICON_EXT_ARROW, emerald, hasArrow = true, selected = true)
    add(MapLibreTransitIds.ICON_EXT_NODIR, emerald, hasArrow = false, selected = true)

    // Always-upright status badges: Terminus (pause bars in line-colored ring), Depot/Off-Route (garage roof + D), Stale/Stuck (alert !)
    out[MapLibreTransitIds.ICON_TERMINUS_GOLD] = createTerminusMarkerBitmap(gold, selected = false, density = density)
    out[MapLibreTransitIds.ICON_TERMINUS_GOLD_SEL] = createTerminusMarkerBitmap(gold, selected = true, density = density)
    out[MapLibreTransitIds.ICON_TERMINUS_CYAN] = createTerminusMarkerBitmap(cyan, selected = false, density = density)
    out[MapLibreTransitIds.ICON_TERMINUS_CYAN_SEL] = createTerminusMarkerBitmap(cyan, selected = true, density = density)

    out[MapLibreTransitIds.ICON_DEPOT] = createDepotMarkerBitmap(selected = false, density = density)
    out[MapLibreTransitIds.ICON_DEPOT_SEL] = createDepotMarkerBitmap(selected = true, density = density)

    out[MapLibreTransitIds.ICON_STALE] = createStaleAlertMarkerBitmap(selected = false, density = density)
    out[MapLibreTransitIds.ICON_STALE_SEL] = createStaleAlertMarkerBitmap(selected = true, density = density)

    out[MapLibreTransitIds.ICON_ROUTE_CHEVRON] = createRouteChevronBitmap(density = density)
    out[MapLibreTransitIds.ICON_FAV_STOP_STAR] = createFavoriteStarBitmap(density = density)

    transitSpriteCache[dpi] = out
    return out
}

internal fun registerTransitSprites(
    style: Style,
    density: Float,
    preRendered: Map<String, Bitmap>? = null,
) {
    val sprites = preRendered ?: getOrCreateTransitSprites(density)
    for ((id, bmp) in sprites) {
        style.addImage(id, bmp, false)
    }
}

private fun createFavoriteStarBitmap(density: Float): Bitmap {
    val sizePx = (13f * density).roundToInt().coerceAtLeast(18)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    bitmap.density = (density * 160f).roundToInt().coerceAtLeast(160)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val outerR = sizePx * 0.42f
    val innerR = outerR * 0.44f

    val path = AndroidPath()
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) outerR else innerR
        val angleRad = Math.toRadians(-90.0 + i * 36.0)
        val x = cx + (r * kotlin.math.cos(angleRad)).toFloat()
        val y = cy + (r * kotlin.math.sin(angleRad)).toFloat()
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    path.close()

    val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        strokeJoin = Paint.Join.ROUND
        color = AndroidColor.argb(145, 131, 24, 67)
    }
    canvas.drawPath(path, edgePaint)

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = AndroidColor.WHITE
    }
    canvas.drawPath(path, fillPaint)

    return bitmap
}

private fun createRouteChevronBitmap(density: Float): Bitmap {
    val sizePx = (14f * density).roundToInt().coerceAtLeast(18)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    bitmap.density = (density * 160f).roundToInt().coerceAtLeast(160)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f
    val arm = sizePx * 0.25f

    val path = AndroidPath().apply {
        moveTo(cx - arm * 0.65f, cy - arm)
        lineTo(cx + arm * 0.65f, cy)
        lineTo(cx - arm * 0.65f, cy + arm)
    }

    // Subtle dark casing around the chevron so it pops cleanly on both gold and cyan lines
    val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3.2f * density
        color = AndroidColor.argb(190, 8, 12, 20)
    }
    canvas.drawPath(path, outlinePaint)

    val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.75f * density
        color = AndroidColor.WHITE
    }
    canvas.drawPath(path, corePaint)

    return bitmap
}

private fun createBusMarkerBitmap(
    fillArgb: Int,
    inkArgb: Int,
    hasArrow: Boolean,
    selected: Boolean,
    density: Float,
): Bitmap {
    val sizeDp = if (selected) 56f else 44f
    val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    bitmap.density = (density * 160f).roundToInt().coerceAtLeast(160)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val baseRadius = (if (selected) 15.8f else 12.8f) * density

    // Soft glowing outer halo
    paint.style = Paint.Style.FILL
    paint.color = fillArgb
    paint.alpha = if (selected) 125 else 68
    canvas.drawCircle(cx, cy, baseRadius + (if (selected) 9.8f else 5.8f) * density, paint)

    // Crisp selection outer ring when selected
    if (selected) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * density
        paint.color = AndroidColor.WHITE
        paint.alpha = 248
        canvas.drawCircle(cx, cy, baseRadius + 4.6f * density, paint)
    }

    // Dark obsidian rim for unmistakable separation over routes & labels
    paint.style = Paint.Style.FILL
    paint.color = inkArgb
    paint.alpha = 250
    canvas.drawCircle(cx, cy, baseRadius + 2.2f * density, paint)

    // Vibrant core fill
    paint.color = fillArgb
    paint.alpha = 255
    canvas.drawCircle(cx, cy, baseRadius, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.2f * density
    paint.color = AndroidColor.WHITE
    paint.alpha = 135
    canvas.drawCircle(cx, cy, baseRadius - 0.8f * density, paint)

    if (hasArrow) {
        // Sharp directional navigation chevron pointing North (0 deg), rotated via MapLibre iconRotate
        val arrowR = baseRadius * 0.66f
        val path = AndroidPath().apply {
            moveTo(cx, cy - arrowR)
            lineTo(cx + arrowR * 0.72f, cy + arrowR * 0.68f)
            lineTo(cx, cy + arrowR * 0.24f)
            lineTo(cx - arrowR * 0.72f, cy + arrowR * 0.68f)
            close()
        }
        paint.style = Paint.Style.FILL
        paint.color = inkArgb
        paint.alpha = 255
        canvas.drawPath(path, paint)
    } else {
        // Always-upright centered target dot inside a crisp ring when GPS heading is not reported yet
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.0f * density
        paint.color = inkArgb
        paint.alpha = 230
        canvas.drawCircle(cx, cy, baseRadius * 0.48f, paint)

        paint.style = Paint.Style.FILL
        paint.alpha = 255
        canvas.drawCircle(cx, cy, baseRadius * 0.22f, paint)
    }

    return bitmap
}

/**
 * Always-upright badge for a live service bus waiting at its terminus ("Al capolinea"):
 * dark obsidian center framed by a bold direction-colored ring (Gold/Cyan) + two crisp upright pause bars.
 */
private fun createTerminusMarkerBitmap(
    directionAccentArgb: Int,
    selected: Boolean,
    density: Float,
): Bitmap {
    val sizeDp = if (selected) 56f else 44f
    val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    bitmap.density = (density * 160f).roundToInt().coerceAtLeast(160)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val baseRadius = (if (selected) 15.6f else 12.6f) * density

    // Soft direction-colored halo
    paint.style = Paint.Style.FILL
    paint.color = directionAccentArgb
    paint.alpha = if (selected) 118 else 64
    canvas.drawCircle(cx, cy, baseRadius + (if (selected) 9.4f else 5.4f) * density, paint)

    if (selected) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f * density
        paint.color = AndroidColor.WHITE
        paint.alpha = 248
        canvas.drawCircle(cx, cy, baseRadius + 4.4f * density, paint)
    }

    // Outer dark separation casing
    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.parseColor("#0B0F17")
    paint.alpha = 252
    canvas.drawCircle(cx, cy, baseRadius + 2.0f * density, paint)

    // Bold direction-colored ring
    paint.color = directionAccentArgb
    paint.alpha = 255
    canvas.drawCircle(cx, cy, baseRadius, paint)

    // Dark obsidian inner face so pause bars pop with crystal clarity
    paint.color = AndroidColor.parseColor("#111726")
    canvas.drawCircle(cx, cy, baseRadius - 2.9f * density, paint)

    // Two crisp upright rounded pause bars ("||" = in sosta al capolinea)
    val barW = baseRadius * 0.22f
    val barH = baseRadius * 0.78f
    val barGap = baseRadius * 0.16f
    val barCorner = barW * 0.45f
    paint.style = Paint.Style.FILL
    paint.color = directionAccentArgb
    paint.alpha = 255
    canvas.drawRoundRect(
        RectF(cx - barGap - barW, cy - barH / 2f, cx - barGap, cy + barH / 2f),
        barCorner,
        barCorner,
        paint,
    )
    canvas.drawRoundRect(
        RectF(cx + barGap, cy - barH / 2f, cx + barGap + barW, cy + barH / 2f),
        barCorner,
        barCorner,
        paint,
    )

    return bitmap
}

/**
 * Always-upright badge for a bus inside a GTT depot or returning off-route toward a depot:
 * muted slate-navy circle with a crisp amber-orange rim and an unmistakable pitched garage roof + "D".
 */
private fun createDepotMarkerBitmap(
    selected: Boolean,
    density: Float,
): Bitmap {
    val sizeDp = if (selected) 56f else 44f
    val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    bitmap.density = (density * 160f).roundToInt().coerceAtLeast(160)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val amberRim = AndroidColor.parseColor("#F59E0B")
    val slateFill = AndroidColor.parseColor("#1E293B")
    val glyphColor = AndroidColor.parseColor("#FEF3C7")

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val baseRadius = (if (selected) 15.2f else 12.2f) * density

    // Subtle amber halo
    paint.style = Paint.Style.FILL
    paint.color = amberRim
    paint.alpha = if (selected) 96 else 44
    canvas.drawCircle(cx, cy, baseRadius + (if (selected) 8.6f else 4.8f) * density, paint)

    if (selected) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = AndroidColor.WHITE
        paint.alpha = 245
        canvas.drawCircle(cx, cy, baseRadius + 4.2f * density, paint)
    }

    // Dark outer casing
    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.parseColor("#0B0F17")
    paint.alpha = 245
    canvas.drawCircle(cx, cy, baseRadius + 2.0f * density, paint)

    // Slate-navy core fill
    paint.color = slateFill
    paint.alpha = 250
    canvas.drawCircle(cx, cy, baseRadius, paint)

    // Amber-orange perimeter ring
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2.3f * density
    paint.color = amberRim
    paint.alpha = 250
    canvas.drawCircle(cx, cy, baseRadius - 1.15f * density, paint)

    // Crisp upright pitched depot roof ("/\")
    val roofHalfW = baseRadius * 0.54f
    val roofPeakY = cy - baseRadius * 0.50f
    val roofEaveY = cy - baseRadius * 0.14f
    val roofPath = AndroidPath().apply {
        moveTo(cx - roofHalfW, roofEaveY)
        lineTo(cx, roofPeakY)
        lineTo(cx + roofHalfW, roofEaveY)
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2.0f * density
    paint.strokeCap = Paint.Cap.ROUND
    paint.strokeJoin = Paint.Join.ROUND
    paint.color = amberRim
    paint.alpha = 255
    canvas.drawPath(roofPath, paint)

    // Crisp geometric upright "D" underneath the roof (Deposito)
    val dTop = cy - baseRadius * 0.04f
    val dBot = cy + baseRadius * 0.46f
    val dLeft = cx - baseRadius * 0.24f
    val dMidX = cx + baseRadius * 0.02f
    val dRight = cx + baseRadius * 0.28f
    val dPath = AndroidPath().apply {
        moveTo(dLeft, dTop)
        lineTo(dLeft, dBot)
        lineTo(dMidX, dBot)
        quadTo(dRight, dBot, dRight, (dTop + dBot) * 0.5f)
        quadTo(dRight, dTop, dMidX, dTop)
        close()
    }
    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 1.95f * density
    paint.color = glyphColor
    canvas.drawPath(dPath, paint)

    return bitmap
}

/**
 * Always-upright alert badge for stale GPS or mid-route stalled bus:
 * dark crimson-slate circle with coral-red rim and crisp upright exclamation mark "!".
 */
private fun createStaleAlertMarkerBitmap(
    selected: Boolean,
    density: Float,
): Bitmap {
    val sizeDp = if (selected) 56f else 44f
    val sizePx = (sizeDp * density).roundToInt().coerceAtLeast(48)
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    bitmap.density = (density * 160f).roundToInt().coerceAtLeast(160)
    val canvas = Canvas(bitmap)
    val cx = sizePx / 2f
    val cy = sizePx / 2f

    val coralRim = AndroidColor.parseColor("#F87171")
    val darkCore = AndroidColor.parseColor("#2A151D")
    val glyphColor = AndroidColor.parseColor("#FEE2E2")

    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    val baseRadius = (if (selected) 15.2f else 12.2f) * density

    paint.style = Paint.Style.FILL
    paint.color = coralRim
    paint.alpha = if (selected) 96 else 44
    canvas.drawCircle(cx, cy, baseRadius + (if (selected) 8.6f else 4.8f) * density, paint)

    if (selected) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.2f * density
        paint.color = AndroidColor.WHITE
        paint.alpha = 245
        canvas.drawCircle(cx, cy, baseRadius + 4.2f * density, paint)
    }

    paint.style = Paint.Style.FILL
    paint.color = AndroidColor.parseColor("#0B0F17")
    paint.alpha = 245
    canvas.drawCircle(cx, cy, baseRadius + 2.0f * density, paint)

    paint.color = darkCore
    paint.alpha = 250
    canvas.drawCircle(cx, cy, baseRadius, paint)

    paint.style = Paint.Style.STROKE
    paint.strokeWidth = 2.3f * density
    paint.color = coralRim
    paint.alpha = 250
    canvas.drawCircle(cx, cy, baseRadius - 1.15f * density, paint)

    // Crisp upright exclamation mark ("!")
    val stemW = baseRadius * 0.22f
    val stemTop = cy - baseRadius * 0.48f
    val stemBot = cy + baseRadius * 0.12f
    paint.style = Paint.Style.FILL
    paint.color = glyphColor
    paint.alpha = 255
    canvas.drawRoundRect(
        RectF(cx - stemW / 2f, stemTop, cx + stemW / 2f, stemBot),
        stemW * 0.5f,
        stemW * 0.5f,
        paint,
    )
    canvas.drawCircle(cx, cy + baseRadius * 0.36f, stemW * 0.58f, paint)

    return bitmap
}

internal fun installTransitLayers(style: Style, isDarkBasemap: Boolean) {
    if (style.getSource(MapLibreTransitIds.ROUTE_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.ROUTE_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.EXTERNAL_ROUTE_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.EXTERNAL_ROUTE_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.STOPS_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.STOPS_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.USER_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.USER_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.BUSES_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.BUSES_SOURCE))
    }
    if (style.getSource(MapLibreTransitIds.EXTERNAL_BUS_SOURCE) == null) {
        style.addSource(GeoJsonSource(MapLibreTransitIds.EXTERNAL_BUS_SOURCE))
    }

    val routeColorExpr = Expression.match(
        Expression.get("dir"),
        Expression.literal("#FFB300"),
        Expression.stop(1, Expression.literal("#38BDF8")),
    )
    val routeGlowExpr = Expression.match(
        Expression.get("dir"),
        Expression.literal("rgba(255, 179, 0, 0.24)"),
        Expression.stop(1, Expression.literal("rgba(56, 189, 248, 0.24)")),
    )
    val routeOffsetExpr = Expression.match(
        Expression.get("offsetSign"),
        Expression.literal(0f),
        Expression.stop(-1, Expression.literal(-1.7f)),
        Expression.stop(1, Expression.literal(1.7f)),
    )

    val isRouteDimmed = Expression.eq(Expression.get("dimmed"), Expression.literal(true))
    val isRouteHighlighted = Expression.eq(Expression.get("highlighted"), Expression.literal(true))
    val mainRouteOpacityExpr = Expression.switchCase(
        isRouteDimmed,
        Expression.literal(0.18f),
        Expression.literal(1.0f),
    )
    val mainRouteGlowOpacityExpr = Expression.switchCase(
        isRouteDimmed,
        Expression.literal(0.06f),
        isRouteHighlighted,
        Expression.literal(1.0f),
        Expression.literal(0.75f),
    )

    // 1. Route Polylines (casing -> glow -> crisp core)
    if (style.getLayer(MapLibreTransitIds.ROUTE_CASING_LAYER) == null) {
        style.addLayer(
            LineLayer(MapLibreTransitIds.ROUTE_CASING_LAYER, MapLibreTransitIds.ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(
                    if (isDarkBasemap) "rgba(7, 10, 17, 0.90)" else "rgba(255, 255, 255, 0.92)",
                ),
                PropertyFactory.lineOpacity(mainRouteOpacityExpr),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOffset(routeOffsetExpr),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 5.2f),
                        Expression.stop(13.5, 7.2f),
                        Expression.stop(17.0, 10.6f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.ROUTE_GLOW_LAYER) == null) {
        style.addLayer(
            LineLayer(MapLibreTransitIds.ROUTE_GLOW_LAYER, MapLibreTransitIds.ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(routeGlowExpr),
                PropertyFactory.lineOpacity(mainRouteGlowOpacityExpr),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOffset(routeOffsetExpr),
                PropertyFactory.lineBlur(3.2f),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 7.4f),
                        Expression.stop(13.5, 10.8f),
                        Expression.stop(17.0, 15.2f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.ROUTE_CORE_LAYER) == null) {
        style.addLayer(
            LineLayer(MapLibreTransitIds.ROUTE_CORE_LAYER, MapLibreTransitIds.ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(routeColorExpr),
                PropertyFactory.lineOpacity(mainRouteOpacityExpr),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineOffset(routeOffsetExpr),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 2.8f),
                        Expression.stop(13.5, 4.1f),
                        Expression.stop(17.0, 5.8f),
                    ),
                ),
            ),
        )
    }

    // 1b. Focused External Bus Route Overlay (Emerald highlight when inspecting a bus of another line)
    if (style.getLayer(MapLibreTransitIds.EXTERNAL_ROUTE_CASING_LAYER) == null) {
        style.addLayer(
            LineLayer(
                MapLibreTransitIds.EXTERNAL_ROUTE_CASING_LAYER,
                MapLibreTransitIds.EXTERNAL_ROUTE_SOURCE,
            ).withProperties(
                PropertyFactory.lineColor(
                    if (isDarkBasemap) "rgba(5, 8, 14, 0.94)" else "rgba(255, 255, 255, 0.94)",
                ),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 5.6f),
                        Expression.stop(13.5, 8.2f),
                        Expression.stop(17.0, 11.8f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.EXTERNAL_ROUTE_GLOW_LAYER) == null) {
        style.addLayer(
            LineLayer(
                MapLibreTransitIds.EXTERNAL_ROUTE_GLOW_LAYER,
                MapLibreTransitIds.EXTERNAL_ROUTE_SOURCE,
            ).withProperties(
                PropertyFactory.lineColor("rgba(16, 185, 129, 0.34)"),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineBlur(4.5f),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 8.0f),
                        Expression.stop(13.5, 12.2f),
                        Expression.stop(17.0, 17.0f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.EXTERNAL_ROUTE_CORE_LAYER) == null) {
        style.addLayer(
            LineLayer(
                MapLibreTransitIds.EXTERNAL_ROUTE_CORE_LAYER,
                MapLibreTransitIds.EXTERNAL_ROUTE_SOURCE,
            ).withProperties(
                PropertyFactory.lineColor("#10B981"),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineWidth(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.0, 3.0f),
                        Expression.stop(13.5, 4.4f),
                        Expression.stop(17.0, 6.2f),
                    ),
                ),
            ),
        )
    }

    // 2. Stops (selection/favorite halo -> directional ring dot -> favorite star -> selected stop label)
    val isStopSelected = Expression.eq(Expression.get("isSelected"), Expression.literal(true))
    val isStopFav = Expression.eq(Expression.get("isFav"), Expression.literal(true))
    val isStopDimmed = Expression.eq(Expression.get("dimmed"), Expression.literal(true))
    val stopDotOpacityExpr = Expression.switchCase(
        isStopSelected,
        Expression.literal(1.0f),
        isStopFav,
        Expression.literal(1.0f),
        isStopDimmed,
        Expression.literal(0.24f),
        Expression.literal(1.0f),
    )
    val stopStrokeColor = Expression.match(
        Expression.get("dir"),
        Expression.literal("#FFB300"),
        Expression.stop(1, Expression.literal("#38BDF8")),
    )

    if (style.getLayer(MapLibreTransitIds.STOPS_HALO_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.STOPS_HALO_LAYER, MapLibreTransitIds.STOPS_SOURCE).withProperties(
                PropertyFactory.circleColor(
                    Expression.switchCase(
                        isStopFav,
                        Expression.literal("#EC4899"),
                        Expression.literal("#FFB300"),
                    ),
                ),
                PropertyFactory.circleOpacity(
                    Expression.switchCase(
                        isStopSelected,
                        Expression.literal(0.36f),
                        Expression.all(isStopFav, isStopDimmed),
                        Expression.literal(0.18f),
                        isStopFav,
                        Expression.literal(0.34f),
                        Expression.literal(0f),
                    ),
                ),
                PropertyFactory.circleRadius(
                    Expression.switchCase(
                        isStopSelected,
                        Expression.literal(15.5f),
                        isStopFav,
                        Expression.literal(13.5f),
                        Expression.literal(0f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.STOPS_DOT_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.STOPS_DOT_LAYER, MapLibreTransitIds.STOPS_SOURCE).withProperties(
                PropertyFactory.circleColor(
                    Expression.switchCase(
                        isStopFav,
                        Expression.literal("#DB2777"),
                        isStopSelected,
                        Expression.literal("#FFFFFF"),
                        Expression.literal(if (isDarkBasemap) "#0E131B" else "#FFFFFF"),
                    ),
                ),
                PropertyFactory.circleOpacity(stopDotOpacityExpr),
                PropertyFactory.circleStrokeOpacity(stopDotOpacityExpr),
                PropertyFactory.circleStrokeColor(
                    Expression.switchCase(
                        Expression.all(isStopSelected, isStopFav),
                        Expression.literal("#FFFFFF"),
                        isStopFav,
                        Expression.literal("#FFFFFF"),
                        isStopSelected,
                        Expression.literal("#FFB300"),
                        stopStrokeColor,
                    ),
                ),
                PropertyFactory.circleStrokeWidth(
                    Expression.switchCase(
                        Expression.all(isStopSelected, isStopFav),
                        Expression.literal(2.8f),
                        isStopSelected,
                        Expression.literal(2.6f),
                        isStopFav,
                        Expression.literal(2.2f),
                        Expression.literal(1.9f),
                    ),
                ),
                PropertyFactory.circleRadius(
                    Expression.switchCase(
                        Expression.all(isStopSelected, isStopFav),
                        Expression.literal(8.4f),
                        isStopFav,
                        Expression.literal(7.2f),
                        isStopSelected,
                        Expression.literal(6.4f),
                        Expression.interpolate(
                            Expression.linear(),
                            Expression.zoom(),
                            Expression.stop(11.0, 2.5f),
                            Expression.stop(14.5, 4.1f),
                            Expression.stop(17.5, 5.2f),
                        ),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.STOPS_FAV_STAR_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.STOPS_FAV_STAR_LAYER, MapLibreTransitIds.STOPS_SOURCE)
                .withFilter(isStopFav)
                .withProperties(
                    PropertyFactory.iconImage(MapLibreTransitIds.ICON_FAV_STOP_STAR),
                    PropertyFactory.iconSize(
                        Expression.switchCase(
                            isStopSelected,
                            Expression.literal(0.92f),
                            Expression.literal(0.78f),
                        ),
                    ),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconOpacity(
                        Expression.switchCase(
                            isStopSelected,
                            Expression.literal(1.0f),
                            isStopDimmed,
                            Expression.literal(0.88f),
                            Expression.literal(1.0f),
                        ),
                    ),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.STOPS_SELECTED_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.STOPS_SELECTED_LABEL_LAYER, MapLibreTransitIds.STOPS_SOURCE)
                .withFilter(isStopSelected)
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(12.2f),
                    PropertyFactory.textOffset(arrayOf(0f, -1.65f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_BOTTOM),
                    PropertyFactory.textColor(
                        Expression.switchCase(
                            isStopFav,
                            Expression.literal("#F472B6"),
                            Expression.literal("#FFB300"),
                        ),
                    ),
                    PropertyFactory.textHaloColor("rgba(8, 11, 16, 0.92)"),
                    PropertyFactory.textHaloWidth(2.0f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
        )
    }

    // 3. Optional User GPS Dot (pulsing halo -> crisp cyan dot)
    if (style.getLayer(MapLibreTransitIds.USER_PULSE_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.USER_PULSE_LAYER, MapLibreTransitIds.USER_SOURCE).withProperties(
                PropertyFactory.circleColor("#38BDF8"),
                PropertyFactory.circleOpacity(0.24f),
                PropertyFactory.circleRadius(18f),
            ),
        )
    }
    if (style.getLayer(MapLibreTransitIds.USER_DOT_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.USER_DOT_LAYER, MapLibreTransitIds.USER_SOURCE).withProperties(
                PropertyFactory.circleColor("#38BDF8"),
                PropertyFactory.circleRadius(6.8f),
                PropertyFactory.circleStrokeColor("#FFFFFF"),
                PropertyFactory.circleStrokeWidth(2.6f),
            ),
        )
    }

    // 4. Tracked Line Live Moving Buses
    if (style.getLayer(MapLibreTransitIds.BUSES_SELECTED_HALO_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.BUSES_SELECTED_HALO_LAYER, MapLibreTransitIds.BUSES_SOURCE)
                .withFilter(Expression.eq(Expression.get("isSelected"), Expression.literal(true)))
                .withProperties(
                    PropertyFactory.circleColor("#FFB300"),
                    PropertyFactory.circleOpacity(0.28f),
                    PropertyFactory.circleRadius(24f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeOpacity(0.62f),
                    PropertyFactory.circleStrokeWidth(1.6f),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.BUSES_ICON_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.BUSES_ICON_LAYER, MapLibreTransitIds.BUSES_SOURCE).withProperties(
                PropertyFactory.iconImage(Expression.get("icon")),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("bearing"))),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.symbolSortKey(Expression.toNumber(Expression.get("sortKey"))),
                PropertyFactory.iconSize(
                    Expression.interpolate(
                        Expression.linear(),
                        Expression.zoom(),
                        Expression.stop(10.5, 0.78f),
                        Expression.stop(13.5, 0.92f),
                        Expression.stop(16.5, 1.06f),
                    ),
                ),
            ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.BUSES_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.BUSES_LABEL_LAYER, MapLibreTransitIds.BUSES_SOURCE)
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(11f),
                    PropertyFactory.textOffset(arrayOf(0f, 1.85f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textColor(if (isDarkBasemap) "#F8FAFC" else "#0F172A"),
                    PropertyFactory.textHaloColor(
                        if (isDarkBasemap) "rgba(8, 11, 16, 0.90)" else "rgba(255, 255, 255, 0.92)",
                    ),
                    PropertyFactory.textHaloWidth(1.8f),
                    PropertyFactory.textAllowOverlap(false),
                ),
        )
    }

    // 5. Dedicated TOP-MOST Layers for a Focused External Bus (e.g. tapping Linea 46 or 55 inside a Stop card)
    // Pure static CircleLayers + isolated SymbolLayers so it is 100% immune to collision, filters, or line-bus animation!
    if (style.getLayer(MapLibreTransitIds.EXTERNAL_BUS_HALO_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.EXTERNAL_BUS_HALO_LAYER, MapLibreTransitIds.EXTERNAL_BUS_SOURCE)
                .withProperties(
                    PropertyFactory.circleColor("#10B981"),
                    PropertyFactory.circleOpacity(0.28f),
                    PropertyFactory.circleRadius(27f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeOpacity(0.75f),
                    PropertyFactory.circleStrokeWidth(1.8f),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.EXTERNAL_BUS_CORE_LAYER) == null) {
        style.addLayer(
            CircleLayer(MapLibreTransitIds.EXTERNAL_BUS_CORE_LAYER, MapLibreTransitIds.EXTERNAL_BUS_SOURCE)
                .withProperties(
                    PropertyFactory.circleColor("#10B981"),
                    PropertyFactory.circleRadius(13.5f),
                    PropertyFactory.circleStrokeColor("#0B0F17"),
                    PropertyFactory.circleStrokeWidth(2.4f),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.EXTERNAL_BUS_ICON_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.EXTERNAL_BUS_ICON_LAYER, MapLibreTransitIds.EXTERNAL_BUS_SOURCE)
                .withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconRotate(Expression.toNumber(Expression.get("bearing"))),
                    PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                    PropertyFactory.iconAllowOverlap(true),
                    PropertyFactory.iconIgnorePlacement(true),
                    PropertyFactory.iconSize(1.06f),
                ),
        )
    }

    if (style.getLayer(MapLibreTransitIds.EXTERNAL_BUS_LABEL_LAYER) == null) {
        style.addLayer(
            SymbolLayer(MapLibreTransitIds.EXTERNAL_BUS_LABEL_LAYER, MapLibreTransitIds.EXTERNAL_BUS_SOURCE)
                .withProperties(
                    PropertyFactory.textField(Expression.get("label")),
                    PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(12.5f),
                    PropertyFactory.textOffset(arrayOf(0f, 2.25f)),
                    PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textColor("#34D399"),
                    PropertyFactory.textHaloColor("rgba(8, 11, 16, 0.94)"),
                    PropertyFactory.textHaloWidth(2.2f),
                    PropertyFactory.textAllowOverlap(true),
                    PropertyFactory.textIgnorePlacement(true),
                ),
        )
    }
}

internal fun buildRoutesFeatureCollection(
    routePatterns: List<RoutePattern>,
    colorByDirection: Boolean,
    highlightedDirectionId: Int? = null,
    dimAll: Boolean = false,
): FeatureCollection {
    if (routePatterns.isEmpty()) return FeatureCollection.fromFeatures(emptyArray())
    val hasBothDirs = routePatterns.map { it.directionId }.distinct().size > 1
    // Draw dimmed patterns FIRST so the highlighted direction always sits cleanly on top
    val orderedPatterns = routePatterns.sortedByDescending { pat ->
        dimAll || (highlightedDirectionId != null && pat.directionId != highlightedDirectionId)
    }
    val features = orderedPatterns.mapNotNull { pattern ->
        if (pattern.points.size < 2) return@mapNotNull null
        val coords = pattern.points.map { Point.fromLngLat(it.lon, it.lat) }
        val feature = Feature.fromGeometry(LineString.fromLngLats(coords))
        val dir = if (colorByDirection) pattern.directionId else 0
        val dimmed = dimAll || (highlightedDirectionId != null && pattern.directionId != highlightedDirectionId)
        val highlighted = !dimAll && highlightedDirectionId != null && pattern.directionId == highlightedDirectionId
        val offsetSign = when {
            !hasBothDirs || highlighted -> 0
            pattern.directionId == 1 -> 1
            else -> -1
        }
        feature.addNumberProperty("dir", dir)
        feature.addNumberProperty("offsetSign", offsetSign)
        feature.addBooleanProperty("dimmed", dimmed)
        feature.addBooleanProperty("highlighted", highlighted)
        feature
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun buildExternalRouteFeatureCollection(
    externalBus: BusVehicle?,
    externalRoutePatterns: List<RoutePattern>,
): FeatureCollection {
    if (externalBus == null || externalRoutePatterns.isEmpty()) {
        return FeatureCollection.fromFeatures(emptyArray())
    }
    val resolvedDir = com.example.data.model.GttFleetAnalyzer.resolveDirectionId(
        bus = externalBus,
        routePatterns = externalRoutePatterns,
    ) ?: externalBus.directionId
    val selectedPatterns = if (resolvedDir != null) {
        externalRoutePatterns.filter { it.directionId == resolvedDir }.ifEmpty { externalRoutePatterns }
    } else {
        externalRoutePatterns
    }
    val features = selectedPatterns.mapNotNull { pattern ->
        if (pattern.points.size < 2) return@mapNotNull null
        val coords = pattern.points.map { Point.fromLngLat(it.lon, it.lat) }
        Feature.fromGeometry(LineString.fromLngLats(coords))
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun buildStopsFeatureCollection(
    stops: List<TransitStop>,
    routePatterns: List<RoutePattern> = emptyList(),
    favoriteStopIds: Set<String>,
    selectedStopId: String?,
    colorByDirection: Boolean,
    highlightedDirectionId: Int? = null,
    dimAll: Boolean = false,
): FeatureCollection {
    if (stops.isEmpty()) return FeatureCollection.fromFeatures(emptyArray())
    val terminusIds = routePatterns
        .mapNotNull { it.stops.lastOrNull()?.stopId }
        .toSet()

    // Draw dimmed stops first, regular stops next, favorites next, and selected stop last (top-most)
    val orderedStops = stops.sortedWith(
        compareBy<TransitStop> { it.stopId == selectedStopId }
            .thenBy { favoriteStopIds.contains(it.stopId) }
            .thenBy { !(dimAll || (highlightedDirectionId != null && it.directionId != highlightedDirectionId)) },
    )

    val features = orderedStops.map { stop ->
        val feature = Feature.fromGeometry(Point.fromLngLat(stop.lon, stop.lat))
        val dir = if (colorByDirection) stop.directionId else 0
        val isFav = favoriteStopIds.contains(stop.stopId)
        val isSelected = stop.stopId == selectedStopId
        val isTerminus = terminusIds.contains(stop.stopId)
        val dimmed = !isSelected && (dimAll || (highlightedDirectionId != null && stop.directionId != highlightedDirectionId))
        feature.addStringProperty("stopId", stop.stopId)
        feature.addNumberProperty("dir", dir)
        feature.addBooleanProperty("isFav", isFav)
        feature.addBooleanProperty("isSelected", isSelected)
        feature.addBooleanProperty("isTerminus", isTerminus)
        feature.addBooleanProperty("dimmed", dimmed)
        feature.addStringProperty("label", shortTurinPlaceName(stop.name))
        feature
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun buildBusesFeatureCollection(
    vehicles: List<BusVehicle>,
    poseForBus: (BusVehicle) -> Triple<Double, Double, Float?>,
    selectedVehicleId: String?,
    busHealthById: Map<String, VehicleHealthAssessment>,
    resolvedDirectionById: Map<String, Int?>,
    colorByDirection: Boolean,
    showVehicleLabels: Boolean,
): FeatureCollection {
    if (vehicles.isEmpty()) return FeatureCollection.fromFeatures(emptyArray())
    val features = vehicles.mapNotNull { bus ->
        val (lat, lon, rawBearing) = poseForBus(bus)
        if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
        val bearing = rawBearing?.takeIf { it.isFinite() }
        val isSelected = bus.vehicleId == selectedVehicleId
        val health = busHealthById[bus.vehicleId]
        val isDepotOrOffRoute = health != null && (health.isInDepot || health.isOffRoute)
        val isStaleOrStuck = health != null && !isDepotOrOffRoute && (health.isStaleGps || health.isStuck)
        val isAtTerminus = health != null && !isDepotOrOffRoute && !isStaleOrStuck && health.isAtTerminus
        val resolvedDir = resolvedDirectionById[bus.vehicleId] ?: bus.directionId
        val isCyan = colorByDirection && resolvedDir == 1
        val isMovingArrow = bearing != null && !isDepotOrOffRoute && !isStaleOrStuck && !isAtTerminus

        val iconId = when {
            isDepotOrOffRoute -> if (isSelected) MapLibreTransitIds.ICON_DEPOT_SEL else MapLibreTransitIds.ICON_DEPOT
            isStaleOrStuck -> if (isSelected) MapLibreTransitIds.ICON_STALE_SEL else MapLibreTransitIds.ICON_STALE
            isAtTerminus -> when {
                isCyan && isSelected -> MapLibreTransitIds.ICON_TERMINUS_CYAN_SEL
                isCyan -> MapLibreTransitIds.ICON_TERMINUS_CYAN
                isSelected -> MapLibreTransitIds.ICON_TERMINUS_GOLD_SEL
                else -> MapLibreTransitIds.ICON_TERMINUS_GOLD
            }
            isCyan -> when {
                isMovingArrow && isSelected -> MapLibreTransitIds.ICON_CYAN_SEL
                isMovingArrow -> MapLibreTransitIds.ICON_CYAN
                isSelected -> MapLibreTransitIds.ICON_CYAN_NODIR_SEL
                else -> MapLibreTransitIds.ICON_CYAN_NODIR
            }
            else -> when {
                isMovingArrow && isSelected -> MapLibreTransitIds.ICON_GOLD_SEL
                isMovingArrow -> MapLibreTransitIds.ICON_GOLD
                isSelected -> MapLibreTransitIds.ICON_GOLD_NODIR_SEL
                else -> MapLibreTransitIds.ICON_GOLD_NODIR
            }
        }

        val displayId = bus.label.ifBlank { bus.vehicleId }
        val label = if (showVehicleLabels) "#$displayId" else ""
        val sortKey = when {
            isSelected -> 100
            isDepotOrOffRoute || isStaleOrStuck -> 1
            isAtTerminus -> 6
            else -> 10
        }

        val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
        feature.addStringProperty("id", bus.vehicleId)
        feature.addStringProperty("icon", iconId)
        // ONLY directional navigation arrows rotate with bearing; all status & no-dir badges stay strictly upright (0 deg).
        feature.addNumberProperty("bearing", if (isMovingArrow) (bearing ?: 0f).toDouble() else 0.0)
        feature.addStringProperty("label", label)
        feature.addNumberProperty("sortKey", sortKey)
        feature.addBooleanProperty("isSelected", isSelected)
        feature
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun buildExternalBusFeatureCollection(
    bus: BusVehicle?,
    pose: Triple<Double, Double, Float?>? = null,
): FeatureCollection {
    if (bus == null) return FeatureCollection.fromFeatures(emptyArray())
    val lat = pose?.first ?: bus.latitude
    val lon = pose?.second ?: bus.longitude
    val rawBearing = pose?.third ?: bus.bearing
    if (!lat.isFinite() || !lon.isFinite()) return FeatureCollection.fromFeatures(emptyArray())

    val bearing = rawBearing?.takeIf { it.isFinite() }
    val hasArrow = bearing != null
    val iconId = if (hasArrow) {
        MapLibreTransitIds.ICON_EXT_ARROW
    } else {
        MapLibreTransitIds.ICON_EXT_NODIR
    }
    val feature = Feature.fromGeometry(Point.fromLngLat(lon, lat))
    feature.addStringProperty("id", bus.vehicleId)
    feature.addStringProperty("icon", iconId)
    feature.addNumberProperty("bearing", if (hasArrow) (bearing ?: 0f).toDouble() else 0.0)
    feature.addStringProperty("label", "Linea ${bus.lineCode} · #${bus.label}")
    return FeatureCollection.fromFeature(feature)
}

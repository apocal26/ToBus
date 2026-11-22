package com.example.ui.components

import com.example.data.model.MapStyle
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generates clean, self-contained, 100% deterministic MapLibre Style Spec v8 JSON styles
 * backed by OpenFreeMap's OpenMapTiles vector tiles (`https://tiles.openfreemap.org/planet`).
 *
 * Every theme includes full urban orientation geography:
 * - Clearly colored parks, woods, grass, pitches, playgrounds (`landcover`, `landuse`, `park`)
 * - Clearly colored rivers & streams (`water`, `waterway`: Po, Dora, Stura, Sangone)
 * - Crisp architectural building footprints (`building` fill + outline, smooth zoom fade-in)
 * - Complete road hierarchy (`motorway`, `trunk`, `primary`, `secondary`, `tertiary`, `minor`, `service`, `path`, `rail`)
 * - Calm, legible street names (`transportation_name`), neighborhoods (`place`), and river names (`water_name`)
 * - ZERO retail shops, bakeries, bars, parking clutter, or house numbers (curated Major Landmarks are added separately)
 * - Strictly valid CSS colors (`#RRGGBB` and `rgba(r, g, b, a)`) — never Android `#AARRGGBB`!
 */
internal object TurinVectorStyleBuilder {

    const val VECTOR_SOURCE_ID = "openmaptiles"
    const val SATELLITE_SOURCE_ID = "google_satellite"
    const val TILEJSON_URL = "https://tiles.openfreemap.org/planet"
    const val GLYPHS_URL = "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf"
    val SATELLITE_TILE_URLS = listOf(
        "https://mt0.google.com/vt/lyrs=s&hl=it&x={x}&y={y}&z={z}",
        "https://mt1.google.com/vt/lyrs=s&hl=it&x={x}&y={y}&z={z}",
        "https://mt2.google.com/vt/lyrs=s&hl=it&x={x}&y={y}&z={z}",
        "https://mt3.google.com/vt/lyrs=s&hl=it&x={x}&y={y}&z={z}",
    )

    private data class Palette(
        val background: String,
        val residentialZone: String,
        val industrialZone: String,
        val hospitalZone: String,
        val campusZone: String,
        val stadiumZone: String,
        val parkGreen: String,
        val woodGreen: String,
        val parkOutline: String,
        val waterFill: String,
        val waterwayLine: String,
        val buildingFill: String,
        val buildingOutline: String,
        val railLine: String,
        val pathLine: String,
        val minorCasing: String,
        val minorFill: String,
        val secondaryCasing: String,
        val secondaryFill: String,
        val primaryCasing: String,
        val primaryFill: String,
        val motorwayCasing: String,
        val motorwayFill: String,
        val boundaryLine: String,
        val waterText: String,
        val streetText: String,
        val streetHalo: String,
        val neighborhoodText: String,
        val cityText: String,
        val placeHalo: String,
    )

    private fun paletteFor(mapStyle: MapStyle): Palette = when (mapStyle) {
        MapStyle.DARK -> Palette(
            background = "#121824",
            residentialZone = "#161E2D",
            industrialZone = "#1A2232",
            hospitalZone = "#2B1D2C",
            campusZone = "#1F243B",
            stadiumZone = "#1A3242",
            parkGreen = "#1B5238",
            woodGreen = "#14422C",
            parkOutline = "#2B7451",
            waterFill = "#12426E",
            waterwayLine = "#1D5E96",
            buildingFill = "#253349",
            buildingOutline = "#3D5170",
            railLine = "#3A4963",
            pathLine = "#2B3A52",
            minorCasing = "#0B1018",
            minorFill = "#303F58",
            secondaryCasing = "#0A0F17",
            secondaryFill = "#425677",
            primaryCasing = "#090D14",
            primaryFill = "#546D94",
            motorwayCasing = "#080C12",
            motorwayFill = "#6B87B3",
            boundaryLine = "#36465E",
            waterText = "#78C2F4",
            streetText = "#D2DCEB",
            streetHalo = "rgba(13, 18, 28, 0.92)",
            neighborhoodText = "#C4D1E3",
            cityText = "#EDF3FC",
            placeHalo = "rgba(12, 16, 26, 0.94)",
        )

        MapStyle.POSITRON -> Palette(
            background = "#ECEFF5",
            residentialZone = "#E5E9F1",
            industrialZone = "#E0E5EE",
            hospitalZone = "#F2DCDF",
            campusZone = "#E4E2F2",
            stadiumZone = "#CFE1EE",
            parkGreen = "#BCE3BB",
            woodGreen = "#A8D7A7",
            parkOutline = "#8EC48D",
            waterFill = "#90C8EE",
            waterwayLine = "#74B8E8",
            buildingFill = "#D3DCE8",
            buildingOutline = "#AEBCD0",
            railLine = "#AEBACB",
            pathLine = "#C8D3E1",
            minorCasing = "#C0CBD9",
            minorFill = "#FFFFFF",
            secondaryCasing = "#B0BFD1",
            secondaryFill = "#FFFFFF",
            primaryCasing = "#A4B5C9",
            primaryFill = "#FFF8E7",
            motorwayCasing = "#CFAF6E",
            motorwayFill = "#F8DC9C",
            boundaryLine = "#B5C1D1",
            waterText = "#2B6FA8",
            streetText = "#3B4A5E",
            streetHalo = "rgba(244, 247, 251, 0.94)",
            neighborhoodText = "#46556B",
            cityText = "#1F2C3D",
            placeHalo = "rgba(244, 247, 251, 0.96)",
        )

        MapStyle.VOYAGER -> Palette(
            background = "#F3EFE7",
            residentialZone = "#EDE7DC",
            industrialZone = "#E8E2D7",
            hospitalZone = "#F4DADE",
            campusZone = "#EBE6D5",
            stadiumZone = "#D2E2DC",
            parkGreen = "#BBE0B0",
            woodGreen = "#A3D198",
            parkOutline = "#86BE7B",
            waterFill = "#88C1EB",
            waterwayLine = "#6EB2E4",
            buildingFill = "#DDD3C5",
            buildingOutline = "#BEB09C",
            railLine = "#B5ACA0",
            pathLine = "#D2C9BA",
            minorCasing = "#CFC6B8",
            minorFill = "#FFFFFF",
            secondaryCasing = "#C2B6A3",
            secondaryFill = "#FFFDF5",
            primaryCasing = "#D3B368",
            primaryFill = "#FCE296",
            motorwayCasing = "#D4964A",
            motorwayFill = "#F8C375",
            boundaryLine = "#BFB5A6",
            waterText = "#24669E",
            streetText = "#433E38",
            streetHalo = "rgba(247, 244, 238, 0.94)",
            neighborhoodText = "#595047",
            cityText = "#26211C",
            placeHalo = "rgba(247, 244, 238, 0.96)",
        )

        MapStyle.SATELLITE -> Palette(
            background = "#0B1218",
            residentialZone = "#0B1218",
            industrialZone = "#0B1218",
            hospitalZone = "#0B1218",
            campusZone = "#0B1218",
            stadiumZone = "#0B1218",
            parkGreen = "#1B5238",
            woodGreen = "#14422C",
            parkOutline = "#2B7451",
            waterFill = "#12426E",
            waterwayLine = "rgba(116, 184, 232, 0.35)",
            buildingFill = "rgba(255, 255, 255, 0.03)",
            buildingOutline = "rgba(255, 255, 255, 0.14)",
            railLine = "rgba(220, 230, 242, 0.45)",
            pathLine = "rgba(255, 255, 255, 0.22)",
            minorCasing = "rgba(0, 0, 0, 0.42)",
            minorFill = "rgba(255, 255, 255, 0.18)",
            secondaryCasing = "rgba(0, 0, 0, 0.50)",
            secondaryFill = "rgba(255, 255, 255, 0.26)",
            primaryCasing = "rgba(0, 0, 0, 0.56)",
            primaryFill = "rgba(255, 235, 165, 0.34)",
            motorwayCasing = "rgba(0, 0, 0, 0.65)",
            motorwayFill = "rgba(252, 198, 98, 0.48)",
            boundaryLine = "rgba(255, 255, 255, 0.45)",
            waterText = "#9BD5FF",
            streetText = "#FFFFFF",
            streetHalo = "rgba(8, 12, 18, 0.94)",
            neighborhoodText = "#F3F7FC",
            cityText = "#FFFFFF",
            placeHalo = "rgba(6, 10, 16, 0.96)",
        )
    }

    fun buildStyleJson(mapStyle: MapStyle): String {
        val p = paletteFor(mapStyle)
        val isSatellite = mapStyle == MapStyle.SATELLITE
        val layers = JSONArray()

        // 1. Background canvas
        layers.put(
            JSONObject().apply {
                put("id", "bg")
                put("type", "background")
                put("paint", JSONObject().put("background-color", p.background))
            },
        )

        if (isSatellite) {
            layers.put(
                JSONObject().apply {
                    put("id", "satellite-imagery")
                    put("type", "raster")
                    put("source", SATELLITE_SOURCE_ID)
                    put("minzoom", 0)
                    put("maxzoom", 21)
                    put(
                        "paint",
                        JSONObject()
                            .put("raster-opacity", 1.0)
                            .put("raster-fade-duration", 180)
                            .put("raster-contrast", 0.04)
                            .put("raster-saturation", 0.05),
                    )
                },
            )
        }

        if (!isSatellite) {
            // 2. Urban zoning (residential / industrial / hospitals / universities / stadiums)
            layers.put(
                fillLayer(
                    id = "landuse-residential",
                    sourceLayer = "landuse",
                    filter = inFilter("class", "residential", "suburb", "neighbourhood"),
                    color = p.residentialZone,
                    opacity = 0.72,
                ),
            )
            layers.put(
                fillLayer(
                    id = "landuse-industrial",
                    sourceLayer = "landuse",
                    filter = inFilter("class", "industrial", "commercial", "retail", "railway", "military", "quarry"),
                    color = p.industrialZone,
                    opacity = 0.70,
                ),
            )
            layers.put(
                fillLayer(
                    id = "landuse-hospital",
                    sourceLayer = "landuse",
                    filter = eqFilter("class", "hospital"),
                    color = p.hospitalZone,
                    opacity = 0.85,
                ),
            )
            layers.put(
                fillLayer(
                    id = "landuse-campus",
                    sourceLayer = "landuse",
                    filter = inFilter("class", "university", "college", "school"),
                    color = p.campusZone,
                    opacity = 0.82,
                ),
            )
            layers.put(
                fillLayer(
                    id = "landuse-stadium",
                    sourceLayer = "landuse",
                    filter = eqFilter("class", "stadium"),
                    color = p.stadiumZone,
                    opacity = 0.85,
                ),
            )

            // 3. Parks, green areas, grass, woods & nature reserves — unmistakable urban green
            layers.put(
                fillLayer(
                    id = "landcover-grass",
                    sourceLayer = "landcover",
                    filter = inFilter("class", "grass", "wetland", "farmland"),
                    color = p.parkGreen,
                    opacity = 0.88,
                ),
            )
            layers.put(
                fillLayer(
                    id = "landcover-wood",
                    sourceLayer = "landcover",
                    filter = eqFilter("class", "wood"),
                    color = p.woodGreen,
                    opacity = 0.90,
                ),
            )
            layers.put(
                fillLayer(
                    id = "landuse-green",
                    sourceLayer = "landuse",
                    filter = inFilter(
                        "class",
                        "park",
                        "cemetery",
                        "pitch",
                        "playground",
                        "recreation_ground",
                        "village_green",
                    ),
                    color = p.parkGreen,
                    opacity = 0.90,
                ),
            )
            layers.put(
                fillLayer(
                    id = "park-fill",
                    sourceLayer = "park",
                    filter = null,
                    color = p.parkGreen,
                    opacity = 0.65,
                    outlineColor = p.parkOutline,
                ),
            )

            // 4. Water bodies & waterways (Po, Dora Riparia, Stura, Sangone)
            layers.put(
                fillLayer(
                    id = "water-fill",
                    sourceLayer = "water",
                    filter = null,
                    color = p.waterFill,
                    opacity = 1.0,
                ),
            )
        }
        layers.put(
            JSONObject().apply {
                put("id", "waterway-line")
                put("type", "line")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "waterway")
                put(
                    "layout",
                    JSONObject()
                        .put("line-cap", "round")
                        .put("line-join", "round"),
                )
                put(
                    "paint",
                    JSONObject()
                        .put("line-color", p.waterwayLine)
                        .put("line-width", zoomInterp(10.0 to 1.2, 13.0 to 2.4, 16.0 to 4.2)),
                )
            },
        )

        if (!isSatellite) {
            // 5. Crisp architectural building footprints (palazzi & courtyards)
            layers.put(
                JSONObject().apply {
                    put("id", "buildings")
                    put("type", "fill")
                    put("source", VECTOR_SOURCE_ID)
                    put("source-layer", "building")
                    put("minzoom", 12.2)
                    put(
                        "paint",
                        JSONObject()
                            .put("fill-color", p.buildingFill)
                            .put("fill-outline-color", p.buildingOutline)
                            .put("fill-opacity", zoomInterp(12.2 to 0.0, 13.3 to 0.82, 14.5 to 0.94)),
                    )
                },
            )
        }

        // 6. Subtle administrative borders
        layers.put(
            JSONObject().apply {
                put("id", "boundary-admin")
                put("type", "line")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "boundary")
                put("filter", inFilter("admin_level", 2, 4, 6, 8))
                put(
                    "paint",
                    JSONObject()
                        .put("line-color", p.boundaryLine)
                        .put("line-opacity", 0.45)
                        .put("line-width", 1.0)
                        .put("line-dasharray", JSONArray().put(4).put(3)),
                )
            },
        )

        // 7. Railways & park walkways
        layers.put(
            JSONObject().apply {
                put("id", "railways")
                put("type", "line")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "transportation")
                put("minzoom", 11.0)
                put("filter", inFilter("class", "rail", "transit"))
                put(
                    "paint",
                    JSONObject()
                        .put("line-color", p.railLine)
                        .put("line-opacity", 0.65)
                        .put("line-width", zoomInterp(11.0 to 0.8, 15.0 to 1.8))
                        .put("line-dasharray", JSONArray().put(4).put(3)),
                )
            },
        )
        layers.put(
            JSONObject().apply {
                put("id", "paths-pedestrian")
                put("type", "line")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "transportation")
                put("minzoom", 13.5)
                put("filter", inFilter("class", "path", "track"))
                put(
                    "layout",
                    JSONObject()
                        .put("line-cap", "round")
                        .put("line-join", "round"),
                )
                put(
                    "paint",
                    JSONObject()
                        .put("line-color", p.pathLine)
                        .put("line-opacity", 0.68)
                        .put("line-width", zoomInterp(13.5 to 0.7, 16.5 to 1.8)),
                )
            },
        )

        // 8. Complete Road Hierarchy (casings below cores for crisp intersection joins!)
        // 8a. Minor & service residential streets (visible from z11.5)
        layers.put(
            roadLineLayer(
                id = "road-minor-casing",
                filter = inFilter("class", "minor", "service"),
                minZoom = 11.8,
                color = p.minorCasing,
                widthExpr = zoomInterp(11.8 to 1.6, 13.5 to 3.4, 15.5 to 6.8, 18.0 to 13.5),
            ),
        )
        // 8b. Secondary & tertiary streets
        layers.put(
            roadLineLayer(
                id = "road-secondary-casing",
                filter = inFilter("class", "tertiary", "secondary"),
                minZoom = 10.5,
                color = p.secondaryCasing,
                widthExpr = zoomInterp(10.5 to 2.0, 13.0 to 5.2, 15.5 to 10.2, 18.0 to 18.5),
            ),
        )
        // 8c. Primary arteries (Grandi Corsi)
        layers.put(
            roadLineLayer(
                id = "road-primary-casing",
                filter = eqFilter("class", "primary"),
                minZoom = 9.0,
                color = p.primaryCasing,
                widthExpr = zoomInterp(9.0 to 2.0, 12.0 to 4.8, 15.0 to 11.4, 18.0 to 21.0),
            ),
        )
        // 8d. Motorways & Tangenziale
        layers.put(
            roadLineLayer(
                id = "road-motorway-casing",
                filter = inFilter("class", "motorway", "trunk"),
                minZoom = 8.0,
                color = p.motorwayCasing,
                widthExpr = zoomInterp(8.0 to 2.2, 12.0 to 5.8, 15.0 to 12.8, 18.0 to 23.0),
            ),
        )

        // Road cores (drawn above casings so intersections merge cleanly)
        layers.put(
            roadLineLayer(
                id = "road-minor-fill",
                filter = inFilter("class", "minor", "service"),
                minZoom = 11.5,
                color = p.minorFill,
                widthExpr = zoomInterp(11.5 to 0.9, 13.5 to 2.2, 15.5 to 5.0, 18.0 to 11.0),
            ),
        )
        layers.put(
            roadLineLayer(
                id = "road-secondary-fill",
                filter = inFilter("class", "tertiary", "secondary"),
                minZoom = 10.2,
                color = p.secondaryFill,
                widthExpr = zoomInterp(10.2 to 1.2, 13.0 to 3.6, 15.5 to 8.0, 18.0 to 15.6),
            ),
        )
        layers.put(
            roadLineLayer(
                id = "road-primary-fill",
                filter = eqFilter("class", "primary"),
                minZoom = 8.8,
                color = p.primaryFill,
                widthExpr = zoomInterp(8.8 to 1.3, 12.0 to 3.4, 15.0 to 9.2, 18.0 to 18.0),
            ),
        )
        layers.put(
            roadLineLayer(
                id = "road-motorway-fill",
                filter = inFilter("class", "motorway", "trunk"),
                minZoom = 8.0,
                color = p.motorwayFill,
                widthExpr = zoomInterp(8.0 to 1.5, 12.0 to 4.2, 15.0 to 10.4, 18.0 to 19.8),
            ),
        )

        // 9. Clean, calm cartographic typography (rivers, streets, neighborhoods, cities)
        layers.put(
            JSONObject().apply {
                put("id", "label-water")
                put("type", "symbol")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "water_name")
                put("minzoom", 11.0)
                put(
                    "layout",
                    JSONObject()
                        .put("text-field", nameCoalesceExpr())
                        .put("text-font", JSONArray().put("Noto Sans Regular"))
                        .put("text-size", zoomInterp(11.0 to 10.5, 15.0 to 12.5))
                        .put("symbol-placement", "line")
                        .put("symbol-spacing", 380)
                        .put("text-padding", 8),
                )
                put(
                    "paint",
                    JSONObject()
                        .put("text-color", p.waterText)
                        .put("text-halo-color", p.streetHalo)
                        .put("text-halo-width", 1.5),
                )
            },
        )

        layers.put(
            JSONObject().apply {
                put("id", "label-streets-major")
                put("type", "symbol")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "transportation_name")
                put("minzoom", 12.0)
                put("filter", inFilter("class", "motorway", "trunk", "primary", "secondary", "tertiary"))
                put(
                    "layout",
                    JSONObject()
                        .put("symbol-placement", "line")
                        .put("symbol-spacing", 340)
                        .put("text-field", nameCoalesceExpr())
                        .put("text-font", JSONArray().put("Noto Sans Regular"))
                        .put("text-size", zoomInterp(12.0 to 10.2, 14.5 to 11.8, 17.0 to 13.0))
                        .put("text-padding", 9)
                        .put("text-max-angle", 30),
                )
                put(
                    "paint",
                    JSONObject()
                        .put("text-color", p.streetText)
                        .put("text-halo-color", p.streetHalo)
                        .put("text-halo-width", 1.6),
                )
            },
        )

        layers.put(
            JSONObject().apply {
                put("id", "label-streets-minor")
                put("type", "symbol")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "transportation_name")
                put("minzoom", 13.8)
                put("filter", inFilter("class", "minor", "service", "path"))
                put(
                    "layout",
                    JSONObject()
                        .put("symbol-placement", "line")
                        .put("symbol-spacing", 360)
                        .put("text-field", nameCoalesceExpr())
                        .put("text-font", JSONArray().put("Noto Sans Regular"))
                        .put("text-size", zoomInterp(13.8 to 10.0, 16.5 to 11.6))
                        .put("text-padding", 10)
                        .put("text-max-angle", 30),
                )
                put(
                    "paint",
                    JSONObject()
                        .put("text-color", p.streetText)
                        .put("text-halo-color", p.streetHalo)
                        .put("text-halo-width", 1.5),
                )
            },
        )

        layers.put(
            JSONObject().apply {
                put("id", "label-neighborhoods")
                put("type", "symbol")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "place")
                put("minzoom", 11.2)
                put("maxzoom", 15.8)
                put("filter", inFilter("class", "suburb", "quarter", "neighbourhood"))
                put(
                    "layout",
                    JSONObject()
                        .put("text-field", nameCoalesceExpr())
                        .put("text-font", JSONArray().put("Noto Sans Bold"))
                        .put("text-transform", "uppercase")
                        .put("text-letter-spacing", 0.08)
                        .put("text-size", zoomInterp(11.2 to 10.0, 13.5 to 11.5, 15.5 to 12.2))
                        .put("text-max-width", 8)
                        .put("text-padding", 12),
                )
                put(
                    "paint",
                    JSONObject()
                        .put("text-color", p.neighborhoodText)
                        .put("text-halo-color", p.placeHalo)
                        .put("text-halo-width", 1.8)
                        .put("text-opacity", zoomInterp(11.2 to 0.86, 15.0 to 0.72, 15.8 to 0.0)),
                )
            },
        )

        layers.put(
            JSONObject().apply {
                put("id", "label-cities")
                put("type", "symbol")
                put("source", VECTOR_SOURCE_ID)
                put("source-layer", "place")
                put("minzoom", 8.0)
                put("maxzoom", 14.2)
                put("filter", inFilter("class", "city", "town", "village"))
                put(
                    "layout",
                    JSONObject()
                        .put("text-field", nameCoalesceExpr())
                        .put("text-font", JSONArray().put("Noto Sans Bold"))
                        .put("text-size", zoomInterp(9.0 to 12.0, 12.0 to 14.5))
                        .put("text-max-width", 8)
                        .put("text-padding", 10),
                )
                put(
                    "paint",
                    JSONObject()
                        .put("text-color", p.cityText)
                        .put("text-halo-color", p.placeHalo)
                        .put("text-halo-width", 2.0),
                )
            },
        )

        val sourcesObj = JSONObject().put(
            VECTOR_SOURCE_ID,
            JSONObject()
                .put("type", "vector")
                .put("url", TILEJSON_URL),
        )
        if (isSatellite) {
            val tilesArr = JSONArray()
            SATELLITE_TILE_URLS.forEach { tilesArr.put(it) }
            sourcesObj.put(
                SATELLITE_SOURCE_ID,
                JSONObject()
                    .put("type", "raster")
                    .put("tiles", tilesArr)
                    .put("tileSize", 256)
                    .put("maxzoom", 20),
            )
        }

        val root = JSONObject().apply {
            put("version", 8)
            put("name", "ToBus-${mapStyle.name}")
            put("sources", sourcesObj)
            put("glyphs", GLYPHS_URL)
            put("layers", layers)
        }
        return root.toString()
    }

    private fun fillLayer(
        id: String,
        sourceLayer: String,
        filter: JSONArray?,
        color: String,
        opacity: Double,
        outlineColor: String? = null,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "fill")
        put("source", VECTOR_SOURCE_ID)
        put("source-layer", sourceLayer)
        if (filter != null) put("filter", filter)
        put(
            "paint",
            JSONObject().apply {
                put("fill-color", color)
                put("fill-opacity", opacity)
                if (outlineColor != null) put("fill-outline-color", outlineColor)
            },
        )
    }

    private fun roadLineLayer(
        id: String,
        filter: JSONArray,
        minZoom: Double,
        color: String,
        widthExpr: JSONArray,
    ): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", "line")
        put("source", VECTOR_SOURCE_ID)
        put("source-layer", "transportation")
        put("minzoom", minZoom)
        put("filter", filter)
        put(
            "layout",
            JSONObject()
                .put("line-cap", "round")
                .put("line-join", "round"),
        )
        put(
            "paint",
            JSONObject()
                .put("line-color", color)
                .put("line-width", widthExpr),
        )
    }

    private fun eqFilter(key: String, value: Any): JSONArray = JSONArray()
        .put("==")
        .put(JSONArray().put("get").put(key))
        .put(value)

    private fun inFilter(key: String, vararg values: Any): JSONArray {
        val list = JSONArray()
        values.forEach { list.put(it) }
        return JSONArray()
            .put("in")
            .put(JSONArray().put("get").put(key))
            .put(JSONArray().put("literal").put(list))
    }

    private fun zoomInterp(vararg stops: Pair<Double, Double>): JSONArray {
        val arr = JSONArray()
            .put("interpolate")
            .put(JSONArray().put("linear"))
            .put(JSONArray().put("zoom"))
        stops.forEach { (z, v) ->
            arr.put(z)
            arr.put(v)
        }
        return arr
    }

    private fun nameCoalesceExpr(): JSONArray = JSONArray()
        .put("coalesce")
        .put(JSONArray().put("get").put("name:it"))
        .put(JSONArray().put("get").put("name:latin"))
        .put(JSONArray().put("get").put("name"))
}

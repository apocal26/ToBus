package com.example.widget

/**
 * Dedicated 2x2 Square Home Screen Widget receiver for ToBus.
 * Shares the battery-efficient update & rendering pipeline of BusMapWidgetProvider.
 */
class BusMapWidgetSquareProvider : BusMapWidgetProvider() {
    override val isSquareVariant: Boolean = true
}

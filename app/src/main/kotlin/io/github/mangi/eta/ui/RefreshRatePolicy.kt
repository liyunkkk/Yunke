package io.github.mangi.eta.ui

internal fun highestSupportedRefreshRate(refreshRates: Iterable<Float>): Float {
    var highest = 0f
    refreshRates.forEach { refreshRate ->
        if (refreshRate.isFinite() && refreshRate > highest) {
            highest = refreshRate
        }
    }
    return highest
}

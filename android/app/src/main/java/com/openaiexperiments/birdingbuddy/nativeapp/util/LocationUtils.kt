package com.openaiexperiments.birdingbuddy.nativeapp.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat

data class LatLon(val lat: Double, val lon: Double)

fun hasLocationPermission(context: Context): Boolean {
    val fine =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    val coarse =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    return fine || coarse
}

@SuppressLint("MissingPermission")
fun getLastKnownCoordinates(context: Context): LatLon? {
    if (!hasLocationPermission(context)) {
        return null
    }

    val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null

    val providers =
        locationManager.getProviders(true)
            .ifEmpty { listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER) }

    var bestLocation: Location? = null

    for (provider in providers) {
        val candidate = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
        if (bestLocation == null || candidate.time > bestLocation.time) {
            bestLocation = candidate
        }
    }

    return bestLocation?.let { LatLon(it.latitude, it.longitude) }
}

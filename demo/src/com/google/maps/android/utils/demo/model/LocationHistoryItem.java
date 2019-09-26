package com.google.maps.android.utils.demo.model;

import com.google.android.gms.maps.model.LatLng;

public class LocationHistoryItem {
    public final long timestampMs;
    public final LatLng latLng;
    public LocationHistoryItem(long timestampMs, LatLng latLng) {
        this.timestampMs = timestampMs;
        this.latLng = latLng;
    }
    public LocationHistoryItem(long timestampMs, double latitude, double longitude) {
        this(timestampMs, new LatLng(latitude, longitude));
    }
    public LocationHistoryItem(long timestampMs, long latitudeE7, long longitudeE7) {
        this(timestampMs, latitudeE7 / 10000000d, longitudeE7 / 10000000d);
    }
}

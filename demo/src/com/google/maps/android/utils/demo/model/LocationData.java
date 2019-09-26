package com.google.maps.android.utils.demo.model;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

public class LocationData {
    public List<MediaItem> mMediaItems = new ArrayList<>();
    public TreeMap<Long, LocationHistoryItem> locationHistory = new TreeMap<>();
}

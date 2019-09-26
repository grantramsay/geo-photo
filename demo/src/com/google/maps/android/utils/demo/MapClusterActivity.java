/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.maps.android.utils.demo;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import com.google.android.gms.maps.CameraUpdateFactory;
import com.google.android.gms.maps.GoogleMap;
import com.google.android.gms.maps.model.BitmapDescriptorFactory;
import com.google.android.gms.maps.model.CameraPosition;
import com.google.android.gms.maps.model.LatLng;
import com.google.android.gms.maps.model.LatLngBounds;
import com.google.android.gms.maps.model.Marker;
import com.google.android.gms.maps.model.MarkerOptions;
import com.google.android.gms.maps.model.Polyline;
import com.google.android.gms.maps.model.PolylineOptions;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.maps.android.clustering.Cluster;
import com.google.maps.android.clustering.ClusterItem;
import com.google.maps.android.clustering.ClusterManager;
import com.google.maps.android.clustering.view.DefaultClusterRenderer;
import com.google.maps.android.ui.IconGenerator;
import com.google.maps.android.utils.demo.model.LocationData;
import com.google.maps.android.utils.demo.model.LocationHistoryItem;
import com.google.maps.android.utils.demo.model.MediaItem;
import com.google.maps.android.utils.demo.repository.LocationDataRepository;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.annotation.NonNull;

public class MapClusterActivity extends BaseDemoActivity implements
        ClusterManager.OnClusterClickListener<MediaItem>,
        ClusterManager.OnClusterInfoWindowClickListener<MediaItem>,
        ClusterManager.OnClusterItemClickListener<MediaItem>,
        ClusterManager.OnClusterItemInfoWindowClickListener<MediaItem> {
    public static final String TAG = "map cluster";
    private static final String SHOW_SATELLITE_ID = "show satellite";
    public static final String SHOW_PHOTOS_ID = "show photos";
    public static final String SHOW_TRAVELLED_LINE_ID = "show travelled line";
    private static final String FIRST_LOAD_ID = "first load";
    private static final String MAP_CAMERA_POSITION_ID = "map camera position";

    private ClusterManager<MediaItem> mClusterManager;
    private Polyline mPolyline;
    private ProgressBar mProgressBar;
    private boolean mShowSatellite = false;
    private boolean mShowPhotos = true;
    private boolean mShowTravelledLine = true;
    private boolean mFirstLoad = true;
    private CameraPosition mCameraPosition;
    private Gson gson = new GsonBuilder().serializeNulls().create();
    private final UpdatableToast mToast = new UpdatableToast(this);

    // TODO: Move to own file
    @SuppressWarnings("WeakerAccess")
    public static class UpdatableToast {
        private Context mContext;
        private Toast mToast;

        public UpdatableToast(Context context) {
            mContext = context;
        }

        public void show(String text) {
            show(text, Toast.LENGTH_LONG);
        }

        public void show(String text, int length) {
            stop();
            mToast = Toast.makeText(mContext, text, length);
            mToast.show();
        }

        public void stop() {
            if (mToast != null)
                mToast.cancel();
        }
    }

    /**
     * Draws profile photos inside markers (using IconGenerator).
     * When there are multiple people in the cluster, draw multiple photos (using MultiDrawable).
     */
    private class MediaItemRenderer extends DefaultClusterRenderer<MediaItem> {
        private final IconGenerator mIconGenerator = new IconGenerator(getApplicationContext());
        private final IconGenerator mClusterIconGenerator = new IconGenerator(getApplicationContext());
        private final ImageView mImageView;
        private final ImageView mClusterImageView;
        private final int mDimension;

        public MediaItemRenderer() {
            super(getApplicationContext(), getMap(), mClusterManager);

            View multiProfile = getLayoutInflater().inflate(R.layout.multi_profile, null);
            mClusterIconGenerator.setContentView(multiProfile);
            mClusterImageView = multiProfile.findViewById(R.id.image);

            mImageView = new ImageView(getApplicationContext());
            mDimension = (int) getResources().getDimension(R.dimen.custom_profile_image);
            mImageView.setLayoutParams(new ViewGroup.LayoutParams(mDimension, mDimension));
            int padding = (int) getResources().getDimension(R.dimen.custom_profile_padding);
            mImageView.setPadding(padding, padding, padding, padding);
            mIconGenerator.setContentView(mImageView);
        }

        private Bitmap getThumbnail(MediaItem mediaItem) {
            Bitmap thumbnail;
            if (mediaItem.type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                thumbnail = MediaStore.Images.Thumbnails.getThumbnail(
                        getContentResolver(), mediaItem.mId, MediaStore.Images.Thumbnails.MICRO_KIND,
                        new BitmapFactory.Options());
            }
            else {
                thumbnail = MediaStore.Video.Thumbnails.getThumbnail(
                        getContentResolver(), mediaItem.mId, MediaStore.Video.Thumbnails.MICRO_KIND,
                        new BitmapFactory.Options());
            }
            return thumbnail;
        }

        @Override
        protected void onBeforeClusterItemRendered(MediaItem mediaItem, MarkerOptions markerOptions) {
            // Hide default marker, load single bitmap on background thread and unhide.
            markerOptions.visible(false);
            new LoadClusterItemIconAsync(this, mediaItem).execute();
        }

        @Override
        protected void onBeforeClusterRendered(Cluster<MediaItem> cluster, MarkerOptions markerOptions) {
            // Hide default marker, load multi-bitmap on background thread and unhide.
            markerOptions.visible(false);
            new LoadClusterIconAsync(this, cluster).execute();
        }

        @Override
        protected boolean shouldRenderAsCluster(Cluster cluster) {
            return cluster.getSize() > 1;
        }

        private Resources getResources() {
            return MapClusterActivity.this.getResources();
        }
    }

    private static class LoadClusterIconAsync extends AsyncTask<Void, Void, Void> {
        private WeakReference<MediaItemRenderer> mRenderer;
        private Cluster<MediaItem> cluster;
        private Drawable iconDrawable;

        private LoadClusterIconAsync(MediaItemRenderer renderer, Cluster<MediaItem> cluster) {
            super();
            this.mRenderer = new WeakReference<>(renderer);
            this.cluster = cluster;
        }

        @Override
        protected Void doInBackground(Void... v) {
            MediaItemRenderer renderer = mRenderer.get();
            if (renderer == null)
                return null;
            List<Drawable> drawables = new ArrayList<>();
            for (MediaItem item : cluster.getItems()) {
                Bitmap thumbnail = renderer.getThumbnail(item);
                Drawable drawable = new BitmapDrawable(renderer.getResources(), thumbnail);
                drawable.setBounds(0, 0, renderer.mDimension, renderer.mDimension);
                drawables.add(drawable);
                // Draw 4 at most.
                if (drawables.size() == 4)
                    break;
            }
            iconDrawable = new MultiDrawable(drawables);
            iconDrawable.setBounds(0, 0, renderer.mDimension, renderer.mDimension);
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            super.onPostExecute(v);
            MediaItemRenderer renderer = mRenderer.get();
            if (renderer == null)
                return;
            Marker marker = renderer.getMarker(cluster);
            if (marker != null) {
                renderer.mClusterImageView.setImageDrawable(iconDrawable);
                Bitmap icon = renderer.mClusterIconGenerator.makeIcon(String.valueOf(cluster.getSize()));
                marker.setIcon(BitmapDescriptorFactory.fromBitmap(icon));
                marker.setVisible(true);
            }
        }
    }

    private static class LoadClusterItemIconAsync extends AsyncTask<Void, Void, Void> {
        private WeakReference<MediaItemRenderer> mRenderer;
        private MediaItem mediaItem;
        private Drawable iconDrawable;

        private LoadClusterItemIconAsync(MediaItemRenderer renderer, MediaItem mediaItem) {
            super();
            this.mRenderer = new WeakReference<>(renderer);
            this.mediaItem = mediaItem;
        }

        @Override
        protected Void doInBackground(Void... v) {
            MediaItemRenderer renderer = mRenderer.get();
            if (renderer == null)
                return null;
            Bitmap thumbnail = renderer.getThumbnail(mediaItem);
            iconDrawable = new BitmapDrawable(renderer.getResources(), thumbnail);
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            super.onPostExecute(v);
            MediaItemRenderer renderer = mRenderer.get();
            if (renderer == null)
                return;
            Marker marker = renderer.getMarker(mediaItem);
            if (marker != null) {
                renderer.mImageView.setImageDrawable(iconDrawable);
                Bitmap icon = renderer.mIconGenerator.makeIcon();
                // marker.setTitle(mediaItem.mediaPath);
                marker.setIcon(BitmapDescriptorFactory.fromBitmap(icon));
                marker.setVisible(true);
            }
        }
    }

    @Override
    public boolean onClusterClick(Cluster<MediaItem> cluster) {
        if (cluster.getSize() > 10) {
            // Zoom in the cluster. Need to create LatLngBounds and including all the cluster items
            // inside of bounds, then animate to center of the bounds.

            // Create the builder to collect all essential cluster items for the bounds.
            LatLngBounds.Builder builder = LatLngBounds.builder();
            for (ClusterItem item : cluster.getItems()) {
                builder.include(item.getPosition());
            }
            // Get the LatLngBounds
            final LatLngBounds bounds = builder.build();

            // Animate camera to the bounds
            try {
                getMap().animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 100), 500, null);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        else {
            // Launch activity to show images
            // Sort cluster items to show in correct order
            ArrayList<MediaItem> sortedClusterItems = new ArrayList<>(cluster.getItems());
            Collections.sort(sortedClusterItems, (o1, o2) -> Long.compare(o1.date, o2.date));
            ArrayList<String> mediaPaths = new ArrayList<>();
            for (MediaItem mediaItem : sortedClusterItems) {
                mediaPaths.add(mediaItem.mediaPath);
            }
            MediaViewerActivity.start(this, mediaPaths);
        }
        return true;
    }

    @Override
    public void onClusterInfoWindowClick(Cluster<MediaItem> cluster) {
        // Does nothing, but you could go to a list of the users.
    }

    @Override
    public boolean onClusterItemClick(MediaItem mediaItem) {
        // Launch activity to show image
        ArrayList<String> mediaPaths = new ArrayList<>();
        mediaPaths.add(mediaItem.mediaPath);
        MediaViewerActivity.start(this, mediaPaths);
        return true;
    }

    @Override
    public void onClusterItemInfoWindowClick(MediaItem item) {
        // Does nothing, but you could go into the user's profile page, for example.
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            mShowSatellite = savedInstanceState.getBoolean(SHOW_SATELLITE_ID, false);
            mShowPhotos = savedInstanceState.getBoolean(SHOW_PHOTOS_ID, true);
            mShowTravelledLine = savedInstanceState.getBoolean(SHOW_TRAVELLED_LINE_ID, true);
            mFirstLoad = savedInstanceState.getBoolean(FIRST_LOAD_ID, true);
        }
        SharedPreferences pref = getSharedPreferences(TAG, MODE_PRIVATE);
        String gsonString = pref.getString(MAP_CAMERA_POSITION_ID, null);
        mCameraPosition = gson.fromJson(gsonString, CameraPosition.class);

        mProgressBar = findViewById(R.id.progressBar);
        mProgressBar.setIndeterminate(false);
        mProgressBar.setProgress(0);
        mProgressBar.setVisibility(View.INVISIBLE);

        FloatingActionButton satelliteFab = findViewById(R.id.satelliteFab);
        satelliteFab.setOnClickListener(view -> {
                GoogleMap map = getMap();
                if (map != null) {
                    int mapType = map.getMapType() == GoogleMap.MAP_TYPE_NORMAL ?
                            GoogleMap.MAP_TYPE_SATELLITE : GoogleMap.MAP_TYPE_NORMAL;
                    map.setMapType(mapType);
                }
            }
        );
        FloatingActionButton photoFab = findViewById(R.id.photoFab);
        photoFab.setOnClickListener(view -> {
                mShowPhotos = !mShowPhotos;
                refreshLocationData();
            }
        );
        FloatingActionButton locationFab = findViewById(R.id.locationFab);
        locationFab.setOnClickListener(view -> {
                mShowTravelledLine = !mShowTravelledLine;
                refreshLocationData();
            }
        );

        LocationDataRepository repo = LocationDataRepository.getInstance();
        if (!repo.loadCompleted()) {
            mProgressBar.setVisibility(View.VISIBLE);
            mToast.show("Loading...");
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        mToast.stop();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(SHOW_SATELLITE_ID, mShowSatellite);
        outState.putBoolean(SHOW_PHOTOS_ID, mShowPhotos);
        outState.putBoolean(SHOW_TRAVELLED_LINE_ID, mShowTravelledLine);
        outState.putBoolean(FIRST_LOAD_ID, mFirstLoad);
        if (getMap() != null) {
            SharedPreferences.Editor editor = getSharedPreferences(TAG, MODE_PRIVATE).edit();
            editor.putString(MAP_CAMERA_POSITION_ID, gson.toJson(getMap().getCameraPosition()));
            editor.apply();
        }
    }

    @Override
    protected void startDemo() {
        getMap().moveCamera(CameraUpdateFactory.newLatLngZoom(new LatLng(51.6605, 4.315), 4.5f));

        mClusterManager = new ClusterManager<>(this, getMap());
        mClusterManager.setRenderer(new MediaItemRenderer());
        getMap().setOnCameraIdleListener(mClusterManager);
        getMap().setOnMarkerClickListener(mClusterManager);
        getMap().setOnInfoWindowClickListener(mClusterManager);
        mClusterManager.setOnClusterClickListener(this);
        mClusterManager.setOnClusterInfoWindowClickListener(this);
        mClusterManager.setOnClusterItemClickListener(this);
        mClusterManager.setOnClusterItemInfoWindowClickListener(this);
        int mapType = mShowSatellite ? GoogleMap.MAP_TYPE_SATELLITE : GoogleMap.MAP_TYPE_NORMAL;
        getMap().setMapType(mapType);
        if (mCameraPosition != null)
            getMap().moveCamera(CameraUpdateFactory.newCameraPosition(mCameraPosition));

        LocationDataRepository repo = LocationDataRepository.getInstance();
        repo.getLocationData().observe(this, locationData -> refreshLocationData());
        repo.getProgress().observe(this, progress -> {
                if (progress == null)
                    return;
                mProgressBar.setProgress(progress);
                if (progress < 100)
                    mProgressBar.setVisibility(View.VISIBLE);
                else
                    mProgressBar.setVisibility(View.GONE);
            }
        );
    }

    private void refreshLocationData() {
        LocationDataRepository repo = LocationDataRepository.getInstance();
        LocationData locationData = repo.getLocationData().getValue();
        if (locationData == null)
            return;
        mClusterManager.clearItems();
        if (mShowPhotos) {
            for (MediaItem item : locationData.mMediaItems) {
                mClusterManager.addItem(item);
            }
        }
        mClusterManager.cluster();
        if (mPolyline != null) {
            mPolyline.remove();
        }
        if (mShowTravelledLine) {
            PolylineOptions polyLineOptions = new PolylineOptions();
            polyLineOptions.color(0xFFFF0000);
            polyLineOptions.width(2);
            for (LocationHistoryItem item : locationData.locationHistory.values())
                polyLineOptions.add(item.latLng);
            mPolyline = getMap().addPolyline(polyLineOptions);
        }
        if (mFirstLoad) {
            mToast.show("Displaying " + locationData.mMediaItems.size() + " media items, " +
                            locationData.locationHistory.size() + " location points");
            mFirstLoad = false;
        }
    }
}

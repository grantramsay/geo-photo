package com.google.maps.android.utils.demo.repository;

import android.app.Application;
import android.database.Cursor;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.android.gms.maps.model.LatLng;
import com.google.gson.stream.JsonReader;
import com.google.maps.android.SphericalUtil;
import com.google.maps.android.utils.demo.model.LocationData;
import com.google.maps.android.utils.demo.model.LocationHistoryItem;
import com.google.maps.android.utils.demo.model.MediaItem;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import wseemann.media.FFmpegMediaMetadataRetriever;
import us.fatehi.pointlocation6709.parse.ParserException;
import us.fatehi.pointlocation6709.PointLocation;
import us.fatehi.pointlocation6709.parse.PointLocationParser;

public class LocationDataRepository {
    private static final String TAG = "LocationDataRepo";
    private static LocationDataRepository mSingleton = null;
    private final Application mContext;
    private LoadDataAsync mCurrentTask;
    private MutableLiveData<LocationData> mLocationData = new MutableLiveData<>();
    private MutableLiveData<Integer> mProgress = new MutableLiveData<>();
    private boolean mLoadCompleted = false;

    private LocationDataRepository(@NonNull Application context) {
        mContext = context;
    }

    public static LocationDataRepository getInstance() {
        if (mSingleton == null)
            throw new AssertionError(TAG + " not yet initialised");
        return mSingleton;
    }

    public synchronized static LocationDataRepository init(@NonNull Application context) {
        if (mSingleton == null)
            mSingleton = new LocationDataRepository(context);
//        else
//            throw new AssertionError(TAG + " already initialised");
        return mSingleton;
    }

    public void setDataSource(long startTime, long endTime, @NonNull Uri locationHistoryFile,
                              List<String> selectedFolders) {
        if (mCurrentTask != null) {
            if (mCurrentTask.startTime == startTime &&
                    mCurrentTask.endTime == endTime &&
                    mCurrentTask.locationHistoryFile.toString().equals(locationHistoryFile.toString()) &&
                    mCurrentTask.selectedFolders.equals(selectedFolders)) {
                // Nothing has changed.
                // TODO: Check if locationHistoryFile has been modified? Media and folders could change as well..
                return;
            }
            mCurrentTask.cancel(false);
        }
        mLocationData.setValue(null);
        mProgress.setValue(0);
        mLoadCompleted = false;

        mCurrentTask = new LoadDataAsync(this, startTime, endTime,
                locationHistoryFile, new ArrayList<>(selectedFolders));
        mCurrentTask.execute();
    }

    public LiveData<LocationData> getLocationData() {
        return mLocationData;
    }

    public LiveData<Integer> getProgress() {
        return mProgress;
    }

    public boolean loadCompleted() {
        return mLoadCompleted;
    }

    public List<String> getFolderList() {
        List<String> folders = new ArrayList<>();
        Uri external = MediaStore.Files.getContentUri("external");
        String[] projection = { "Distinct " + MediaStore.Images.Media.BUCKET_DISPLAY_NAME };
        String selection = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" +
                MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + " OR " +
                MediaStore.Files.FileColumns.MEDIA_TYPE + "=" +
                MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
        String order = MediaStore.Images.Media.BUCKET_DISPLAY_NAME;
        try (Cursor cur = mContext.getContentResolver().query(
                external, projection, selection, null, order)) {
            if (cur == null || !cur.moveToFirst())
                return folders;
            int bucketColumn = cur.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
            do {
                String bucket = cur.getString(bucketColumn);
                if (bucket == null)
                    continue;
                String logTxt = " bucket: " + bucket;
                // TODO: Dunno where these "IMG_" buckets are coming from, just ignore for now..
                if (bucket.startsWith("IMG_"))
                    logTxt += " - Ignored";
                else
                    folders.add(bucket);
                Log.i(TAG, logTxt);
            } while (cur.moveToNext());
        }
        return folders;
    }

    private static class LoadDataAsync extends AsyncTask<Void, Integer, Void> {
        private final WeakReference<LocationDataRepository> repository;
        private final long startTime;
        private final long endTime;
        private final Uri locationHistoryFile;
        private final List<String> selectedFolders;
        private static final int minLocationAccuracy = 100;
        private static final int maxMediaItems = 1000;
        private TreeMap<Long, LocationHistoryItem> locationHistory = new TreeMap<>();
        private List<MediaItem> mediaItems = new ArrayList<>();
        private List<MediaItem> mediaItemsNoLocation = new ArrayList<>();

        private LoadDataAsync(LocationDataRepository repo,
                              long startTime, long endTime, Uri locationHistoryFile,
                              List<String> selectedFolders) {
            super();
            this.repository = new WeakReference<>(repo);
            this.startTime = startTime;
            this.endTime = endTime;
            this.locationHistoryFile = locationHistoryFile;
            this.selectedFolders = selectedFolders;
        }

        @Override
        protected Void doInBackground(Void... v) {
            // TODO: Weighted progress, so each sub-task sets 0 -> 100 and overall progress determined from that
            // TODO: Move sub-tasks (especially getLocationHistory()) somewhere else, they should be reusable
            Runnable[] subTasks = {
                this::getLocationHistory,
                this::getMedia,
                this::interpolateMediaLocations};

            for (Runnable task : subTasks) {
                if (isCancelled())
                    return null;
                task.run();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            super.onPostExecute(v);
            LocationData locationData = new LocationData();
            locationData.mMediaItems = mediaItems;
            locationData.locationHistory = locationHistory;
            LocationDataRepository repo = repository.get();
            if (repo != null) {
                repo.mLocationData.setValue(locationData);
                repo.mLoadCompleted = true;
            }
        }

        @Override
        protected void onProgressUpdate(Integer... values) {
            LocationDataRepository repo = repository.get();
            if (repo != null && !isCancelled())
                repo.mProgress.setValue(values[0]);
        }

        private void getLocationHistory() {
            // Extract location history info from google takeout if available.
            publishProgress(2);

            LocationDataRepository repo = repository.get();
            if (repo == null || locationHistoryFile == null || locationHistoryFile.toString().isEmpty())
                return;

            Log.i(TAG, "Attempting to read \"Location History.json\"");

            try (InputStream stream = repo.mContext.getContentResolver().openInputStream(locationHistoryFile);
                 JsonReader reader = new JsonReader(new InputStreamReader(stream, Charset.forName("UTF-8")))) {
                if (stream == null)
                    return;

                // TODO: Available isn't really correct here, but works for now..
                long availableEstimate = stream.available();
                int progressIndex = 0;

                reader.beginObject();
                reader.nextName(); // "locations"
                reader.beginArray();
                while (reader.hasNext()) {
                    // TODO: This is faster than gson.fromJson(...), but still not really fast enough...
                    reader.beginObject();
                    reader.nextName(); // "timestampMs"
                    long timestamp = reader.nextLong();
                    if (timestamp >= startTime && timestamp <= endTime) {
                        reader.nextName(); // "latitudeE7"
                        long lat = reader.nextLong();
                        reader.nextName(); // "longitudeE7"
                        long lng = reader.nextLong();
                        reader.nextName(); // "accuracy"
                        int accuracy = reader.nextInt();
                        if (accuracy <= minLocationAccuracy)
                            locationHistory.put(timestamp, new LocationHistoryItem(timestamp, lat, lng));
                    }
                    if (++progressIndex == 1000) {
                        progressIndex = 0;
                        // 2 -> 70% progress
                        int progress = Math.min(2 + (int)((availableEstimate - stream.available()) * 68L / availableEstimate), 70);
                        publishProgress(progress);
                        if (isCancelled())
                            break;
                    }
                    while (reader.hasNext())
                        reader.skipValue();
                    reader.endObject();
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to read \"Location History.json\": " + e.toString());
            }
            publishProgress(70);
        }

        private void getMedia() {
            // NOTE: Date added is used for time stamping rather than date taken, since Snapchat doesn't
            // seem to add that to the media metadata. Also trying to combine these is difficult as date
            // taken is in milliseconds and date added is in seconds...

            // Location metadata is not available anymore via MediaStore.Images.Media.LATITUDE/LONGITUDE.
            // Bit hacky to use "MediaStore.Images.Media." here, but video files have these same fields.
            LocationDataRepository repo = repository.get();
            if (repo == null)
                return;

            String[] projection = {
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
                    MediaStore.Images.Media.DATE_TAKEN,
                    MediaStore.Images.Media.DATE_ADDED,
                    MediaStore.Files.FileColumns.DATA,
                    MediaStore.Files.FileColumns.MEDIA_TYPE
            };
            Uri external = MediaStore.Files.getContentUri("external");
            String selection = "(" + MediaStore.Files.FileColumns.MEDIA_TYPE + "=" +
                    MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE + " OR " +
                    MediaStore.Files.FileColumns.MEDIA_TYPE + "=" +
                    MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO + ")";
            List<String> selectedFoldersFmt = new ArrayList<>();
            for (String folder : selectedFolders)
                selectedFoldersFmt.add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME + "=\"" + folder + "\"");
            if (selectedFoldersFmt.size() > 0)
                selection += " AND (" + TextUtils.join(" OR ", selectedFoldersFmt) + ")";
            // Date added is in seconds rather than milliseconds
            selection += " AND (" + MediaStore.Images.Media.DATE_ADDED + ">=" + startTime / 1000 +
                    " AND " + MediaStore.Images.Media.DATE_ADDED + "<=" + endTime / 1000 + ")";
            try (Cursor cur = repo.mContext.getContentResolver().query(external,
                    projection, selection, null,
                    MediaStore.Images.Media.DATE_ADDED + " DESC")) {
                if (cur == null || !cur.moveToFirst())
                    return;
//                Log.i(TAG, "media query count=" + cur.getCount());
                int idColumn = cur.getColumnIndex(MediaStore.Files.FileColumns._ID);
                int bucketColumn = cur.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                int dateColumn = cur.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN);
                int dateAddedColumn = cur.getColumnIndex(MediaStore.Images.Media.DATE_ADDED);
                int dataColumn = cur.getColumnIndex(MediaStore.Files.FileColumns.DATA);
                int typeColumn = cur.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE);
                int mediaItemsLoaded = 0;
                int totalMediaItems = cur.getCount();
                int progress = 0;
                do {
                    long id = cur.getLong(idColumn);
                    String bucket = cur.getString(bucketColumn);
                    String dateStr = cur.getString(dateColumn);
                    String dateAddedStr = cur.getString(dateAddedColumn);
                    String data = cur.getString(dataColumn);
                    int type = cur.getInt(typeColumn);
                    float[] latLong = {0, 0};
                    boolean hasLatLng = false;

                    // Can't find a metadata interface that works for images and video so they use
                    // ExifInterface and FFmpegMediaMetadataRetriever respectively.
                    if (type == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE) {
                        ExifInterface exifInterface;
                        try {
                            exifInterface = new ExifInterface(data);
                            hasLatLng = exifInterface.getLatLong(latLong);
                        } catch (IOException e) {
                            Log.w(TAG, "EXIF load failed: " + e.toString());
                        }
                    }
                    // TODO: Use (FFmpeg)MediaMetadataRetriever to get video location data as well
                    // TODO: Re-enable this after open source regex compile error fixed.
//                    else {
//                        try {
//                            FFmpegMediaMetadataRetriever metaData = new FFmpegMediaMetadataRetriever();
//                            metaData.setDataSource(data);
//                            String locationStr = metaData.extractMetadata("location");
//                            metaData.release();
//                            // Format is ISO 6709, e.g. "+49.2470+006.8340/", se open source parser.
//                            if (locationStr != null) {
//                                PointLocation pl = PointLocationParser.parsePointLocation(locationStr);
//                                latLong[0] = (float) pl.getLatitude().getDegrees();
//                                latLong[1] = (float) pl.getLatitude().getDegrees();
//                                hasLatLng = true;
//                            }
//                        } catch (ParserException e) {
//                            Log.w(TAG, "Metadata load failed: " + e.toString());
//                        }
//                    }

//                    Log.d(TAG, "bucket=" + bucket + ", data=" + data +
//                            ", date_taken=" + dateStr + ", date_added=" + dateAddedStr +
//                            ", gps=" + hasLatLng + ", lat=" + latLong[0] + ", lng=" + latLong[1] +
//                            ", type=" + type);

                    long date;
                    if (dateStr != null) {
                        date = Long.decode(dateStr);
                    } else if (dateAddedStr != null) {
                        // dateAdded is seconds rather than ms
                        date = Long.decode(dateAddedStr) * 1000L;
                    } else {
                        Log.i(TAG, "Media with no date info: " + bucket + " - " + data);
                        continue;
                    }
                    LatLng latLng = new LatLng(latLong[0], latLong[1]);
                    MediaItem mediaItem = new MediaItem(latLng, data, id, date, type);
                    if (hasLatLng) {
                        mediaItems.add(mediaItem);
                        locationHistory.put(date, new LocationHistoryItem(date, latLng));
                    }
                    else {
                        mediaItemsNoLocation.add(mediaItem);
                    }
                    // Limit the number of media items for performance.
                    if (++mediaItemsLoaded >= maxMediaItems)
                        break;

                    // 70 -> 98% progress
                    int newProgress = 70 + mediaItemsLoaded * 28 / Math.min(totalMediaItems, maxMediaItems);
                    if (newProgress > progress) {
                        progress = newProgress;
                        publishProgress(progress);
                        if (isCancelled())
                            break;
                    }
                } while (cur.moveToNext());
            }
            publishProgress(98);
        }

        private void interpolateMediaLocations() {
            for (MediaItem m : mediaItemsNoLocation) {
                Map.Entry<Long, LocationHistoryItem> floorEntry = locationHistory.floorEntry(m.date);
                Map.Entry<Long, LocationHistoryItem> ceilingEntry = locationHistory.ceilingEntry(m.date);
                if (floorEntry == null && ceilingEntry == null)
                    continue;
                else if (floorEntry == null)
                    floorEntry = ceilingEntry;
                else if (ceilingEntry == null)
                    ceilingEntry = floorEntry;
                LocationHistoryItem floorItem = floorEntry.getValue();
                LocationHistoryItem ceilingItem = ceilingEntry.getValue();
                double fraction = 0.5d;
                // Check for divide by 0.
                if (ceilingItem.timestampMs != floorItem.timestampMs)
                    fraction = (double) (m.date - floorItem.timestampMs) /
                            (ceilingItem.timestampMs - floorItem.timestampMs);
                LatLng interpolatedLatLng = SphericalUtil.interpolate(floorItem.latLng, ceilingItem.latLng, fraction);
                MediaItem updatedMediaItem = new MediaItem(interpolatedLatLng, m.mediaPath, m.mId, m.date, m.type);
                mediaItems.add(updatedMediaItem);
            }
            publishProgress(100);
        }
    }
}

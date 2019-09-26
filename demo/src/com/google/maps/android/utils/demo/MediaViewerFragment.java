/*
 * Copyright (C) 2016 Ronald Martin <hello@itsronald.com>
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
 *
 * Last modified 10/12/16 11:22 PM.
 */

package com.google.maps.android.utils.demo;

import android.graphics.Bitmap;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.VideoView;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.view.Display;

import java.lang.ref.WeakReference;
import java.net.URLConnection;

import com.github.chrisbanes.photoview.PhotoView;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;

public class MediaViewerFragment extends Fragment {
    private static final String MEDIA_PATH_ID = "media_path";
    private static final String PLAYBACK_TIME = "play_time";
    private String mediaPath;
    private PhotoView imageView;
    private PlayerView videoView;
    private Point displaySize;
    private long mCurrentPosition = 0;

    public MediaViewerFragment() {
    }

    public static MediaViewerFragment newInstance(@NonNull final String mediaPath) {
        MediaViewerFragment fragment = new MediaViewerFragment();

        Bundle args = new Bundle();
        args.putString(MEDIA_PATH_ID, mediaPath);
        fragment.setArguments(args);

        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mediaPath = getArguments().getString(MEDIA_PATH_ID);
        }
        if (savedInstanceState != null) {
            mCurrentPosition = savedInstanceState.getLong(PLAYBACK_TIME);
        }
    }

    @Override
    public void onDestroy() {
        if (videoView != null)
            videoView.getPlayer().release();
        super.onDestroy();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (videoView != null)
            videoView.getPlayer().setPlayWhenReady(getUserVisibleHint());
    }

    @Override
    public void onPause() {
        super.onPause();
        if (videoView != null) {
            mCurrentPosition = videoView.getPlayer().getCurrentPosition();
            videoView.getPlayer().setPlayWhenReady(false);
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putLong(PLAYBACK_TIME, mCurrentPosition);
    }

    @Override
    public void setUserVisibleHint(boolean isVisibleToUser) {
        super.setUserVisibleHint(isVisibleToUser);
        if (videoView != null)
            videoView.getPlayer().setPlayWhenReady(isVisibleToUser);
    }

    private static int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculate the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight
                    && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.image_viewer_fragment, container, false);
        imageView = view.findViewById(R.id.image_view);
        videoView = view.findViewById(R.id.video_view);

        String mimeType = URLConnection.guessContentTypeFromName(mediaPath);

        if (mimeType != null) {
            if (mimeType.startsWith("image")) {
                // Hide video view
                videoView.setVisibility(View.GONE);
                videoView = null;
                // This can crash for very large images...
                // imageView.setImageURI(Uri.parse(mediaPath));

                // Instead load async and scale down images if necessary.
                // TODO: But now loosing resolution for larger images when zooming...
                // Just uses display size for image width/height.
                Display display = getActivity().getWindowManager().getDefaultDisplay();
                displaySize = new Point();
                display.getSize(displaySize);
                // Load image asynchronously for noticeably better UI performance.
                new LoadImageAsync(this).execute();
            } else if (mimeType.startsWith("video")) {
                // Hide image view
                imageView.setVisibility(View.GONE);
                imageView = null;

                SimpleExoPlayer player = ExoPlayerFactory.newSimpleInstance(getActivity());
                videoView.setPlayer(player);

                DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(getActivity(),
                        Util.getUserAgent(getActivity(), getString(R.string.app_name)));
                MediaSource videoSource = new ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(Uri.parse(mediaPath));
                player.setRepeatMode(Player.REPEAT_MODE_ONE);
                player.prepare(videoSource);

                if (mCurrentPosition > 0) {
                    player.seekTo(mCurrentPosition);
                }
                // Fragments are often created before they're visible for fast loading.
                // Those videos will started in setUserVisibleHint.
//                boolean visible = isVisible();
                boolean visible = getUserVisibleHint();
                player.setPlayWhenReady(visible);
            }
        }
        return view;
    }

    private static class LoadImageAsync extends AsyncTask<Void, Void, Void> {
        WeakReference<MediaViewerFragment> fragment;
        Bitmap bitmap;

        private LoadImageAsync(MediaViewerFragment frag) {
            fragment = new WeakReference<>(frag);
        }

        @Override
        protected Void doInBackground(Void... v) {
            MediaViewerFragment frag = fragment.get();
            if (frag == null)
                return null;
            // First decode with inJustDecodeBounds=true to check dimensions
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(frag.mediaPath, options);

            // Calculate inSampleSize (just uses display size for image width/height)
            options.inSampleSize = calculateInSampleSize(options,
                    frag.displaySize.x, frag.displaySize.y);

            // Decode bitmap with inSampleSize set
            options.inJustDecodeBounds = false;
            bitmap = BitmapFactory.decodeFile(frag.mediaPath, options);
            return null;
        }

        @Override
        protected void onPostExecute(Void v) {
            super.onPostExecute(v);
            MediaViewerFragment frag = fragment.get();
            if (frag != null && bitmap != null) {
                frag.imageView.setImageBitmap(bitmap);
            }
        }
    }
}
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
 * Last modified 10/13/16 2:53 PM.
 */

package com.google.maps.android.utils.demo;

import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentPagerAdapter;
import androidx.viewpager.widget.ViewPager;
import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.content.Context;
import com.itsronald.widget.ViewPagerIndicator;

import java.util.ArrayList;

public class MediaViewerActivity extends AppCompatActivity {
    private static final String IMAGE_PATHS_ID = "param1";

    /**
     * Use this factory method to create a new instance of
     * this activity using the provided parameters.
     */
    public static void start(Context context, final ArrayList<String> mediaPaths) {
        Intent intent = new Intent(context, MediaViewerActivity.class);
        intent.putExtra(IMAGE_PATHS_ID, mediaPaths);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.image_viewer);

        Intent intent = this.getIntent();
        ArrayList<String> mediaPaths = intent.getStringArrayListExtra(IMAGE_PATHS_ID);

        final ViewPager viewPager = findViewById(R.id.view_pager);
        viewPager.setAdapter(new PagerAdapter(getSupportFragmentManager(), mediaPaths));

        final ViewPager.LayoutParams layoutParams = new ViewPager.LayoutParams();
        layoutParams.width = ViewPager.LayoutParams.MATCH_PARENT;
        layoutParams.height = ViewPager.LayoutParams.WRAP_CONTENT;
        layoutParams.gravity = Gravity.BOTTOM;

        final ViewPagerIndicator indicator = new ViewPagerIndicator(this);
        viewPager.addView(indicator, layoutParams);
    }

    private class PagerAdapter extends FragmentPagerAdapter {
        final ArrayList<String> mMediaPaths;

        private PagerAdapter(FragmentManager fragmentManager, final ArrayList<String> mediaPaths) {
            super(fragmentManager);
            mMediaPaths = mediaPaths;
        }

        @Override
        public int getCount() {
            return mMediaPaths.size();
        }

        @Override
        @NonNull
        public Fragment getItem(int position) {
            return MediaViewerFragment.newInstance(mMediaPaths.get(position));
        }
    }
}
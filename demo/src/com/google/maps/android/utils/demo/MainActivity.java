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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.app.DatePickerDialog;
import android.widget.Button;
import android.widget.TextView;
import android.util.Pair;
import android.net.Uri;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.google.maps.android.utils.demo.repository.LocationDataRepository;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends Activity {
    public static final String TAG = "MainActivity";
    public static final int PICKFILE_RESULT_CODE = 1;
    public static final int READ_EXTERNAL_STORAGE_SHOW_CLUSTER_VIEW_REQUEST = 1;
    public static final int READ_EXTERNAL_STORAGE_SELECT_FOLDERS_REQUEST = 2;
    public static final String START_DATE_ID = "start date";
    public static final String END_DATE_ID = "end date";
    public static final String LOCATION_HISTORY_FILE_ID = "location history file";
    public static final String SELECTED_FOLDERS_ID = "selected folders";

    private Calendar startDateCalendar = Calendar.getInstance();
    private Calendar endDateCalendar = Calendar.getInstance();
    private TextInputEditText locationHistoryExitText;
    private LocationDataRepository mLocationDataRepository;
    private TextInputEditText mSelectedFoldersExitText;
    private List<String> mSelectedFolders = new ArrayList<>(Arrays.asList(
            "Camera", "Pictures", "Snapchat"));
    private List<String> mAllFolders;
    private Gson gson = new GsonBuilder().serializeNulls().create();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mLocationDataRepository = LocationDataRepository.init(getApplication());

        setContentView(R.layout.main);

        TextInputEditText startDateEditText = findViewById(R.id.StartDate);
        TextInputEditText endDateEditText = findViewById(R.id.EndDate);

        SharedPreferences pref = getSharedPreferences(TAG, MODE_PRIVATE);

        startDateCalendar.add(Calendar.MONTH, -6);
        startDateCalendar.setTimeInMillis(pref.getLong(START_DATE_ID, startDateCalendar.getTimeInMillis()));
        endDateCalendar.setTimeInMillis(pref.getLong(END_DATE_ID, endDateCalendar.getTimeInMillis()));
        for (Pair<TextInputEditText, Calendar> calendarTextPair : Arrays.asList(
                Pair.create(startDateEditText, startDateCalendar),
                Pair.create(endDateEditText, endDateCalendar))) {
            final TextInputEditText editText = calendarTextPair.first;
            final Calendar calendar = calendarTextPair.second;
            updateDateLabel(editText, calendar.getTime());

            final DatePickerDialog.OnDateSetListener onDateSetListener =
                (view, year, monthOfYear, dayOfMonth) -> {
                    calendar.set(year, monthOfYear, dayOfMonth);
                    updateDateLabel(editText, calendar.getTime());
                    onLocationDataParamsUpdated();
                };

            editText.setOnClickListener(view ->
                new DatePickerDialog(MainActivity.this, onDateSetListener,
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH)).show());
        }

        mSelectedFoldersExitText = findViewById(R.id.SelectedFolders);
        mSelectedFoldersExitText.setOnClickListener(view -> showSelectFoldersDialog());

        locationHistoryExitText = findViewById(R.id.LocationHistory);
        locationHistoryExitText.setText(pref.getString(LOCATION_HISTORY_FILE_ID, ""));
        locationHistoryExitText.setOnClickListener(view -> {
                Intent intent;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                } else {
                    intent = new Intent(Intent.ACTION_GET_CONTENT);
                }
                intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setType("application/json");
                // intent.setComponent("Downloads");
                intent = Intent.createChooser(intent, "Choose location history json file");
                startActivityForResult(intent, PICKFILE_RESULT_CODE);
            }
        );
        TextInputLayout locationHistoryTextInputLayout = findViewById(R.id.LocationHistoryTextInputLayout);
        locationHistoryTextInputLayout.setEndIconOnClickListener(view -> {
                locationHistoryExitText.setText("");
                onLocationDataParamsUpdated();
            }
        );

        Button viewButton = findViewById(R.id.ViewButton);
        viewButton.setOnClickListener(view -> startClusterView());

        String gsonString = pref.getString(SELECTED_FOLDERS_ID, gson.toJson(mSelectedFolders));
        mSelectedFolders = gson.fromJson(gsonString, new TypeToken<ArrayList<String>>(){}.getType());

        onLocationDataParamsUpdated();
    }

    private boolean storagePermissionGranted() {
        int result = ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_EXTERNAL_STORAGE);
        return (result == PackageManager.PERMISSION_GRANTED);
    }

    private void onLocationDataParamsUpdated() {
        if (storagePermissionGranted()) {
            if (mAllFolders == null) {
                // Init selected folders list
                mAllFolders = mLocationDataRepository.getFolderList();
                mSelectedFolders.retainAll(mAllFolders);
                mSelectedFoldersExitText.setText(TextUtils.join(", ", mSelectedFolders));
            }

            Editable e = locationHistoryExitText.getText();
            Uri locationFileUri = Uri.parse(e != null ? e.toString() : "");
            mLocationDataRepository.setDataSource(
                    startDateCalendar.getTimeInMillis(),
                    endDateCalendar.getTimeInMillis(),
                    locationFileUri, mSelectedFolders);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        SharedPreferences.Editor editor = getSharedPreferences(TAG, MODE_PRIVATE).edit();
        editor.putLong(START_DATE_ID, startDateCalendar.getTimeInMillis());
        editor.putLong(END_DATE_ID, endDateCalendar.getTimeInMillis());
        Editable e = locationHistoryExitText.getText();
        editor.putString(LOCATION_HISTORY_FILE_ID, e != null ? e.toString() : "");
        editor.putString(SELECTED_FOLDERS_ID, gson.toJson(mSelectedFolders));
        editor.apply();
    }

    private void startClusterView() {
        if (storagePermissionGranted()) {
            startActivity(new Intent(this, MapClusterActivity.class));
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                    READ_EXTERNAL_STORAGE_SHOW_CLUSTER_VIEW_REQUEST);
        }
    }

    private void showSelectFoldersDialog()
    {
        if (storagePermissionGranted()) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this);
            builder.setTitle("Select Folders");
            final String[] items = mAllFolders.toArray(new String[] {});
            final boolean[] checkedItems = new boolean[items.length];
            for (String folder : mSelectedFolders)
                checkedItems[mAllFolders.indexOf(folder)] = true;
            builder.setMultiChoiceItems(items, checkedItems,
                    (dialog, which, isChecked) -> checkedItems[which] = isChecked);
            String positiveText = getString(android.R.string.ok);
            builder.setPositiveButton(positiveText,
                    (dialog, which) -> {
                            mSelectedFolders.clear();
                            for(int i = 0; i < mAllFolders.size(); i++)
                                if (checkedItems[i])
                                    mSelectedFolders.add(mAllFolders.get(i));
                            mSelectedFoldersExitText.setText(TextUtils.join(", ", mSelectedFolders));
                            onLocationDataParamsUpdated();
                            dialog.dismiss();
            });
            String negativeText = getString(android.R.string.cancel);
            builder.setNegativeButton(negativeText,
                    (dialog, which) -> dialog.dismiss());
            AlertDialog dialog = builder.create();
            dialog.show();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[] { Manifest.permission.READ_EXTERNAL_STORAGE },
                    READ_EXTERNAL_STORAGE_SELECT_FOLDERS_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NotNull String[] permissions,
                                           @NotNull int[] grantResults) {
        if (requestCode == READ_EXTERNAL_STORAGE_SHOW_CLUSTER_VIEW_REQUEST ||
                requestCode == READ_EXTERNAL_STORAGE_SELECT_FOLDERS_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                onLocationDataParamsUpdated();
                if (requestCode == READ_EXTERNAL_STORAGE_SHOW_CLUSTER_VIEW_REQUEST)
                    startClusterView();
                else
                    showSelectFoldersDialog();
            } else {
                Toast.makeText(this,
                        "External storage permission is required to view photos",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void updateDateLabel(TextView tv, Date date) {
        DateFormat df = DateFormat.getDateInstance();
        tv.setText(df.format(date));
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICKFILE_RESULT_CODE) {
            if (resultCode == -1) {
                Uri locationFileUri = data.getData();
                if (locationFileUri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                    int takeFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                    getContentResolver().takePersistableUriPermission(locationFileUri, takeFlags);
                }
                locationHistoryExitText.setText(locationFileUri != null ?
                        locationFileUri.toString() : "");
                onLocationDataParamsUpdated();
            }
        }
    }
}

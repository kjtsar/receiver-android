/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.app;

import android.Manifest;

import androidx.appcompat.app.AlertDialog;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentTransaction;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;
import com.google.android.material.snackbar.Snackbar;

import org.opendroneid.android.Constants;
import org.opendroneid.android.R;
import org.opendroneid.android.bluetooth.BluetoothScanner;
import org.opendroneid.android.data.CaltopoClient;
import org.opendroneid.android.data.CtDroneSpec;
import org.opendroneid.android.data.WaypointTrack;
import org.opendroneid.android.log.LogWriter;
import org.opendroneid.android.bluetooth.OpenDroneIdDataManager;
import org.opendroneid.android.data.AircraftObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Set;

public class DebugActivity extends AppCompatActivity {

    private AircraftViewModel mModel;
    OpenDroneIdDataManager dataManager;

    public LocationRequest locationRequest;
    public LocationCallback locationCallback;
    public FusedLocationProviderClient mFusedLocationClient;

    private static final String TAG = DebugActivity.class.getSimpleName();

    public static final String SHARED_PREF_NAME = "DebugActivity";
    public static final String SHARED_PREF_ENABLE_LOG = "EnableLog";

    private static boolean RestartingFlag = false;

    private MenuItem mMenuLogItem;


//    private AircraftMapView mMapView;

    private static File loggerFile;
    private static LogWriter logger;

    private Handler handler;
    private Runnable runnableCode;
    public OpenDroneIdDataManager getDataManager() {return dataManager;}
    public LogWriter getLogger() {return logger;}
    private static DebugActivity appActivity = null;
    public static DebugActivity getDebugActivity() {return appActivity;}
    public static Context getAppContext() {
        return (null != appActivity) ? appActivity.getApplicationContext() : null;
    }
    private boolean initializedCalled;
    private ArrayList <String>outstandingPermissionsList = new ArrayList<>();

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        mMenuLogItem = menu.findItem(R.id.menu_log);
        mMenuLogItem.setChecked(getLogEnabled());
        /* When the flag org.gradle.project.map in gradle.properties is defined to google_map,
           the below code needs to be uncommented:
        if (BuildConfig.USE_GOOGLE_MAPS) {
            menu.findItem(R.id.maptypeHYBRID).setChecked(true); // Configured in AircraftMapView.setMapSettings()
        }*/
        checkBluetoothSupport(menu);
        checkNaNSupport(menu);
        checkWiFiSupport(menu);
        return true;
    }

    private void checkBluetoothSupport(Menu menu) {
        BluetoothAdapter bluetoothAdapter = BluetoothScanner.getBluetoothAdapter(this);
        if (null == bluetoothAdapter) return;

        if (bluetoothAdapter.isLeCodedPhySupported()) {
            menu.findItem(R.id.coded_phy).setTitle(getString(R.string.coded_phy_supported));
        }
        if (bluetoothAdapter.isLeExtendedAdvertisingSupported()) {
            menu.findItem(R.id.extended_advertising).setTitle(getString(R.string.ea_supported));
        }
    }

    private void checkNaNSupport(Menu menu) {
        if (getPackageManager().hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)) {
            menu.findItem(R.id.wifi_nan).setTitle(getString(R.string.nan_supported));
        }
    }

    private void checkWiFiSupport(Menu menu) {
        menu.findItem(R.id.wifi_beacon_scan).setTitle(getString(R.string.wifi_beacon_scan_supported));
    }

    private void showHelpMenu() {
        HelpMenu helpMenu = HelpMenu.newInstance();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        helpMenu.show(transaction, getString(R.string.Help));
    }

    private void showCaltopoConfigPanel() {
        CaltopoSettings configPanel = CaltopoSettings.newInstance();
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        configPanel.show(transaction, "CaltopoSettings");
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            dataManager.getAircraft().clear();
            mModel.setAllAircraft(dataManager.getAircraft());
            LogWriter.bumpSession();
            return true;
        } else if (id == R.id.help) {
            showHelpMenu();
            return true;
        } else if (id == R.id.menu_log) {
            boolean enabled = !getLogEnabled();
            setLogEnabled(enabled);
            mMenuLogItem.setChecked(enabled);
            if (enabled) {
                createNewLogfile();
            } else {
                if (logger != null)
                    logger.close();
            }
            return true;
        } else if (id == R.id.log_location) {
            String message;
            if (getLogEnabled())
                message = getString(R.string.Logging_to) + loggerFile;
            else
                message = getString(R.string.Logging_not_activated);
            showToast(message);
            return true;
        } else if (id == R.id.caltopo) {
            showCaltopoConfigPanel();
        } else if (id == R.id.caltopoConfig) {
            CaltopoClient.RequestLoadConfigFile();
        }
/*
        if (BuildConfig.USE_GOOGLE_MAPS)
            return mMapView.changeMapType(item);
 */
        return false;
    }

    boolean getLogEnabled() {
        SharedPreferences pref = getSharedPreferences(SHARED_PREF_NAME, 0);
        return pref.getBoolean(SHARED_PREF_ENABLE_LOG, true);
    }

    void setLogEnabled(boolean enabled) {
        SharedPreferences pref = getSharedPreferences(SHARED_PREF_NAME, 0);
        pref.edit().putBoolean(SHARED_PREF_ENABLE_LOG, enabled).apply();
    }

    private File getLoggerFileDir(String name) {
        File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "OpenDroneID");
        if (!file.mkdirs()) {
            file = getExternalFilesDir(null);
        }
        String pattern = "yyyy-MM-dd_HH-mm-ss.SSS";
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat(pattern, Locale.US);
        return new File(file, "log_" + Build.MODEL + "_" + name + "_" + simpleDateFormat.format(new Date()) + ".csv");
    }

    private void createNewLogfile() {
        if (null != loggerFile) return;
        loggerFile = getLoggerFileDir(getName());

        try {
            logger = new LogWriter(loggerFile);
        } catch (IOException e) {
            CaltopoClient.CTError(TAG, "createNewLogfile(): ", e);
        }
    }
    public String getName() {
        return getApplication().getProcessName();
    }
    /*
    public void requestTurnOnBluetooth() {
        ActivityResultLauncher enableBluetoothLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        CaltopoClient.CTDebug(TAG, "requestTurnOnBluetooth(): Success");
                    } else {
                        CaltopoClient.CTDebug(TAG, "requestTurnOnBluetooth(): fail");
                    }
                }
        );
        Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
        enableBluetoothLauncher.launch(enableBtIntent);

        BluetoothAdapter bluetoothAdapter = BluetoothScanner.getBluetoothAdapter(this);
        if (bluetoothAdapter == null) {
            CaltopoClient.CTError(TAG, "device doesn't support bluetooth.");
        } else if (!bluetoothAdapter.isEnabled()) {
            CaltopoClient.CTDebug(TAG, "Requesting enable bluetooth...");
            requestTurnOnBluetooth();
        } else {
            CaltopoClient.CTDebug(TAG, "Bluetooth is enabled.");
        }
    }
*/
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (appActivity != null) {
            CaltopoClient.CTDebug(TAG, "onCreate() with an existing activity.");
            if (appActivity != this) {
                RestartingFlag = true;
                /* prevent ScanningService's PendingIntent tap from starting a new instance. */
                CaltopoClient.CTDebug(TAG, "onCreate() restarting with new activity.");
            }
        }
        appActivity = this;
        initializedCalled = false;

        setContentView(R.layout.activity_debug);
        mModel = new ViewModelProvider(this).get(AircraftViewModel.class);

        dataManager = new OpenDroneIdDataManager(new OpenDroneIdDataManager.Callback() {
            @Override
            public void onNewAircraft(AircraftObject object) {
                mModel.setAllAircraft(dataManager.getAircraft());
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            CaltopoClient.CTDebug(TAG, "onCreate: Android 13");
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                CaltopoClient.CTDebug(TAG, "onCreate: Requesting NEARBY_WIFI_DEVICES");
                outstandingPermissionsList.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            } else {
                CaltopoClient.CTDebug(TAG, "onCreate: NEARBY_WIFI_DEVICES granted.");
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                CaltopoClient.CTDebug(TAG, "onCreate: Requesting POST_NOTIFICATIONS");
                outstandingPermissionsList.add(Manifest.permission.POST_NOTIFICATIONS);
            } else {
                CaltopoClient.CTDebug(TAG, "onCreate: POST_NOTIFICATIONS granted.");
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            CaltopoClient.CTDebug(TAG, "onCreate: Requesting ACCESS_FINE_LOCATION");
            outstandingPermissionsList.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        } else {
            CaltopoClient.CTDebug(TAG, "onCreate: ACCESS_COARSE_LOCATION granted.");
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            CaltopoClient.CTDebug(TAG, "onCreate: Requesting ACCESS_FINE_LOCATION");
            outstandingPermissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION);
        } else {
            CaltopoClient.CTDebug(TAG, "onCreate: ACCESS_FINE_LOCATION granted.");
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            CaltopoClient.CTDebug(TAG, "onCreate: Android 12");
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                CaltopoClient.CTDebug(TAG, "onCreate: Requesting BLUETOOTH_SCAN");
                outstandingPermissionsList.add(Manifest.permission.BLUETOOTH_SCAN);
            } else {
                CaltopoClient.CTDebug(TAG, "onCreate: BLUETOOTH_SCAN granted");
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                CaltopoClient.CTDebug(TAG, "onCreate: Requesting BLUETOOTH_CONNECT");
                outstandingPermissionsList.add(Manifest.permission.BLUETOOTH_CONNECT);
            } else {
                CaltopoClient.CTDebug(TAG, "onCreate: BLUETOOTH_CONNECT granted");
            }
        }
        CaltopoClient.InitializeForActivityAndContext(this, getApplicationContext());
        if (!outstandingPermissionsList.isEmpty()) {
            String[] permArray = outstandingPermissionsList.toArray(new String[0]);
            ActivityCompat.requestPermissions(this, permArray, Constants.REQUEST_BULK_PERMISSIONS);
        } else {
            finalizeOnCreate();
            initialize();
        }
    }

    private void finalizeOnCreate() {
        CaltopoClient.CTDebug(TAG, "finalizeOnCreate");

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wifiManager.isWifiEnabled()) {
            Log.i(TAG, "finalizeOnCreate(): Requesting Internet Connectivity.");
            Intent panelIntent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
            startActivity(panelIntent);
        }

        BluetoothAdapter bluetoothAdapter = BluetoothScanner.getBluetoothAdapter(getAppContext());
        if (bluetoothAdapter != null) {
            // Is Bluetooth turned on?
            if (!bluetoothAdapter.isEnabled()) {
                // Prompt user to turn on Bluetooth (logic continues in onActivityResult()).
                try {
                    CaltopoClient.CTDebug(TAG, "finalizeOnCreate(): Requesting Bluetooth Enable.");
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivity(enableBtIntent);
                } catch (SecurityException se) {
                    CaltopoClient.CTError(TAG, String.format(Locale.US, "Not able to turn on bluetooth - %s", se));
                }
            }
        }
        createNewLogfile();
    }

    private void initialize() {
        if (initializedCalled) return;
        CaltopoClient.CTDebug(TAG, "initialize()");
        initializedCalled = true;
        String archivePathVal = CaltopoClient.GetArchivePath();
        if (null == archivePathVal) {
            CaltopoClient.QueryUserForArchiveDir();
        }
        mModel.setAllAircraft(dataManager.getAircraft());

        final Observer<Set<AircraftObject>> listObserver = airCrafts -> {
            if (airCrafts == null)
                return;
            setTitle(String.format(Locale.US, "%d drones", airCrafts.size()));
        };

        mModel.getAllAircraft().observe(this, listObserver);

        addDeviceList();

        AircraftOsMapView mOsMapView = (AircraftOsMapView) getSupportFragmentManager().findFragmentById(R.id.mapView);
            if (mOsMapView != null)
                mOsMapView.setMapSettings();

        mFusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        locationRequest = new LocationRequest.Builder(10 * 1000) // 10 seconds
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMinUpdateIntervalMillis(5 * 1000) // 5 seconds
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    if (location != null) {
                        dataManager.receiverLocation = location;
                    }
                }
            }
        };
        if (!RestartingFlag) {
            CaltopoClient.CTDebug(TAG, String.format(Locale.US, "onCreate(): Starting ScanningService from activity 0x%x", this.hashCode()));
            Intent serviceIntent = new Intent(this, ScanningService.class);
            getApplicationContext().startForegroundService(serviceIntent);
        }
    }


    public void addDeviceList() {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.holder, new DeviceList()).commitAllowingStateLoss();
    }

    @Override
    protected void onResume() {
        // Log.d(TAG, "onResume");

        // Wake the main Activity thread regularly, to update time counters and other UI elements
        handler = new Handler(Looper.getMainLooper());
        runnableCode = () -> {
            for (AircraftObject aircraft : dataManager.aircraft.values()) {
                aircraft.updateShadowBasicId();
                aircraft.connection.setValue(aircraft.connection.getValue());
            }
            handler.postDelayed(runnableCode, 1000);
        };
        handler.post(runnableCode);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            if (mFusedLocationClient != null)
                mFusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper());

        }

        super.onResume();
    }

    @Override
    protected void onPause() {
        // Log.d(TAG, "onPause");

        handler.removeCallbacks(runnableCode);
        if (mFusedLocationClient != null)
            mFusedLocationClient.removeLocationUpdates(locationCallback);
        CaltopoClient.CTDebug(TAG, "onPause() archiving tracks...");
        archiveTracks();
        super.onPause();
    }

    public void archiveTracks() {
        try {
            WaypointTrack.ArchiveTracks(this);
        } catch (Exception e) {
            CaltopoClient.CTError(TAG, "archiveTracks() raised:", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {

        CaltopoClient.CTDebug(TAG, "In onRequestPermissionsResult()");
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == Constants.REQUEST_BULK_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++) {
                int ix = outstandingPermissionsList.indexOf(permissions[i]);
                outstandingPermissionsList.remove(ix);
                if (grantResults[i] != PackageManager.PERMISSION_GRANTED) {
                    CaltopoClient.CTError(TAG, "onRequestPermissionsResult: Did not get " + permissions[i]);
                } else {
                    CaltopoClient.CTDebug(TAG, "onRequestPermissionsResult(): Received " + permissions[i]);
                }
            }
            finalizeOnCreate();
            initialize();
            return;
        }
        CaltopoClient.CTDebug(TAG, String.format(Locale.US, "onRequestPermissionsResult(%d)", requestCode));
    }

    public void showToast(String message) {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R)
            Toast.makeText(getBaseContext(), message, Toast.LENGTH_LONG).show();
        else {
            Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content).getRootView(), message, Snackbar.LENGTH_LONG);
            View snackView = snackbar.getView();
            TextView snackTextView = snackView.findViewById(com.google.android.material.R.id.snackbar_text);
            snackTextView.setTextIsSelectable(true);
            snackTextView.setMaxLines(5);
            snackbar.show();
        }
    }

    public static void ConfirmActiveTrackLabelChange(CaltopoClient client, CtDroneSpec droneSpec, String existingLabel) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getDebugActivity());

        builder.setTitle("Change active track label");
        builder.setMessage(String.format(Locale.US,"Change Label From:'%s' to '%s'",
                existingLabel, droneSpec.getMappedId()));

        // Positive button
        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                CaltopoClient.CTDebug(TAG, String.format(Locale.US, "User confirmed change track label From:'%s' to '%s'",
                        existingLabel, droneSpec.getMappedId()));
                dialog.dismiss();
            }
        });

        // Negative button
        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // User clicked "No", dismiss the dialog
                CaltopoClient.CTDebug(TAG, "User cancelled track label change.");
                dialog.dismiss();
            }
        });

        // Create and show the AlertDialog
        AlertDialog alertDialog = builder.create();
        alertDialog.show();

    }
    private void confirmUserWishesToExit() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setTitle("Exit Application");
        builder.setMessage("Are you sure you want to exit?");

        // Positive button
        builder.setPositiveButton("Yes", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                CaltopoClient.CTDebug(TAG, "User confirmed intention to exit.");
                finish();
            }
        });

        // Negative button
        builder.setNegativeButton("No", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                // User clicked "No", dismiss the dialog
                CaltopoClient.CTDebug(TAG, "User cancelled exit.");

                dialog.dismiss();
                Toast.makeText(DebugActivity.this, "Exit cancelled", Toast.LENGTH_SHORT).show();
            }
        });

        // Create and show the AlertDialog
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    @Override
    public void onBackPressed() {
        CaltopoClient.CTDebug(TAG, "onBackButtonPressed()");
        confirmUserWishesToExit();
    }

    @Override
    public void onDestroy() {
        if (this == appActivity) {
            if (isFinishing()) {
                CaltopoClient.CTDebug(TAG, "onDestroy() shutting down scanning service...");
                Intent serviceIntent = new Intent(this, ScanningService.class);
                stopService(serviceIntent);
                CaltopoClient.Shutdown();
                appActivity = null;
                forceStopApp();
                super.onDestroy();
                return;
            }
            CaltopoClient.CTDebug(TAG, "onDestroy() archiving tracks...");
            archiveTracks();
        }
        super.onDestroy();
    }

    void forceStopApp() {
        new Thread(() -> {
            try {
                Thread.sleep(3000);
            }
            catch (Exception ignored) { }
            finish();
        }).start();
    }
}

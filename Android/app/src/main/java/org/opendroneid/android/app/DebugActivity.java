/*
 * Copyright (C) 2019 Intel Corporation
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 */
package org.opendroneid.android.app;

import android.Manifest;

import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.net.Uri;
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
import org.opendroneid.android.PermissionUtils;
import org.opendroneid.android.R;
import org.opendroneid.android.bluetooth.BluetoothScanner;
import org.opendroneid.android.data.CaltopoClient;
import org.opendroneid.android.data.WaypointTrack;
import org.opendroneid.android.log.LogWriter;
import org.opendroneid.android.bluetooth.OpenDroneIdDataManager;
import org.opendroneid.android.data.AircraftObject;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
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
    private MenuItem mMenuLogItem;

//    private AircraftMapView mMapView;

    private File loggerFile;
    private LogWriter logger;

    private Handler handler;
    private Runnable runnableCode;
    public OpenDroneIdDataManager getDataManager() {return dataManager;}
    public LogWriter getLogger() {return logger;}
    private static DebugActivity appActivity = null;
    public static DebugActivity getDebugActivity() {return appActivity;}
    public static Context getAppContext() {return appActivity.getApplicationContext();}
    int pendingPermissions;
    int grantedPermissions;

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
        Object object = getSystemService(BLUETOOTH_SERVICE);
        if (object == null)
            return;
        BluetoothAdapter bluetoothAdapter = ((android.bluetooth.BluetoothManager) object).getAdapter();

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED &&
                    ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "createNewLogfile:  Did not get BLUETOOTH_SCAN or BLUETOOTH_CONNECT");
                showToast(getString(R.string.nearby_not_granted));
                forceStopApp();
                return;
            }
        }
        loggerFile = getLoggerFileDir(getName());

        try {
            logger = new LogWriter(loggerFile);
        } catch (IOException e) {
            Log.e(TAG, e.toString());
        }
    }
    public String getName() {
        return getApplication().getProcessName();
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (appActivity != null) {
            /* prevent ScanningService's PendingIntent tap from starting a new instance. */
            finish();
            return;
        }

        setContentView(R.layout.activity_debug);
        mModel = new ViewModelProvider(this).get(AircraftViewModel.class);

        dataManager = new OpenDroneIdDataManager(new OpenDroneIdDataManager.Callback() {
            @Override
            public void onNewAircraft(AircraftObject object) {
                mModel.setAllAircraft(dataManager.getAircraft());
            }
        });

        pendingPermissions = grantedPermissions = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "onCreate: Android 13");
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "onCreate: Requesting NEARBY_WIFI_DEVICES");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.NEARBY_WIFI_DEVICES}, Constants.REQUEST_NEARBY_WIFI_DEVICES_PERMISSION);
                pendingPermissions++;
            } else {
                Log.d(TAG, "onCreate: NEARBY_WIFI_DEVICES granted.");
                grantedPermissions++;
            }
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "onCreate: Requesting ACCESS_FINE_LOCATION");
            ActivityCompat.requestPermissions(this, new String[] { Manifest.permission.ACCESS_FINE_LOCATION }, Constants.FINE_LOCATION_PERMISSION_REQUEST_CODE);
            pendingPermissions++;
        } else {
            Log.d(TAG, "onCreate: ACCESS_FINE_LOCATION granted.");
            grantedPermissions++;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(TAG, "onCreate: Android 12");
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "onCreate: Requesting BLUETOOTH_SCAN");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_SCAN}, Constants.REQUEST_BLUETOOTH_PERMISSION_SCAN);
                pendingPermissions++;
            } else {
                Log.d(TAG, "onCreate: BLUETOOTH_SCAN granted");
                grantedPermissions++;
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "onCreate: Requesting BLUETOOTH_CONNECT");
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.BLUETOOTH_CONNECT}, Constants.REQUEST_BLUETOOTH_PERMISSION_CONNECT);
                pendingPermissions++;
            } else {
                Log.d(TAG, "onCreate: BLUETOOTH_CONNECT granted");
                grantedPermissions++;
            }
        }
        appActivity = this;
        finalizeOnCreate();
        if (0 == pendingPermissions) initialize();
    }

    private void finalizeOnCreate() {
        Log.d(TAG, "finalizeOnCreate");

        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (!wifiManager.isWifiEnabled()) {
            Intent panelIntent = new Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY);
            startActivity(panelIntent);
        }

        BluetoothAdapter bluetoothAdapter = BluetoothScanner.getBluetoothAdapter(getAppContext());
        if (bluetoothAdapter != null) {
            // Is Bluetooth turned on?
            if (!bluetoothAdapter.isEnabled()) {
                // Prompt user to turn on Bluetooth (logic continues in onActivityResult()).
                try {
                    Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivity(enableBtIntent);
                } catch (SecurityException se) {
                    Log.e(TAG, String.format(Locale.US, "Not able to turn on bluetooth - %s", se));
                }
            }
        }

        String archivePathVal = CaltopoClient.getArchivePath();
        if (null == archivePathVal) {
            CaltopoClient.queryUserForArchiveDir();
        }
        createNewLogfile();
    }

    private void initialize() {
        Log.d(TAG, "initialize()");
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
        Log.d(TAG, String.format(Locale.US, "onCreate(): Starting ScanningService from activity 0x%x", this.hashCode()));
        Intent serviceIntent = new Intent(this , ScanningService.class);
        getApplicationContext().startForegroundService(serviceIntent);
        CaltopoClient.initializeForActivityAndContext(this, getApplicationContext());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == Constants.REQUEST_ENABLE_BT) {
            if (resultCode == RESULT_OK) {
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED ||
                        ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "onMapReady: call request permission");
                    requestLocationPermission(Constants.FINE_LOCATION_PERMISSION_REQUEST_CODE);
                } else {
                    initialize();
                }
            } else {
                Log.e(TAG, "onActivityResult: User declined to enable Bluetooth, exit the app.");
                showToast(getString(R.string.bt_not_enabled_leaving));
                forceStopApp();
            }
        } else if (requestCode == Constants.REQUEST_ENABLE_WIFI) {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (!wifiManager.isWifiEnabled()) {
                Log.e(TAG, "onActivityResult: User declined to enable WiFi, exit the app.");
                showToast(getString(R.string.wifi_not_enabled_leaving));
                forceStopApp();
            }
        }
    }

    public void addDeviceList() {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.holder, new DeviceList()).commitAllowingStateLoss();
    }

    @Override
    protected void onResume() {
        Log.d(TAG, "onResume");

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
        Log.d(TAG, "onPause");

        handler.removeCallbacks(runnableCode);
        if (mFusedLocationClient != null)
            mFusedLocationClient.removeLocationUpdates(locationCallback);
        Log.i(TAG, "onPause() archiving tracks...");
        archiveTracks();
        super.onPause();
    }

    public void archiveTracks() {
        String archivePath = CaltopoClient.getArchivePath();
        if (null != archivePath) try {
            Uri treeUri = Uri.parse(archivePath);
            DocumentFile archiveDir = DocumentFile.fromTreeUri(this, treeUri);
            if (null != archiveDir) {
                WaypointTrack.ArchiveTracks(this, archiveDir);
            }
        } catch (Exception e) {
            Log.e(TAG, String.format(Locale.US, "archiveTracks(%s) raised:\n  %s", archivePath, e));
        }
    }
    public void requestLocationPermission(int requestCode) {
        Log.d(TAG, "requestLocationPermission: request permission");

        // Location permission has not been granted yet, request it.
        PermissionUtils.requestPermission(this, requestCode,
                Manifest.permission.ACCESS_FINE_LOCATION, false);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Log.d(TAG, "onRequestPermissionsResult()");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Log.d(TAG, "onRequestPermissionsResult: Android 13");
            if (requestCode == Constants.FINE_LOCATION_PERMISSION_REQUEST_CODE) {
                pendingPermissions--;
                Log.d(TAG, "onRequestPermissionsResult: back from request FINE_LOCATION");
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "onRequestPermissionsResult: Did not get ACCESS_FINE_LOCATION");
                } else {
                    grantedPermissions++;
                }
            }
        }

        if (requestCode == Constants.REQUEST_NEARBY_WIFI_DEVICES_PERMISSION) {
            pendingPermissions--;
            Log.d(TAG, "onRequestPermissionsResult: REQUEST_NEARBY_WIFI_DEVICES_PERMISSION");
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "onRequestPermissionsResult: Did not get NEARBY_WIFI_DEVICES");
            } else {
                grantedPermissions++;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Log.d(TAG, "onRequestPermissionsResult: Android 12");
            if (requestCode == Constants.REQUEST_BLUETOOTH_PERMISSION_SCAN) {
                pendingPermissions--;
                Log.d(TAG, "onRequestPermissionsResult: REQUEST_BLUETOOTH_PERMISSION_SCAN");
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                   Log.d(TAG, "onRequestPermissionsResult: Did not get BLUETOOTH_SCAN");
                } else {
                    grantedPermissions++;
                }
             }

            if (requestCode == Constants.REQUEST_BLUETOOTH_PERMISSION_CONNECT) {
                pendingPermissions--;
                Log.d(TAG, "onRequestPermissionsResult: REQUEST_BLUETOOTH_PERMISSION_CONNECT");
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                    Log.e(TAG, "onRequestPermissionsResult: Did not get BLUETOOTH_CONNECT");
                } else {
                    grantedPermissions++;
                }
            }
        }
        if (pendingPermissions <= 0) {
            if (grantedPermissions == 0) {
                showToast(getString(R.string.nearby_not_granted));
                forceStopApp();
                return;
            }
        }
    }

    void showToast(String message) {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R)
            Toast.makeText(getBaseContext(), message, Toast.LENGTH_LONG).show();
        else {
            Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content).getRootView(), message, Snackbar.LENGTH_LONG);
            View snackView = snackbar.getView();
            TextView snackTextView = snackView.findViewById(com.google.android.material.R.id.snackbar_text);
            snackTextView.setMaxLines(5);
            snackbar.show();
        }
    }

    @Override
    public void onDestroy() {
        if (this == appActivity) {
            Log.i(TAG, "onDestroy() shutting down scanning service...");
            Intent serviceIntent = new Intent(this, ScanningService.class);
            stopService(serviceIntent);
            Log.i(TAG, "onDestroy() archiving tracks...");
            archiveTracks();
            appActivity = null;
            forceStopApp();
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

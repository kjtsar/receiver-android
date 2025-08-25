/*
 caltopo module:
   Provide UI to specify groupID and edit the RID map

 caltopo supports:
   https://caltopo.com/api/v1/postion/report/<group>?id=<id>@lat=36.47375&lng=-118.85302
   # organization identifer - No Spaces & up to 12 characters
   setGroupid(groupid='NCSSAR')

   # Map Remote ID to SARID + dronetype + accessories.
   setRidTable(rid='1581F67QE239L00A00DE', id='1SAR7m3p')

   UI for specifying groupid and ridTable info:

                                           +----------+
 	   Caltopo min update delta in feet:   |    4     |  (~.00001 degrees)
                                           +----------+

                       +-------------+
      Caltopo GroupID: | <textfield> |
                       +-------------+
  For some scenarios, might want to enable any new RID be logged with it's RID directly.
  For other scenarios, might only want to log known RIDs
                       +------------------+
        AutoLogNewIDs: | [toggle autoAdd] |
                       +------------------+

   Identfied IDs(Scroll View):
      REMOTE ID       Track Label (i.e. <pilot>_<model>)    Status
    +---------------+------------------------------------+-----------+
    |  <remoteID1>  |  blank or <pilot>+<ModelInfo>      | airborne  |
    +---------------+------------------------------------+-----------+
    |  <remoteID2>  |  blank or <pilot>+<ModelInfo>      | grounded  |
    +---------------+------------------------------------+-----------+
    |  <remoteID3>  |  blank or <pilot>+<ModelInfo>      | grounded  |
    +---------------+------------------------------------+-----------+
    |  <remoteID4>  |  blank or <pilot>+<ModelInfo>      | grounded  |
    +---------------+------------------------------------+-----------+
    |  <remoteID5>  |  blank or <pilot>+<ModelInfo>      | grounded  |
    +---------------+------------------------------------+-----------+
    |  <remoteID6>  |  blank or <pilot>+<ModelInfo>      | grounded  |
    +---------------+------------------------------------+-----------+

  use:
       CaltopoClient client = CaltopoClient.clientForRemoteId(String remoteID)
       client.newWaypoint(lat,lng);
    #


*/
package org.opendroneid.android.data;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.*;

import org.opendroneid.android.app.DebugActivity;

/*
 * Persistent state management for CaltopoClient
 */
class ClientClassState implements Serializable {
    static final long serialVersionUID = 11L; // Serializable version.
    long minDistanceInFeet;
    String groupID;
    String archivePath;
    Hashtable<String, String> ridTable;  // Table to map remoteIDs to their preferred descriptors.
    boolean caltopoUpdatesEnabled;

    // Default/initial state for the caltopo client:
    ClientClassState() {
        minDistanceInFeet = CaltopoClient.MIN_DISTANCE_IN_FEET;
        groupID = "";
        archivePath = null;
        caltopoUpdatesEnabled = false;
        ridTable = new Hashtable<String, String>(16);
    }

    public String stringRep() {
        String retval = String.format("vers:'%d', minDist:'%d' ft, group:'%s', caltopoUpdatesEnabled:%s, archivePath:%s, ht:%s:",
                serialVersionUID, minDistanceInFeet, groupID, caltopoUpdatesEnabled ? "true" : "false",
                (archivePath == null) ? "<undefined>":archivePath, CaltopoClient.htStringRep(ridTable));

        return retval;
    }
}

public class CaltopoClient {
    // CaltopoClient CLASS VARS:
    static final int MIN_DISTANCE_IN_FEET = 2;
    private static final String BASE_URL = "https://caltopo.com/api/v1/position/report/";
    private static final String TAG = "CaltopoClient";
    private static Hashtable<String, CaltopoClient> clientMap;
    private static int threadPoolSize = 1;
    private static ExecutorService executorPool = null;
    private static ClientClassState ccstate = null;
    private static AppCompatActivity appActivity = null;
    private static Context appContext = null;
    private static String MyStateFileName = TAG + ".ser";
    private static String MyTemporaryStateFileName = TAG + ".tmp";

    // CaltopoClient INSTANCE VARS:=
    private String mappedID = null;
    private String remoteID = null;

    public CaltopoClient(String rid) {
        ClientClassState ccs = getState();

        remoteID = rid;
        mappedID = ccs.ridTable.get(rid);  // try archived value first.
        if (null == mappedID) {
            mappedID = rid; // default to mappedID same as remoteID.
            ccs.ridTable.put(remoteID, mappedID);
        }
    }

    static ActivityResultLauncher<Intent> queryArchivePath;

    public String toString() {
        return String.format("  rid:%s, mapped:%s", remoteID, mappedID);
    }
    public static void queryUserForArchiveDir() {
        final String EXTERNAL_STORAGE_PROVIDER_AUTHORITY = "com.android.externalstorage.documents";

        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        // intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
        //        Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
        //        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION |
        //        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        // intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, "Select directory to archive drone tracks");
        Uri downloadsUri = DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_PROVIDER_AUTHORITY, "primary:Downloads");
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloadsUri);
        Log.d(TAG, String.format("In queryUserForArchiveDir(%s)", intent.toString()));
        try {
            queryArchivePath.launch(intent);
        } catch (Exception e) {
            Log.wtf(TAG, String.format("queryUserForArchiveDir() raised:'%s'", e.toString()));
        }
    }

    public static void initializeForActivityAndContext(AppCompatActivity activity, Context ctxt) {
        appActivity = activity;
        appContext = ctxt;
        try {
            queryArchivePath = activity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                    new ActivityResultCallback<ActivityResult>() {
                        @Override
                        public void onActivityResult(ActivityResult result) {
                            Log.d(TAG, String.format("In queryArchivePath:onActivityResult(%s)", result.toString()));
                            if (result.getResultCode() == Activity.RESULT_OK) {
                                Intent data = result.getData();
                                if (null != data) {
                                    Uri treeUri = data.getData();
                                    // Persist the URI for later use (e.g., in SharedPreferences)
                                    appActivity.getContentResolver().takePersistableUriPermission(treeUri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                                    // Now you have a Uri representing the selected directory tree, which likely includes Downloads
                                    // You can use DocumentFile to work with files within this directory
                                    setArchivePath(treeUri.toString());
                                }
                            }
                        }
                    });
        } catch (Exception e) {
            Log.e(TAG, String.format("initializeForActivityAndContext() raised:\n  %s", e.toString()));
        }
    }
    public static CaltopoClient clientForRemoteId(String remoteID) {

        if (null == clientMap) {
            clientMap = new Hashtable<String, CaltopoClient>(16);
        }
        if (null == remoteID || 0 == remoteID.length()) {
            Log.wtf(TAG, String.format("clientForRemoteId('%s'", remoteID));
        }
        CaltopoClient client = clientMap.get(remoteID);

        if (null == client) {
            client = new CaltopoClient(remoteID);

            clientMap.put(remoteID, client);
            Log.i(TAG, String.format("mapped %s to a new client ", remoteID));
        }
        return client;
    }

    public static String htStringRep(Hashtable<String, String> ht) {
        int count = (null != ht) ? ht.size() : 0;
        String retval = String.format("%d k/v pairs:", count);

        if (null != ht) {
            for (Map.Entry<String, String> map : ht.entrySet()) {
                String key = map.getKey(), val = map.getValue();
                retval += String.format("\n  %20s:'%s'", key, val);
            }
        }
        return retval;
    }

    private static ClientClassState getState() {
        if (null == ccstate) {
            appContext = DebugActivity.getAppContext();
            ClientClassState ccs = restoreState();
            if (null == ccs) ccs = new ClientClassState();
            Log.d(TAG, String.format("getState():%s.", ccs.stringRep()));
            ccstate = ccs;
        }
        return ccstate;
    }

    public static Hashtable<String, String> getRidTable() {
        ClientClassState ccs = getState();
        return ccs.ridTable;
    }

    public static String getGroupId() {
        ClientClassState ccs = getState();
        return ccs.groupID;
    }

    public static long getMinDistanceInFeet() {
        ClientClassState ccs = getState();
        return ccs.minDistanceInFeet;
    }
    public static String getArchivePath() {
        ClientClassState ccs = getState();
        return ccs.archivePath;
    }

    public static void setArchivePath(String path) {
   /* FIXME: is this going to be a problem going forward?  How to get access to same dir each time?

        if ((null != path) && (path.length() > 0)) {

            try {
                Log.d(TAG, String.format("setArchivePath(%s)...", path.toString()));
                Uri treeUri = Uri.parse(path);
                Log.d(TAG, String.format("setArchivePath(), Uri.parse() returned %s", treeUri.toString()));
                DocumentFile archiveDir = DocumentFile.fromTreeUri(appContext, treeUri);
                Log.d(TAG, String.format("setArchivePath(), archiveDir() returned: %s", archiveDir.toString()));
                Uri newTreeUri = archiveDir.getUri();
                Log.d(TAG, String.format("setArchivePath(), archiveDir.getUri() returned: %s", newTreeUri.toString()));
                String newPath = newTreeUri.toString();
                Log.d(TAG, String.format("setArchivePath(), newTreeUri() returned: %s", newPath));
                if ((null == newPath) || (0 != newPath.compareTo(path))) {
                    Log.e(TAG, String.format("setArchivePath(%s) identity test failed:'%s'", path, newPath));
                    return;
                }
            } catch (Exception e) {
                Log.e(TAG, String.format("setArchivePath(%s) raised:\n  '%s'", path, e.toString()));
                path = null;
            }
        } else { // allow user to reset path:
            path = null;
        }
    */
        ClientClassState ccs = getState();
        ccs.archivePath = path;
        archiveState();
    }

    public static boolean caltopoUpdatesEnabled() {
        ClientClassState ccs = getState();
        return ccs.caltopoUpdatesEnabled;
    }

    public static String setGroupId(String gid) {
        ClientClassState ccs = getState();
        if ((gid != null) & (gid.length() > 0)) {
            ccs.caltopoUpdatesEnabled = true;
            ccs.groupID = gid;
        } else {
            ccs.caltopoUpdatesEnabled = false;
            ccs.groupID = "";
        }
        archiveState(); // save any time there is a chg.
        return ccs.groupID;
    }

    public static long setMinDistanceInFeet(long minDistance) {
        ClientClassState ccs = getState();
        if (minDistance < MIN_DISTANCE_IN_FEET) {
            minDistance = MIN_DISTANCE_IN_FEET;
        }
        ccs.minDistanceInFeet = minDistance;
        archiveState();
        return ccs.minDistanceInFeet;
    }

    public String setMappedId(String id) {
        ClientClassState ccs = getState();
        if (null != id && id.length() > 0) {
            mappedID = id;
            ccs.ridTable.put(remoteID, mappedID);
            archiveState();
        }
        Log.i(TAG, String.format("mapped '%s' to '%s' ", remoteID, mappedID));
        return mappedID;
    }


    public void publish(String groupId, String deviceId, double lat, double lng) {
        String https_url = String.format("%s%s?id=%s&lat=%.6f&lng=%.6f",
                BASE_URL, groupId, deviceId, lat, lng);
        try {
            URL url = new URL(https_url);
            HttpsURLConnection httpsConn;
            int responseCode;

        //    Log.i(TAG, "sending to caltopo: " + https_url);
            httpsConn = (HttpsURLConnection) url.openConnection();
            httpsConn.setRequestMethod("GET");
            httpsConn.setRequestProperty("User-Agent", "RemoteIdTracker/0.1");
            responseCode = httpsConn.getResponseCode();
            if (HttpsURLConnection.HTTP_OK != responseCode) {
                Log.e(TAG, "https bad response: " + responseCode);
            }
        } catch (IOException e) {
            Log.d(TAG, "openConnection() failed:\n  " + e);
            executorPool.shutdown();
            ClientClassState ccs = getState();
            ccs.caltopoUpdatesEnabled = false; // stop attempts to send msgs to caltopo.
        }
    }

    public long unsavedMsgCount() {
        return WaypointTrack.UnsavedMsgCountForTrack(mappedID);
    }

    public boolean newWaypoint(double lat, double lng, long altitudeInMeters, long timestampInSeconds) {
        boolean archived = WaypointTrack.AddWaypointForTrack(mappedID, lat, lng, altitudeInMeters, timestampInSeconds);
        if (archived) {
            // Then it is OK to publish this updated location
            //     Log.i(TAG, String.format("submitting job: %s %f %f", mappedID, lat, lng));
            ClientClassState ccs = getState();
            if (ccs.caltopoUpdatesEnabled) try {
                if (null == executorPool) {
                    executorPool = Executors.newFixedThreadPool(threadPoolSize);
                }
                executorPool.submit(() -> {
                    publish(ccs.groupID, mappedID, lat, lng);
                });
            } catch (Exception e) {
                Log.d(TAG, String.format("executorPool.submit() raised:\n  %s", e.toString()));
                if (null != executorPool) {
                    executorPool.shutdown();
                }
                ccs.caltopoUpdatesEnabled = false; // stop attempts to send msgs to caltopo.
            }
        }
        return archived;
    }

    // can return null if no stored state available.
    private static ClientClassState restoreState() {
        ClientClassState ccs = null;
        try {
            FileInputStream fis = appContext.openFileInput(MyStateFileName);
            ObjectInputStream ois = new ObjectInputStream(fis);
            ccs = (ClientClassState) ois.readObject();
            ois.close();
        } catch (FileNotFoundException e) {
            ccs = null;
        } catch (java.io.InvalidClassException e) {
            Log.d(TAG, String.format("restoreState(ICE) not able to restore incompatible version of state:\n  %s",
                    e.toString()));
            ccs = null;
        } catch (ClassNotFoundException e) {
            // probably a version mismatch
            Log.d(TAG, String.format("restoreState(CNF) not able to restore incompatible version of state.\n  %s",
                    e.toString()));
            ccs = null;
        } catch (IOException e) {
            e.printStackTrace();
            ccs = null;
        }
        return ccs;
    }

    private static void archiveState() {
        if (null != ccstate) try {
            FileOutputStream fos = appContext.openFileOutput(MyStateFileName, 0);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(ccstate);
            oos.flush();
            oos.close();
            Log.d(TAG, String.format("archivedState():%s.", ccstate.stringRep()));

            //  Files.move(Paths.get(MyTemporaryStateFileName), Paths.get(MyStateFileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}


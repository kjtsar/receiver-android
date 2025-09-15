/*
 caltopo module:
   Provide UI to specify groupID and edit the RID map

 caltopo supports Live Tracking via Fleet option:
   https://caltopo.com/api/v1/postion/report/<group>?id=<id>@lat=36.47375&lng=-118.85302
   # organization identifer - No Spaces & up to 12 characters
   setGroupid(groupid='NCSSAR')

   It also provides an API of sorts that allows limited direct viewing/editing of maps:
       https://training.caltopo.com/all_users/team-accounts/teamapi

  use:
       // specify either Live Track (false) or Direct Track (true)
       CaltopoClient.useDirectFlag(useDirectFlag);
       if (useDirectFlag) {
           CaltopoClient.setCaltopoSessionConfig(cfg);
       }

       CaltopoClient client = CaltopoClient.clientForRemoteId(String remoteID);
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
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opendroneid.android.app.DebugActivity;

/*
 * Persistent state management for CaltopoClient
 */
class ClientClassState implements Serializable {
    private static final long serialVersionUID = 12L; // Serializable version.
    public long minDistanceInFeet;
    public String groupID;
    public String archivePath;
    public Hashtable<String, String> ridTable;  // Table to map remoteIDs to their preferred descriptors.
    public boolean caltopoUpdatesEnabled;
    public String caltopoTrackFolder;
    public CaltopoSessionConfig caltopoSessionConfig;
    public String mapId;
    public boolean useDirectFlag;
    public String droneSymbol;
    static final String DEFAULT_DRONE_SYMBOL = "point";

    // Default/initial state for the caltopo client:
    ClientClassState() {
        minDistanceInFeet = CaltopoClient.MIN_DISTANCE_IN_FEET;
        groupID = "";
        archivePath = null;
        caltopoUpdatesEnabled = false;
        caltopoTrackFolder = "Drone Tracks";
        caltopoSessionConfig = null;
        mapId = "";
        droneSymbol = DEFAULT_DRONE_SYMBOL; // "icon-8T781R60-12-0.5-0.5-tf";
        useDirectFlag = false;
        ridTable = new Hashtable<>(16);
    }

    public String stringRep() {
        CaltopoSessionConfig cfg = caltopoSessionConfig;
        String domainAndPort = "";
        String teamId = "";
        String credId = "";
        String credSecret = "";
        if (null != cfg) {
            if (null != cfg.domainAndPort && !cfg.domainAndPort.isEmpty()) {
                domainAndPort = cfg.domainAndPort;
            }
            if (null != cfg.teamId && !cfg.teamId.isEmpty()) {
                // teamId = cfg.teamId;
                teamId = "###";
            }
            if (null != cfg.credentialId && !cfg.credentialId.isEmpty()) {
                // credId = cfg.credentialId;
                credId = "######";
            }
            if (null != cfg.credentialSecret && !cfg.credentialSecret.isEmpty()) {
                // credSecret = cfg.credentialSecret;
                credSecret = "###########";
            }
    }

        return String.format(Locale.US,
                 "vers:'%d', minDist:'%d' ft, group:'%s', " +
                 "caltopoUpdatesEnabled:%s, archivePath:%s, " +
                 "caltopoTrackFolder: '%s', caltopoDomainAndPort:%s, " +
                 "teamId: '%s', credId: '%s' credSecret: '%s', ht: %s",
                serialVersionUID, this.minDistanceInFeet, groupID,
                caltopoUpdatesEnabled ? "true" : "false",
                (archivePath == null) ? "<undefined>":archivePath,
                caltopoTrackFolder, domainAndPort, teamId, credId,
                credSecret, CaltopoClient.htStringRep(ridTable));
    }
}

public class CaltopoClient {
    private static CaltopoSession csp;
    private static CaltopoOp openMapOp;
    private static boolean mapConfigChanged = true;

    // CaltopoClient CLASS VARS:
    static final int MIN_DISTANCE_IN_FEET = 2;
    private static final String BASE_URL = "https://caltopo.com/api/v1/position/report/";
    private static final String TAG = "CaltopoClient";
    private static Hashtable<String, CaltopoClient> clientMap;
    private static final int threadPoolSize = 1;
    private static ExecutorService executorPool = null;
    private static ClientClassState ccstate = null;
    private static AppCompatActivity appActivity = null;
    private static Context appContext = null;
    private static final String MyStateFileName = TAG + ".ser";
    private static ActivityResultLauncher<Intent> queryArchivePath;


    // CaltopoClient INSTANCE VARS:=
    private String mappedID;
    private String trackLabel;
    private String remoteID;
    private CaltopoOp lastAddLineOp;
    private LinkedList<double[]> linePoints; // array of arrays of [lat,lng] pairs
    private String lineId;
    private String folderId;
    private CaltopoOp folderIdOp;
    private CaltopoOp liveTrackOp;
    private String startDateAndTime;

    public CaltopoClient(String rid) {
        ClientClassState ccs = getState();

        remoteID = rid;
        mappedID = ccs.ridTable.get(rid);  // try archived value first.
        if (null == mappedID) {
            mappedID = rid; // default to mappedID same as remoteID.
            ccs.ridTable.put(remoteID, mappedID);
        }
    }

    public static String getTrackFolderName() {
        ClientClassState ccs = getState();
        return ccs.caltopoTrackFolder;
    }

    /**
     *  Setting a null or empty track folder name is OK!.  That tells the client
     *  to put the tracks in the main directory.
     * @param reqFolderName
     * @return returns the specified string.
     */
    public static String setTrackFolderName(String reqFolderName) {

        ClientClassState ccs = getState();
        if (null != reqFolderName && null != ccs.caltopoTrackFolder) {
            if (!reqFolderName.equals(ccs.caltopoTrackFolder)) {
                mapConfigChanged = true;
            }
        } else if (null != ccs.caltopoTrackFolder || null != reqFolderName) {
            mapConfigChanged = true;
        }
        ccs.caltopoTrackFolder = reqFolderName;
        archiveState();
        return ccs.caltopoTrackFolder;
    }

    public static String getDroneSymbol() {
        ClientClassState ccs = getState();
        return ccs.droneSymbol;
    }

    public static String setDroneSymbol(String newSym) {
        ClientClassState ccs = getState();
        if (null == newSym || newSym.isEmpty()) {
            newSym = ClientClassState.DEFAULT_DRONE_SYMBOL;
        }
        if (!newSym.equals(ccs.droneSymbol)) {
            ccs.droneSymbol = newSym;
            mapConfigChanged = true;
            archiveState();
        }
        return ccs.droneSymbol;
    }

    @Nullable
    public static CaltopoSessionConfig getCaltopoConfig() {
        ClientClassState ccs = getState();
        return ccs.caltopoSessionConfig;
    }

    public static String setMapId(String mapid) throws RuntimeException {
        if (null == mapid || mapid.isEmpty()) {
            throw new RuntimeException("CaltopoClient.setMapId() - invalid mapId value.");
        }
        ClientClassState ccs = getState();
        if (ccs.mapId == null || !mapid.equals(ccs.mapId)) mapConfigChanged = true;
        ccs.mapId = mapid;
        archiveState();
        return ccs.mapId;
    }
    public static String getMapId() {
        ClientClassState ccs = getState();
        return ccs.mapId;
    }
    public static boolean setUseDirect(boolean flag) {
        ClientClassState ccs = getState();
        if (ccs.useDirectFlag != flag) mapConfigChanged = true;
        ccs.useDirectFlag = flag;
        archiveState();
        return ccs.useDirectFlag;
    }

    public static boolean getUseDirectFlag(){
        ClientClassState ccs = getState();
        return ccs.useDirectFlag;
    }

    @Nullable
    public static CaltopoSessionConfig getCaltopoSessionConfig() {
        ClientClassState ccs = getState();
        return ccs.caltopoSessionConfig;
    }
    @Nullable
    public static CaltopoSessionConfig setCaltopoSessionConfig(@NonNull CaltopoSessionConfig cfg)
            throws RuntimeException {
        if (!CaltopoSessionConfig.sniffTest(cfg)) {
            throw new RuntimeException("CaltopoSessionConfig.setCaltopoConfig() bad spec.");
        }

        ClientClassState ccs = getState();

        if (!CaltopoSessionConfig.configSpecsAreEqual(cfg, ccs.caltopoSessionConfig)) {
            ccs.caltopoSessionConfig = cfg;
            mapConfigChanged = true;
            archiveState();
        }
        return ccs.caltopoSessionConfig;
    }


    @Override @NonNull
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
        Log.d(TAG, String.format(Locale.US, "In queryUserForArchiveDir(%s)", intent));
        try {
            queryArchivePath.launch(intent);
        } catch (Exception e) {
            Log.wtf(TAG, String.format(Locale.US, "queryUserForArchiveDir() raised:'%s'", e));
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
            Log.e(TAG, String.format(Locale.US, "initializeForActivityAndContext() raised:\n  %s", e));
        }
    }
    public static CaltopoClient clientForRemoteId(String remoteID) {

        if (null == clientMap) {
            clientMap = new Hashtable<>(16);
        }
        if (null == remoteID || remoteID.isEmpty()) {
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
        StringBuilder retval = new StringBuilder(String.format(Locale.US, "%d k/v pairs:", count));

        if (null != ht) {
            for (Map.Entry<String, String> map : ht.entrySet()) {
                String key = map.getKey(), val = map.getValue();
                retval.append(String.format("\n  %20s:'%s'", key, val));
            }
        }
        return retval.toString();
    }

    private static ClientClassState getState() {
        if (null == ccstate) {
            appContext = DebugActivity.getAppContext();
            ClientClassState ccs = restoreState();
            if (null == ccs) ccs = new ClientClassState();
            Log.d(TAG, "getState()"+ ccs.stringRep());
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
        if (gid != null && !gid.isEmpty()) {
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
        if (null != id && !id.isEmpty()) {
            mappedID = id;
            ccs.ridTable.put(remoteID, mappedID);
            archiveState();
            mapConfigChanged = true;
        }
        Log.i(TAG, String.format("mapped '%s' to '%s' ", remoteID, mappedID));
        return mappedID;
    }

    public void finishTrack() {
        mapConfigChanged = true;
        linePoints = new LinkedList();
    }

    public void bgPublishLive(String groupId, String deviceId, double lat, double lng) {
        String https_url = String.format(Locale.US, "%s%s?id=%s&lat=%.6f&lng=%.6f",
                BASE_URL, groupId, deviceId, lat, lng);
        try {
            URL url = new URL(https_url);
            HttpsURLConnection httpsConn;
            int responseCode;

        //    Log.i(TAG, "sending to caltopo: " + https_url);
            httpsConn = (HttpsURLConnection) url.openConnection();
            httpsConn.setRequestMethod("GET");
            httpsConn.setRequestProperty("User-Agent", "RID2Caltopo/0.1");
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

    /**
     * Apologies for the bit of a state machine going on here, but there's
     *  a process for bringing up a map connection.  Need to configure and
     *  establish the session, then open the map, then peruse the existing
     *  map to see if the folder for our tracks is present and grab it's
     *  id if it is.  If folder not present, we need to create it and get
     *  its id when that process completes.   All this happens in an
     *  environment where network connectivity can drop out at any point
     *  and the user can change the credentials, folder name, or the
     *  mapid at any time, so it requires a bit of flexibility.
     *
     * @return returns true once it's OK to start publishing tracks.
     */
    public boolean caltopoMapIsUp() {
        if (null == csp || mapConfigChanged) {
            openMapOp = null;
            folderIdOp = null;
            folderId = null;
            liveTrackOp = null;
            mapConfigChanged = false;

             if (null == ccstate.caltopoSessionConfig) {
                Log.e(TAG, "publishDirect(): can't establish caltopo connection - missing config spec.");
                return false;
            }
        }

        if (null == csp) {
            csp = new CaltopoSession(ccstate.caltopoSessionConfig);
        } else {
            csp.setCfg(ccstate.caltopoSessionConfig);
        }

        if (null == openMapOp && ccstate.mapId != null && !ccstate.mapId.isEmpty()) {
            try {
                Log.i(TAG, String.format(Locale.US, "Opening map '%s'", ccstate.mapId));
                openMapOp = csp.openMap(ccstate.mapId);
            } catch (Exception e) {
                Log.e(TAG, "caltopoMapIsUp(): csp.openMap() raised: " + e);
            }
        }
        if ((null == openMapOp) || (!openMapOp.isDone())) return false;

        if (!openMapOp.fail() && folderId == null && folderIdOp == null) {
            // See if requested folder is already present - get it's folderId if so.
            // While we're at it, lets make sure we're using a unique track name as well.
            trackLabel = mappedID;
            int suffixIndex = mappedID.length() + 1; // skip the connecting '-' hyphen
            int maxSuffix = 0;

            String folderName;
            if (null != ccstate.caltopoTrackFolder && !ccstate.caltopoTrackFolder.isEmpty()) {
                folderName = ccstate.caltopoTrackFolder;
            } else {
                folderName = "Lines & Polygons";
            }

            try {
                JSONObject state = openMapOp.responseJson.getJSONObject("state");
                JSONArray features = state.getJSONArray("features");
                for (int i = 0; i < features.length(); i++) {
                    JSONObject feature = features.getJSONObject(i);
    //                Log.i(TAG, "Parsing returned feature:\n" + feature.toString(2));
                    JSONObject prop = feature.getJSONObject("properties");
                    String title = prop.getString("title");
                    String classProp = prop.getString("class");
                    if (folderId == null && classProp.equals("Folder") && title.equals(folderName)) {
                        // Found it - get it's ID
                        folderId = feature.getString("id");
    //                     Log.i(TAG, String.format(Locale.US, "Found existing folder '%s' with id %s", folderName, folderId));
                        continue;
                    }
                    if (title.startsWith(mappedID)) {
                        if (title.length() > suffixIndex) {
                            // skip the '-' hyphen
                            String suffix = title.substring(suffixIndex);
                            if (suffix.length() > 0) {
                                int suffixVal = Integer.valueOf(suffix);
                                if (suffixVal >= maxSuffix) {
                                    maxSuffix = suffixVal+1;
                                }
                            }
                        }
                        if (0 == maxSuffix) maxSuffix = 1;
                    }
                }
            } catch (JSONException e) {
                Log.i(TAG, "Error parsing existing map data:" + e + "\n  " +
                        openMapOp.responseJson);
            } catch (InterruptedException e) {
                Log.i(TAG, "Interrupted while parsing existing map data:" + e + "\n  " +
                        openMapOp.responseJson);

            }
            if (maxSuffix > 0) {
                trackLabel = mappedID + "-" + maxSuffix;
            }
        }


        // Request the directory to be created if it wasn't found in the map dump
        if (null == folderId) {
            if (null == folderIdOp) {
                try {
                    folderIdOp = csp.addFolder(ccstate.caltopoTrackFolder,
                            true, true);
                } catch (Exception e) {
                    Log.e(TAG, "caltopoMapIsUp(): csp.addFolder raised: " + e);
                }
                return false;
            } else if (!folderIdOp.isDone()) return false;

            // get folderId from returned create op:
            if (folderIdOp.isDone()) {
                try {
                    folderId = folderIdOp.id();
                } catch (JSONException e) {
                    Log.e(TAG, "caltopoMapIsUp(): folderIdOp.id() raised: " + e);
                }
                Log.i(TAG, String.format(Locale.US, "folderid for op %d is %s", folderIdOp.opNum, folderId));
            }
        }
        return true;
    }

    public void publishDirect(double lat, double lng, long altitudeInMeters)
            throws JSONException, RuntimeException, InterruptedException {

        if (null == linePoints) {
            linePoints = new LinkedList();
            startDateAndTime = LocalDateTime.now().toString();
        }

        // add this new point to the list.
        double[] point = {lat,lng};
        linePoints.add(point);

        if (caltopoMapIsUp()) {
            // start sending our point list to caltopo if it seems to be up and running:
            if (null != folderId && (null == lastAddLineOp || lastAddLineOp.isDone())) {
                if (lastAddLineOp != null && lineId == null) {
                    lineId = lastAddLineOp.id();
                }
                String description = String.format(Locale.US,
                        "\nstart:%s,\nlast:%s",
                        startDateAndTime, LocalDateTime.now().toString());
                if (null == liveTrackOp) {
                    liveTrackOp = csp.startLiveTrack("NCSSAR", trackLabel, folderId, null);
                }
                point = linePoints.removeFirst();
                csp.addLiveTrackPoint("NCSSAR", trackLabel, point[0], point[1]);
            }
        }
    }
    public void publishLive(String groupId, String deviceId, double lat, double lng) {
        if (ccstate.caltopoUpdatesEnabled) try {
            if (null == executorPool) {
                executorPool = Executors.newFixedThreadPool(threadPoolSize);
            }
            executorPool.submit(() -> {
                bgPublishLive(ccstate.groupID, mappedID, lat, lng);
            });
        } catch (Exception e) {
            Log.d(TAG, "executorPool.submit() raised:\n"+ e);
            if (null != executorPool) {
                executorPool.shutdown();
            }
            ccstate.caltopoUpdatesEnabled = false; // stop attempts to send msgs to caltopo.
        }
    }

    public boolean newWaypoint(double lat, double lng, long altitudeInMeters, long timestampInSeconds) {
        boolean archived = WaypointTrack.AddWaypointForTrack(mappedID, lat, lng, altitudeInMeters, timestampInSeconds);
        ClientClassState ccs = getState();
        if (archived) {
            // Then it is OK to publish this updated location
            //     Log.i(TAG, String.format("submitting job: %s %f %f", mappedID, lat, lng));
            if (ccs.useDirectFlag) {
                try {
                    publishDirect(lat, lng, altitudeInMeters);
                } catch (Exception e) {
                    Log.e(TAG, "publishDirect() raised:\n  " + e);
                }
                return true;
            }

            publishLive(ccs.groupID, mappedID, lat, lng);
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
        } catch (InvalidClassException e) {
            Log.d(TAG, "restoreState() not able to restore incompatible version of state:\n  " + e);
            ccs = null;
        } catch (Exception e) {
            Log.d(TAG, "restoreState() raised:\n  " + e);
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
            Log.d(TAG, "archiveState():%s" + ccstate.stringRep());

            //  Files.move(Paths.get(MyTemporaryStateFileName), Paths.get(MyStateFileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.d(TAG, String.format(Locale.US,
                    "archiveState() raised exception:\n  %s", e));
        }
    }

    public static void shutdown() {
        if (executorPool != null) {executorPool.shutdownNow();}
        if (csp != null) {
            CaltopoSession.shutdown();
        }
    }
}


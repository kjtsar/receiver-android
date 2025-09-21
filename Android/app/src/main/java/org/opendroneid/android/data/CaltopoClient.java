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
import android.content.ClipData;
import android.content.ClipboardManager;
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
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Hashtable;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.*;
import java.util.function.Function;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opendroneid.android.app.DebugActivity;

/*
 * Persistent state management for CaltopoClient
 */
class ClientClassState implements Serializable {
    private static final long SerialVersionUID = 15L; // Serializable version.
    public long minDistanceInFeet;
    public String groupId;
    public String archivePath;
    public Hashtable<String, CtDroneSpec> droneSpecTable;  // Table to map remoteIDs to their data
    public boolean caltopoUpdatesEnabled;
    public String caltopoTrackFolder;
    public CaltopoSessionConfig caltopoSessionConfig;
    public String mapId;
    public boolean useDirectFlag;
    public long newTrackDelayInSeconds;
    public long maxDisplayAgeInSeconds;

    // Default/initial state for the caltopo client:
    ClientClassState() {
        minDistanceInFeet = CaltopoClient.MIN_DISTANCE_IN_FEET;
        groupId = "";
        archivePath = null;
        caltopoUpdatesEnabled = false;
        caltopoTrackFolder = "Drone Tracks";
        caltopoSessionConfig = null;
        mapId = "";
        useDirectFlag = false;
        newTrackDelayInSeconds = 60;
        maxDisplayAgeInSeconds = 0 ;
        droneSpecTable = new Hashtable<>(16);
    }

    @Override
    @NonNull
    public String toString() {
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
                "vers:'%d', minDist:'%d' ft, groupId:'%s', mapId:'%s'\n" +
                        "newTrackDelayInSec:%d, maxDisplayAgeInSec:%d, caltopoUpdatesEnabled:%s,\n" +
                        "archivePath:%s, \n caltopoTrackFolder: '%s', caltopoDomainAndPort:%s, " +
                        "teamId: '%s', credId: '%s' credSecret: '%s', ht: %s",
                SerialVersionUID, minDistanceInFeet, groupId, mapId,
                newTrackDelayInSeconds, maxDisplayAgeInSeconds, caltopoUpdatesEnabled ? "true" : "false",
                (archivePath == null) ? "<undefined>" : archivePath,
                caltopoTrackFolder, domainAndPort, teamId, credId,
                credSecret, CaltopoClient.DroneSpecStringRep(droneSpecTable));
    }
}

public class CaltopoClient {
    // CaltopoClient CLASS VARS:
    static final long MIN_DISTANCE_IN_FEET = 2;
    static final long MIN_NEW_TRACK_DELAY_IN_SECONDS = 15;
    private static final String BASE_URL = "https://caltopo.com/api/v1/position/report/";
    private static final String TAG = "CaltopoClient";
    public static final String LoadConfigFileMessage = "Open Caltopo Configuration File";
    private static final int ThreadPoolSize = 1;
    private static CaltopoSession Csp;
    private static CaltopoOp OpenMapOp;
    private static String FolderId;
    private static CaltopoOp FolderIdOp;
    private static boolean MapConfigChanged = true;
    private static boolean MapDumpedToClipboard;
    private static boolean WarnMissingGroupId = false;
    private static boolean WarnLiveTrackFailed = false;
    private static Hashtable<String, CaltopoClient> ClientMap;
    private static ExecutorService ExecutorPool = null;
    private static ClientClassState Ccstate = null;
    private static ArrayList<CtDroneSpec> CurrentDroneSpecArray = null;
    private static long CurrentDroneSpecAge = 0;
    private static long CurrentDroneSpecTimestampInSec = 0;
    private static AppCompatActivity AppActivity = null;
    private static Context AppContext = null;
    private static final String MyStateFileName = TAG + ".ser";
    private static ActivityResultLauncher<Intent> QueryArchivePath;
    private static ActivityResultLauncher<Intent> LoadConfigFileLauncher;

    // CaltopoClient INSTANCE VARS:=
    private int trackSuffix;
    private String trackLabel;
    private CaltopoOp liveTrackOp;
    private String remoteId;
    private CtDroneSpec droneSpec;
    private LinkedList<double[]> linePoints; // array of arrays of [lat,lng] pairs
    private String startDateAndTime;

    public CaltopoClient(String rid) throws RuntimeException {
        ClientClassState ccs = GetState();

        if (null == rid || rid.isEmpty()) {
            throw new RuntimeException("CaltopoClient() constructor missing/invalid remoteId");
        }
        remoteId = rid;
        droneSpec = ccs.droneSpecTable.get(rid);  // try archived value first.
        if (null == droneSpec) {
            droneSpec = new CtDroneSpec(rid);
            ccs.droneSpecTable.put(rid, droneSpec);
            ArchiveState();
        }
    }

    public static CtDroneSpec DroneSpecForRemoteId(String rid) {
        ClientClassState ccs = GetState();
        return ccs.droneSpecTable.get(rid);  // try archived value first.
    }

    public static String getTrackFolderName() {
        ClientClassState ccs = GetState();
        return ccs.caltopoTrackFolder;
    }

    /**
     * Setting a null or empty track folder name is OK!.  That tells the client
     * to put the tracks in the default track directory.
     *
     * @param folderName Folder to put tracks into - may be null or empty.
     */
    public static void SetTrackFolderName(String folderName) {
        ClientClassState ccs = GetState();
        boolean stateChanged = false;
        if (null != folderName && null != ccs.caltopoTrackFolder) {
            if (!folderName.equals(ccs.caltopoTrackFolder)) {
                stateChanged = true;
            }
        } else if (null != ccs.caltopoTrackFolder || null != folderName) {
            stateChanged = true;
        }

        if (stateChanged) {
            MapConfigChanged = true;
            ccs.caltopoTrackFolder = folderName;
            ArchiveState();
        }
    }

    @Nullable
    public static CaltopoSessionConfig GetCaltopoConfig() {
        ClientClassState ccs = GetState();
        return ccs.caltopoSessionConfig;
    }

    /* This is the mapid portion of the caltopo map's URL. The
     * 'H61AV0G' portion of the following map URL:
     *      https://caltopo.com/m/H61AV0G
     */
    public static String SetMapId(String mapid) throws RuntimeException {
        ClientClassState ccs = GetState();
        mapid = mapid.trim().replaceAll("[^a-zA-Z0-9]", "");
        if (!mapid.equals(ccs.mapId)) {
            MapConfigChanged = true;
            ccs.mapId = mapid;
            ArchiveState();
        }
        return ccs.mapId;
    }

    public static String GetMapId() {
        ClientClassState ccs = GetState();
        return ccs.mapId;
    }

    public static void SetUseDirect(boolean flag) {
        ClientClassState ccs = GetState();
        if (ccs.useDirectFlag != flag) {
            MapConfigChanged = true;
            ccs.useDirectFlag = flag;
            ArchiveState();
        }
    }

    public static boolean GetUseDirectFlag() {
        ClientClassState ccs = GetState();
        return ccs.useDirectFlag;
    }

    @Nullable
    public static CaltopoSessionConfig GetCaltopoSessionConfig() {
        ClientClassState ccs = GetState();
        return ccs.caltopoSessionConfig;
    }

    public static void SetCaltopoSessionConfig(@NonNull CaltopoSessionConfig cfg)
            throws RuntimeException {
        if (!CaltopoSessionConfig.sniffTest(cfg)) {
            throw new RuntimeException("CaltopoSessionConfig.setCaltopoConfig() bad spec.");
        }

        ClientClassState ccs = GetState();

        if (!CaltopoSessionConfig.configSpecsAreEqual(cfg, ccs.caltopoSessionConfig)) {
            ccs.caltopoSessionConfig = cfg;
            MapConfigChanged = true;
            ArchiveState();
        }
    }

    public static JSONObject ReadJsonFile(Uri uri) {
        StringBuilder stringBuilder = new StringBuilder();
        InputStream is;
        InputStreamReader isr;
        BufferedReader bufferedReader;
        JSONObject retval;

        try {
            is = AppActivity.getContentResolver().openInputStream(uri);
            isr = new InputStreamReader(is);
            bufferedReader = new BufferedReader(isr);
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
        } catch (IOException e) {
            ShowToast(String.format(Locale.US, "Not able to read '%s' - %s", uri, e ));
            return null;
        }

        try {
            retval = new JSONObject(stringBuilder.toString());
        } catch (JSONException e) {
            ShowToast(String.format(Locale.US, "Not able to parse '%s' - %s", uri, e ));
            return null;
        }
        return retval;
    }
    public static void ShowToast(String msg) {
        ((DebugActivity)AppActivity).showToast(msg);
    }

    public static void readCredentialsFileContent(JSONObject json)
            throws JSONException {
        String teamId = json.optString("team_id", null);
        String credentialId = json.optString("credential_id", null);
        String credentialSecret = json.optString("credential_secret", null);
        String trackFolder = json.optString("track_folder", null);
        String mapId = json.optString("map_id", null);
        if (null == teamId || null == credentialId || null == credentialSecret) {
            throw new JSONException("Bad/missing config.  Require ea. of team_id, credential_id, credential_secret");
        }
        if (null != trackFolder) SetTrackFolderName(trackFolder);
        if (null != mapId) SetMapId(mapId);

        SetCaltopoSessionConfig(new CaltopoSessionConfig(teamId, credentialId, credentialSecret));
        ArchiveState();
    }
    public static void readRidmapFileContent(JSONObject json) throws JSONException {
        JSONArray map;
        try {
            map = json.optJSONArray("map");
        } catch (NullPointerException e) {
            map = null;
        }
        if (null == map) {
            ShowToast("No map specified in file.");
            return;
        }
        ClientClassState ccs = GetState();
        Hashtable<String, CtDroneSpec> mergedTable = new Hashtable<>(16);
        for (int i = 0; i < map.length(); i++) {
            JSONObject entry = map.getJSONObject(i);
            String rid = entry.optString("remoteId");
            String mid = entry.optString("mappedId");
            String org = entry.optString("org");
            String model = entry.optString("model");
            String owner = entry.optString("owner");
//                Log.i(TAG, String.format(Locale.US, "rid:%s, mid:%s, org:%s, model:%s owner:%s from entry: %s",
//                        rid, mid, org, model, owner, entry.toString(2)));

            CtDroneSpec ds = new CtDroneSpec(rid, mid, org, model, owner);
            CtDroneSpec existingDs = ccs.droneSpecTable.get(rid);
            if (null != existingDs) { // don't modify any existing values - just add values:
                existingDs.mergeWithNew(ds);
                ds = existingDs;
            }
            existingDs = mergedTable.get(rid);
            if (null != existingDs) {
                throw new JSONException(String.format(Locale.US,
                        "Illegal duplicate remoteId '%s' at offset %d - file contents ignored.", rid, i));
            }
            Log.i(TAG, String.format(Locale.US, "Adding dronespec:%s", ds));
            mergedTable.put(ds.remoteId, ds);
        }
        ccs.droneSpecTable = mergedTable;
        ArchiveState();
    }

    public static String LoadConfigFile(Uri uri) {
        if (null == uri) return null;
        try {
            JSONObject json = ReadJsonFile(uri);
            if (null == json) return null;
            //Log.i(TAG, String.format(Locale.US, "Loaded '%s':\n%s",
            //        uri, json.toString(2)));
            String type = json.optString("type").trim().toLowerCase();
            String fileVersion = json.optString("file_version");
            String updated = json.optString("updated");
            String editor = json.optString("editor");
            Log.i(TAG, String.format(Locale.US, "Reading v%s %s config file last updated by %s on %s",
                    fileVersion, type, editor, updated));

            if (type.equals("ct_ridmap")) {
                readRidmapFileContent(json);
            } else if (type.equals("ct_credentials")) {
                readCredentialsFileContent(json);
            }
        } catch (JSONException e) {
            Log.e(TAG, String.format(Locale.US, "Error processing '%s':\n", uri));
        }
        return null;
    }

    public static void RequestLoadConfigFile() {
        RequestConfigFile(LoadConfigFileMessage, LoadConfigFileLauncher);
    }

    public static void RequestConfigFile(String requestMessage, ActivityResultLauncher<Intent> launcher) {
        Intent requestFileIntent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        requestFileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        requestFileIntent.setType("application/json");

        try {
            launcher.launch(requestFileIntent);
        } catch (Exception e) {
            Log.e(TAG, String.format(Locale.US, "RequestConfigFile(%s).launcher() raised:\n  %s", requestMessage, e));
        }
    }

    public static void QueryUserForArchiveDir() {
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
        Log.d(TAG, String.format(Locale.US, "In QueryUserForArchiveDir(%s)", intent));
        try {
            QueryArchivePath.launch(intent);
        } catch (Exception e) {
            Log.wtf(TAG, String.format(Locale.US, "queryUserForArchiveDir() raised:'%s'", e));
        }
    }
    private static ActivityResultLauncher<Intent> InitLauncherForConfigFile(String requestMessage, Function<Uri, String> fileProcessor) {
        return AppActivity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        Log.i(TAG, String.format(Locale.US, "In InitLauncherForConfigFile(%s):onActivityResult(%s)",
                                requestMessage, result.toString()));
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            Intent data = result.getData();
                            if (null != data) {
                                Uri jsonUri = data.getData();
                                // Persist the URI for later use (e.g., in SharedPreferences)
                                // Now you have a Uri representing the selected file:
                                fileProcessor.apply(jsonUri);
                            }
                        }
                    }
                });
    }


    public static ActivityResultLauncher<Intent> InitLauncherForArchiveDir() {
        return AppActivity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        Log.d(TAG, String.format(Locale.US, "In queryArchivePath:onActivityResult(%s)", result.toString()));
                        if (result.getResultCode() == Activity.RESULT_OK) {
                            Intent data = result.getData();
                            if (null != data) {
                                Uri treeUri = data.getData();
                                if (null != treeUri) {
                                    // Persist the URI for later use (e.g., in SharedPreferences)
                                    AppActivity.getContentResolver().takePersistableUriPermission(treeUri,
                                            Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);

                                    // Now you have a Uri representing the selected directory tree, which likely includes Downloads
                                    // You can use DocumentFile to work with files within this directory
                                    SetArchivePath(treeUri.toString());
                                }
                            }
                        }
                    }
                });
    }

    public static void InitializeForActivityAndContext(AppCompatActivity activity, Context ctxt) {
        AppActivity = activity;
        AppContext = ctxt;
        try {
            QueryArchivePath = InitLauncherForArchiveDir();
            LoadConfigFileLauncher = InitLauncherForConfigFile(LoadConfigFileMessage, CaltopoClient::LoadConfigFile);
        } catch (Exception e) {
            Log.e(TAG, String.format(Locale.US, "InitializeForActivityAndContext() raised:\n  %s", e));
        }
    }

    public static CaltopoClient ClientForRemoteId(String remoteId)
            throws RuntimeException {
        if (null == ClientMap) {
            ClientMap = new Hashtable<>(16);
        }
        if (null == remoteId || remoteId.isEmpty()) {
            throw new RuntimeException("CaltopoClient.ClientForRemoteId(): Invalid remoteId");
        }
        CaltopoClient client = ClientMap.get(remoteId);
        if (null == client) {
            client = new CaltopoClient(remoteId);

            ClientMap.put(remoteId, client);
            ArchiveState();
//            Log.i(TAG, String.format(Locale.US,
//                    "CaltopoClient() mapped %s to a new client ", remoteId));
        }
        return client;
    }

    public static String DroneSpecStringRep(Hashtable<String, CtDroneSpec> ht) {
        int count = (null != ht) ? ht.size() : 0;
        StringBuilder retval = new StringBuilder(String.format(Locale.US, "%d k/v pairs:", count));

        if (null != ht) {
            for (Map.Entry<String, CtDroneSpec> map : ht.entrySet()) {
                CtDroneSpec ds = map.getValue();
                retval.append("\n  " + ds);
            }
        }
        return retval.toString();
    }


    // can return null if no stored state available.
    private static ClientClassState RestoreState() {
        ClientClassState ccs;
        try {
            FileInputStream fis = AppContext.openFileInput(MyStateFileName);
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

    private static ClientClassState GetState() {
        if (null == Ccstate) {
            AppContext = DebugActivity.getAppContext();
            ClientClassState ccs = RestoreState();
            if (null == ccs) ccs = new ClientClassState();
            Ccstate = ccs;
            Log.d(TAG, "GetState()" + Ccstate);
        }
        return Ccstate;
    }

    private static void ArchiveState() {
        if (null != Ccstate) try {
            FileOutputStream fos = AppContext.openFileOutput(MyStateFileName, 0);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(Ccstate);
            oos.flush();
            oos.close();
            Log.d(TAG, "ArchiveState():%s" + Ccstate);

            //  Files.move(Paths.get(MyTemporaryStateFileName), Paths.get(MyStateFileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.d(TAG, String.format(Locale.US,
                    "ArchiveState() raised exception:\n  %s", e));
        }
        CurrentDroneSpecTimestampInSec = 0; // state has changed, so invalidate CurrentDroneSpecArray
    }

    /**
     *
     *
     * @param ageInSeconds   use zero to get all entries.
     * @return Return an array of CtDroneSpecs for drones that have been seen
     * * within the previous ageInSeconds.
     */
    @NonNull
    public static ArrayList<CtDroneSpec> GetSortedCurrentDroneSpecArray(long ageInSeconds) {
        ClientClassState ccs = GetState();
        long currentTimeInSeconds = (System.currentTimeMillis() / 1000);

        if (currentTimeInSeconds != CurrentDroneSpecTimestampInSec || CurrentDroneSpecAge != ageInSeconds) {
            long ageOutInSeconds = currentTimeInSeconds - ageInSeconds;

            ArrayList<CtDroneSpec> dsArray = new ArrayList<>(ccs.droneSpecTable.size());
            for (Map.Entry<String, CtDroneSpec> map : ccs.droneSpecTable.entrySet()) {
                CtDroneSpec ds = map.getValue();
                if ((0 == ageInSeconds) || ds.mostRecentTimeInSeconds >= ageOutInSeconds)
                    dsArray.add(ds);
            }
            dsArray.sort(null);
            CurrentDroneSpecArray = dsArray;
            CurrentDroneSpecAge = ageInSeconds;
            CurrentDroneSpecTimestampInSec = currentTimeInSeconds;
        }
        return CurrentDroneSpecArray;
    }
    public static String GetGroupId() {
        ClientClassState ccs = GetState();
        return ccs.groupId;
    }

    public static long GetNewTrackDelayInSeconds() {
        ClientClassState ccs = GetState();
        return ccs.newTrackDelayInSeconds;
    }

    public static long GetMaxDisplayAgeInSeconds() {
        ClientClassState ccs = GetState();
        return ccs.maxDisplayAgeInSeconds;
    }
    public static long GetMinDistanceInFeet() {
        ClientClassState ccs = GetState();
        return ccs.minDistanceInFeet;
    }

    public static String GetArchivePath() {
        ClientClassState ccs = GetState();
        return ccs.archivePath;
    }

    public static void SetArchivePath(String path)
            throws RuntimeException {
        if (null == path || path.isEmpty()) {
            throw new RuntimeException("CaltopoClient.SetArchivePath() invalid path.");
        }
        ClientClassState ccs = GetState();
        ccs.archivePath = path;
        ArchiveState();
    }

    public static String SetGroupId(String gid) {
        ClientClassState ccs = GetState();
        if (gid != null && !gid.isEmpty()) {
            ccs.caltopoUpdatesEnabled = true;
            ccs.groupId = gid.trim();
        } else {
            ccs.caltopoUpdatesEnabled = false;
            ccs.groupId = "";
        }
        ArchiveState(); // save any time there is a chg.
        WarnMissingGroupId = false;
        return ccs.groupId;
    }

    public static void Shutdown() {
        if (ExecutorPool != null) {
            ExecutorPool.shutdownNow();
        }
        if (Csp != null) {
            CaltopoSession.Shutdown();
        }
    }


    // CaltopoClient instance methods:

    @Override
    @NonNull
    public String toString() {
        return String.format(Locale.US,
                "  rid:%s, mapped:%s", remoteId, droneSpec.mappedId);
    }

    public static long SetNewTrackDelayInSeconds(long delayInSeconds) {
        ClientClassState ccs = GetState();

        if (delayInSeconds < MIN_NEW_TRACK_DELAY_IN_SECONDS) {
            delayInSeconds = MIN_NEW_TRACK_DELAY_IN_SECONDS;
        }

        if (ccs.newTrackDelayInSeconds != delayInSeconds) {
            ccs.newTrackDelayInSeconds = delayInSeconds;
            ArchiveState();
        }
        return ccs.newTrackDelayInSeconds;
    }

    public static long SetMaxDisplayAgeInSeconds(long delayInSeconds) {
        ClientClassState ccs = GetState();
        if (ccs.maxDisplayAgeInSeconds != delayInSeconds) {
            ccs.maxDisplayAgeInSeconds = delayInSeconds;
            ArchiveState();
        }
        return ccs.maxDisplayAgeInSeconds;
    }

    public static long setMinDistanceInFeet(long minDistance) {
        ClientClassState ccs = GetState();
        if (minDistance < MIN_DISTANCE_IN_FEET) {
            minDistance = MIN_DISTANCE_IN_FEET;
        }
        if (ccs.minDistanceInFeet != minDistance) {
            ccs.minDistanceInFeet = minDistance;
            ArchiveState();
        }
        return ccs.minDistanceInFeet;
    }

    public CtDroneSpec getDroneSpec() {
        return droneSpec;
    }

    public CtDroneSpec setDroneSpec(@NonNull CtDroneSpec newSpec) {
        boolean specChanged = false;
        if (droneSpec.sameAs(newSpec)) {
            Log.d(TAG, "setDroneSpec(): No change - ignoring.");
            return newSpec;
        }

        // You can't change remoteId - that's sacred
        if (!droneSpec.remoteId.equals(newSpec.remoteId)) {
            Log.d(TAG, "setDroneSpec(): Can't change remoteId - ignoring.");
            return droneSpec;
        }

        if (null != newSpec.mappedId && !newSpec.mappedId.isEmpty() &&
                !droneSpec.mappedId.equals(newSpec.mappedId)) {
            specChanged = true;
        }
        if (null == newSpec.org) newSpec.org = "";
        else if (!newSpec.org.equals(droneSpec.org)) specChanged = true;

        if (null == newSpec.owner) newSpec.owner = "";
        else if (!newSpec.owner.equals(droneSpec.owner)) specChanged = true;

        if (null == newSpec.model) newSpec.model = "";
        else if (!newSpec.model.equals(droneSpec.model)) specChanged = true;

        if (specChanged) {
            ClientClassState ccs = GetState();
            droneSpec = newSpec;
            ccs.droneSpecTable.put(remoteId, newSpec);
            ArchiveState();
        }
        return newSpec;
    }


    public void finishTrack() {
        Log.d(TAG, "finishTrack(): Requesting a new track for " + droneSpec.mappedId);
        trackSuffix++;liveTrackOp = null;
    }

    // this is used when there is no Caltopo Session defined:
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
            ExecutorPool.shutdown();
            ClientClassState ccs = GetState();
            ccs.caltopoUpdatesEnabled = false; // stop attempts to send msgs to caltopo.
        }
    }

    public long unsavedMsgCount() {
        return WaypointTrack.UnsavedMsgCountForTrack(droneSpec.mappedId);
    }
    public void CopyStringToClipboard(String message) {
        ClipboardManager clippy = (ClipboardManager) AppContext.getSystemService(Context.CLIPBOARD_SERVICE);
        clippy.setPrimaryClip(ClipData.newPlainText("OpenDroneId", message));
    }

    /**
     * Apologies for the bit of a state machine going on here, but there's
     * a process for bringing up a map connection.  Need to configure and
     * establish the session, then open the map, then peruse the existing
     * map to see if the folder for our tracks is present and grab it's
     * id if it is.  If folder not present, we need to create it and get
     * its id when that process completes.   All this happens in an
     * environment where network connectivity can drop out at any point
     * and the user can change the credentials, folder name, or the
     * mapid at any time, so it requires a bit of flexibility.
     *
     * @return returns true once it's OK to start publishing tracks.
     */
    public boolean caltopoMapIsUp() throws RuntimeException {
        if (null == Csp || MapConfigChanged) {
            linePoints = new LinkedList<>();
            OpenMapOp = null;
            FolderIdOp = null;
            FolderId = null;
            liveTrackOp = null;
            MapConfigChanged = false;
            MapDumpedToClipboard = false;

            if (null == Ccstate.caltopoSessionConfig) {
                Log.e(TAG, "caltopoMapIsUp(): Can't establish caltopo connection - missing config spec.");
                return false;
            }
        }

        if (null == Csp) {
            Csp.SetCfg(Ccstate.caltopoSessionConfig);
            Csp = new CaltopoSession();
        }

        if (null == OpenMapOp && Ccstate.mapId != null && !Ccstate.mapId.isEmpty()) {
            try {
                Log.i(TAG, String.format(Locale.US, "Opening map '%s'", Ccstate.mapId));
                OpenMapOp = Csp.openMap(Ccstate.mapId);
            } catch (Exception e) {
                Log.e(TAG, "caltopoMapIsUp(): csp.openMap() raised: " + e);
            }
        }
        if ((null == OpenMapOp) || (!OpenMapOp.isDone())) return false;
        if (OpenMapOp.fail()) {
            ShowToast(String.format(Locale.US, "Not able to open map '%s':\n  %s",
                    GetMapId(), OpenMapOp.responseString()));
            SetMapId("");
            return false;
        } else if (!MapDumpedToClipboard) {
            CopyStringToClipboard(OpenMapOp.responseString());
            MapDumpedToClipboard = true;
        }

        if (FolderId == null && FolderIdOp == null) {
            // See if requested folder is already present - get it's folderId if so.
            // While we're at it, lets make sure we're using a unique track name as well.
            int suffixIndex = droneSpec.mappedId.length() + 1; // skip the connecting '-' hyphen

            String folderName;
            if (null != Ccstate.caltopoTrackFolder && !Ccstate.caltopoTrackFolder.isEmpty()) {
                folderName = Ccstate.caltopoTrackFolder;
            } else {
                folderName = "Lines & Polygons";
            }

            try {
                JSONObject state = OpenMapOp.responseJson.getJSONObject("state");
                JSONArray features = state.getJSONArray("features");
                for (int i = 0; i < features.length(); i++) {
                    JSONObject feature = features.getJSONObject(i);
                    // Log.i(TAG, "Parsing returned feature:\n" + feature.toString(2));
                    JSONObject prop = feature.getJSONObject("properties");
                    String title = prop.getString("title");
                    String classProp = prop.getString("class");
                    if (FolderId == null && classProp.equals("Folder") && title.equals(folderName)) {
                        // Found it - get it's ID
                        FolderId = feature.getString("id");

                        Log.i(TAG, String.format(Locale.US, "Found existing folder '%s' with id %s", folderName, FolderId));
                        continue;
                    }
                    if ((classProp.equals("LiveTrack") || classProp.equals("Shape")) &&
                            title.startsWith(droneSpec.mappedId)) {
                        Log.i(TAG, String.format(Locale.US, "Found %s with drone prefix '%s'", classProp, title));
                        if (title.length() > suffixIndex) {
                            // skip the '-' hyphen
                            String suffix = title.substring(suffixIndex);
                            if (!suffix.isEmpty()) {
                                int suffixVal = Integer.parseInt(suffix);
                                if (suffixVal >= trackSuffix) {
                                    trackSuffix = suffixVal + 1;
                                }
                            }
                        } else if (0 == trackSuffix) trackSuffix++;
                    }
                }
                if (0 == trackSuffix) {
                    trackSuffix = 1;
                }
                Log.i(TAG, "trackSuffix after processing features:" + trackSuffix);
            } catch (JSONException e) {
                Log.i(TAG, "Error parsing existing map data:" + e + "\n  " +
                        OpenMapOp.responseString());
            }
        }

        // Request the directory to be created if it wasn't found in the map dump
        if (null == FolderId) {
            if (null == FolderIdOp) {
                try {
                    FolderIdOp = Csp.addFolder(Ccstate.caltopoTrackFolder,
                            true, true);
                } catch (Exception e) {
                    Log.e(TAG, "caltopoMapIsUp(): csp.addFolder raised: " + e);
                }
                return false;
            } else if (!FolderIdOp.isDone()) return false;

            // get folderId from returned create op:
            if (FolderIdOp.isDone()) {
                if (FolderIdOp.fail()) {
                    ShowToast(String.format(Locale.US,
                            "Could not create folder in map '%s' - check mapId/permissions:\n  %s",
                            GetMapId(), FolderIdOp.responseString()));

                    return false;
                }
                try {
                    FolderId = FolderIdOp.id();
                } catch (JSONException e) {
                    ShowToast("caltopoMapIsUp(): folderIdOp.id() raised: " + e);
                }
                Log.i(TAG, String.format(Locale.US, "folderid for op %d is %s", FolderIdOp.opNum, FolderId));
            }
        }
        return true;
    }

    public void publishDirect(double lat, double lng, long altitudeInMeters)
            throws JSONException, RuntimeException, InterruptedException {

        if (null == linePoints) {
            linePoints = new LinkedList<>();
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("ddLLL@HH:mm:ss");
            startDateAndTime = LocalDateTime.now().format(formatter);
        }

        // add this new point to the list.
        double[] point = {lat, lng};
        linePoints.add(point);

        if (caltopoMapIsUp()) {
            // start sending our point list to caltopo if it seems to be up and running:
            if (null != FolderId ) {
                if (null == liveTrackOp) {
                    if (trackSuffix > 0) {
                        trackLabel = droneSpec.mappedId + "-" + trackSuffix;
                    } else trackLabel = droneSpec.mappedId;
                    String description = String.format(Locale.US,
                            "start:%s, org:%s, model:%s", startDateAndTime,
                            droneSpec.org, droneSpec.model);
                    Log.i(TAG,"publishDirect(): Starting LiveTrack " + Ccstate.groupId + "-" + trackLabel);
                    liveTrackOp = Csp.startLiveTrack(Ccstate.groupId, trackLabel, FolderId, description, null);
                }
                if (liveTrackOp.isDone()) {
                    if (liveTrackOp.fail()) {
                        if (!WarnLiveTrackFailed)
                            ShowToast(String.format(Locale.US, "Not able to open/write LiveTrack for group:'%s-%s':\n  %s",
                                    Ccstate.groupId, trackLabel, liveTrackOp.responseString()));
                        WarnLiveTrackFailed = true;
                        finishTrack();
                        return;
                    }
                    // only send one waypoint at a time and verify succesful response before sending the next.
                    point = linePoints.removeFirst();
                    Log.i(TAG, "publishDirect(): adding waypoint to LiveTrack " + Ccstate.groupId + "-" + trackLabel);
                    liveTrackOp = Csp.addLiveTrackPoint(Ccstate.groupId, trackLabel, point[0], point[1]);
                }
            }
        }
    }

    public void publishLive(double lat, double lng) {
        if (Ccstate.caltopoUpdatesEnabled) try {
            if (null == ExecutorPool) {
                ExecutorPool = Executors.newFixedThreadPool(ThreadPoolSize);
            }
            ExecutorPool.submit(() -> {
                bgPublishLive(Ccstate.groupId, droneSpec.mappedId, lat, lng);
            });
        } catch (Exception e) {
            Log.d(TAG, "executorPool.submit() raised:\n" + e);
            if (null != ExecutorPool) {
                ExecutorPool.shutdown();
            }
            Ccstate.caltopoUpdatesEnabled = false; // stop attempts to send msgs to caltopo.
        }
    }

    public boolean newWaypoint(double lat, double lng, long altitudeInMeters, long droneTimestampInSeconds) {
        boolean archived = WaypointTrack.AddWaypointForTrack(droneSpec.mappedId, lat, lng, altitudeInMeters, droneTimestampInSeconds);
        ClientClassState ccs = GetState();
        long currentTimeInSec = (System.currentTimeMillis() / 1000);

        if (archived) {
            if (Ccstate.groupId.isEmpty()) {
                if (!WarnMissingGroupId) {
                    ShowToast("Can't forward waypoint to caltopo - 'groupId' not specified in Caltopo Config panel.");
                    WarnMissingGroupId = true;
                    return archived;
                }
            }

            // Then it is OK to publish this updated location
            //     Log.i(TAG, String.format("submitting job: %s %f %f", droneSpec.mappedID, lat, lng));
            if (ccs.useDirectFlag && !ccs.mapId.isEmpty()) {
                long idleDuration = currentTimeInSec - droneSpec.mostRecentTimeInSeconds;
                if ((null != liveTrackOp) && (0 != droneSpec.mostRecentTimeInSeconds) &&
                        (idleDuration >= GetNewTrackDelayInSeconds())) {
                    Log.d(TAG, String.format(Locale.US,
                            "Finishing track for %s after %d seconds idle between waypoints.",
                            droneSpec.mappedId, idleDuration));
                    finishTrack();
                }

                try {
                    publishDirect(lat, lng, altitudeInMeters);
                } catch (Exception e) {
                    Log.e(TAG, "publishDirect() raised:\n  " + e);
                }
            } else if (!Ccstate.groupId.isEmpty()){
                publishLive(lat, lng);
            } else {
                Log.i(TAG,"newWaypoint(): Ignoring waypoint - missing " + (ccs.useDirectFlag ? "mapId" : "groupId"));
            }
        }
        droneSpec.mostRecentTimeInSeconds = currentTimeInSec;
        return archived;
    }
}


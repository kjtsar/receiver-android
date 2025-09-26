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
import android.content.ContentResolver;
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
import androidx.documentfile.provider.DocumentFile;

import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
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
    private static final long SerialVersionUID = 16L; // Serializable version.
    public long minDistanceInFeet;
    public String groupId;
    public String archivePath;
    public Hashtable<String, CtDroneSpec> droneSpecTable;  // Table to map remoteIDs to their data
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
        caltopoTrackFolder = "Drone Tracks";
        caltopoSessionConfig = null;
        mapId = "";
        useDirectFlag = false;
        newTrackDelayInSeconds = 20;
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
                        "newTrackDelayInSec:%d, maxDisplayAgeInSec:%d, \n " +
                        "archivePath:%s, \n caltopoTrackFolder: '%s', caltopoDomainAndPort:%s, " +
                        "teamId: '%s', credId: '%s' credSecret: '%s', ht: %s",
                SerialVersionUID, minDistanceInFeet, groupId, mapId,
                newTrackDelayInSeconds, maxDisplayAgeInSeconds,
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
    private static final int DebugLevelError = 0;
    private static final int DebugLevelDebug = 1;
    private static final int DebugLevelInfo = 2;
    private static int DebugLevel = DebugLevelDebug;
    private static final int ThreadPoolSize = 1;
    private static CaltopoSession Csp;
    private static CaltopoOp OpenMapOp;
    private static String FolderId;
    private static CaltopoOp FolderIdOp;
    private static boolean MapConfigChanged = true;
    private static boolean MapDumpedToLog;
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
    private static OutputStream DebugOutputStream;
    private static long BytesWrittenToDebugOutputStream;
    private static final long MAX_SIZE_DEBUG_OUTPUT = 10000000;

    // CaltopoClient INSTANCE VARS:=
    private String trackLabel;
    private CaltopoOp liveTrackOp;
    private final String remoteId;
    private CtDroneSpec droneSpec;
    private String trackGroupId; // groupId used to start current track
    private String trackMappedId; // mappedId used to start current track
    private LinkedList<double[]> linePoints; // array of arrays of [lat,lng] pairs
    private boolean outstandingLabelChangeRequest;

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
            ArchiveState("dronespec changed for " + rid);
        }
    }

    public static String BumpLoggingLevel() {
        DebugLevel++;
        if (DebugLevel > DebugLevelInfo) DebugLevel = DebugLevelError;
        String name = switch (DebugLevel) {
            case DebugLevelError -> "Errors only";
            case DebugLevelDebug -> "Debugs";
            case DebugLevelInfo -> "Info";
            default -> "<undefined>";
        };
        return name;
    }

    public static void CTLog(String type, String tag, String msg) {
        if (null == AppActivity || null == AppContext) {
            // attempt no logging to file without app context (caused by
            // logging from ScanningService during screen rotate).
            return;
        }
        ClientClassState ccs = GetState();
        if (BytesWrittenToDebugOutputStream >= MAX_SIZE_DEBUG_OUTPUT) return;

        if (null == ccs.archivePath || null == AppContext) return;
        if (null == DebugOutputStream) try {
            DocumentFile todaysArchiveDir = GetTodaysTrackDir();
            String filepath = "Log" + DateTimestampString();
            if (null != todaysArchiveDir) {
                DocumentFile dataFilepath = todaysArchiveDir.createFile("text/plain", filepath);
                ContentResolver resolver = AppContext.getContentResolver();
                DebugOutputStream = (resolver.openOutputStream(dataFilepath.getUri()));
            }
            Log.i(TAG, "Writing logs to " + filepath);
        } catch (IOException e) {
            Log.e(TAG, "Not able to open DebugOutputStream: " + e);
            return;
        }

        try {
            msg = String.format(Locale.US, "%s@%.3f:%s  %s\n\n  ", type,
                    (double)System.currentTimeMillis()/1000.0, tag, msg);
            byte[] bytes = msg.getBytes();
            BytesWrittenToDebugOutputStream += bytes.length;
            DebugOutputStream.write(bytes);
            DebugOutputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, String.format(Locale.US, "Not able to write '%s' - %s", ccs.archivePath, e));
        }
        if (BytesWrittenToDebugOutputStream >= MAX_SIZE_DEBUG_OUTPUT) {
            Log.e(TAG, "CTLog(): Sorry.  Maximum debugging output file size reached.  Future bits will be tossed on the floor.");
        }
    }

    public static void CTInfo(String tag, String msg){
        if ( DebugLevel >= DebugLevelInfo) {
            CTLog("INFO", tag, msg);
            msg = "CTInfo: " + msg;
            Log.i(tag, msg);
        }
    }

    public static void CTDebug(String tag, String msg){
        if (DebugLevel >= DebugLevelDebug) {
            CTLog("DEBUG", tag, msg);
            msg = "CTDebug: " + msg;
            Log.d(tag, msg);
        }
    }

    public static void CTError(String tag, String msg) {
        CTLog("ERROR", tag, msg);
        msg = "CTError: " + msg;
        Log.e(tag, msg);
    }

    public static String ExceptionToString(Exception e) {
        StringBuilder str = new StringBuilder();
        str.append(e);
        StackTraceElement[] stackTrace = e.getStackTrace();
        for (StackTraceElement element : stackTrace) {
            str.append("\n    ");
            str.append(element);
        }
        return str.toString();
    }

    public static void CTError(String tag, String msg, Exception e) {
        StringBuilder str = new StringBuilder();
        str.append(msg);
        str.append("\n  ");
        str.append(ExceptionToString(e));
        CTLog("ERROR", tag, str.toString());
        str.insert(0, "CTError: ");
        Log.e(tag, str.toString());
    }

    public static CtDroneSpec DroneSpecForRemoteId(String rid) {
        ClientClassState ccs = GetState();
        return ccs.droneSpecTable.get(rid);  // try archived value first.
    }

    /**
     * Create/find a directory within the ArchiveDir with todays date
     * and return that as the directory to place trackfiles and logs in.
     *
     * @return DocumentFile path to existing directory on success and
     * null on failure.
     */
    public static DocumentFile GetTodaysTrackDir() {
        String archivePath = GetArchivePath();
        DocumentFile todaysDir = null;
        if (null != archivePath) try {
            Uri treeUri = Uri.parse(archivePath);
            DocumentFile archiveDir = DocumentFile.fromTreeUri(AppContext, treeUri);
            if (null != archiveDir) {
                SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy", Locale.US);
                String dirpath = "tracks-" + sdf.format(new Date());
                todaysDir = archiveDir.findFile(dirpath);
                if (null == todaysDir) {
                    todaysDir = archiveDir.createDirectory(dirpath);
                    if (null == todaysDir) {
                        Log.e(TAG, String.format("GetTodaysTrackDir(): Not able to create '%s'", archiveDir));
                    } else {
                        Log.d(TAG, String.format("GetTodaysTrackDir(): Created '%s'", archiveDir));
                    }
                } else {
                    Log.d(TAG, String.format("GetTodaysTrackDir(): found existing '%s'", archiveDir));
                }
            }
        } catch (Exception e) {
            CTError(TAG, "Not able to create today's archive dir", e);
        }
        return todaysDir;
    }

    public static String GetTrackFolderName() {
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
            ArchiveState( "Caltopo Track Folder changed.");
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
            ArchiveState("mapId changed to " + mapid);
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
            ArchiveState("useDirectChanged to " + flag);
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

    // returns true if changes archived.
    public static boolean SetCaltopoSessionConfig(@NonNull CaltopoSessionConfig cfg)
            throws RuntimeException {
        if (!CaltopoSessionConfig.sniffTest(cfg)) {
            throw new RuntimeException("CaltopoSessionConfig.setCaltopoConfig() bad spec.");
        }

        ClientClassState ccs = GetState();
        if (!CaltopoSessionConfig.configSpecsAreEqual(cfg, ccs.caltopoSessionConfig)) {
            ccs.caltopoSessionConfig = cfg;
            MapConfigChanged = true;
            ArchiveState( "SessionConfigChanged to " + cfg);
            return true;
        }
        return false;
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
            ShowToast(String.format(Locale.US, "Not able to read '%s'", uri), e );
            return null;
        }

        try {
            retval = new JSONObject(stringBuilder.toString());
        } catch (JSONException e) {
            ShowToast(String.format(Locale.US, "Not able to parse '%s'", uri) , e);
            return null;
        }
        return retval;
    }
    public static void ShowToast(String msg) {
        CTError(TAG, "showToast():" + msg);
        ((DebugActivity)AppActivity).showToast(msg);
    }
    public static void ShowToast(String msg, Exception e) {
        CTError(TAG, "showToast():" + msg, e);
        msg = msg + "\n" + ExceptionToString(e);
        ((DebugActivity)AppActivity).showToast(msg);
    }

    public static void readCredentialsFileContent(JSONObject json)
            throws JSONException {
        boolean changedFlag = false;
        String teamId = json.optString("team_id", null);
        String credentialId = json.optString("credential_id", null);
        String credentialSecret = json.optString("credential_secret", null);
        String trackFolder = json.optString("track_folder", null);
        String mapid = json.optString("map_id", null);
        String groupid = json.optString("group_id", null);

        if (null == teamId || null == credentialId || null == credentialSecret) {
            throw new JSONException("Bad/missing config.  Require ea. of team_id, credential_id, credential_secret");
        }
        if (null != trackFolder) SetTrackFolderName(trackFolder);
        if (null != mapid) SetMapId(mapid);
        if (null != groupid) SetGroupId(groupid);

        SetCaltopoSessionConfig(new CaltopoSessionConfig(teamId, credentialId, credentialSecret));
    }
    public static void readRidmapFileContent(JSONObject json) throws JSONException {
        JSONArray mapJson;
        try {
            mapJson = json.optJSONArray("map");
        } catch (NullPointerException e) {
            mapJson = null;
        }
        if (null == mapJson) {
            ShowToast("No map specified in file.");
            return;
        }
        ClientClassState ccs = GetState();
        Hashtable<String, CtDroneSpec> mergedTable = new Hashtable<>(16);
        CtDroneSpec ds;
        for (int i = 0; i < mapJson.length(); i++) {
            JSONObject entry = mapJson.getJSONObject(i);
            String rid = entry.optString("remoteId");
            String mid = entry.optString("mappedId");
            String org = entry.optString("org");
            String model = entry.optString("model");
            String owner = entry.optString("owner");
//                Log.i(TAG, String.format(Locale.US, "rid:%s, mid:%s, org:%s, model:%s owner:%s from entry: %s",
//                        rid, mid, org, model, owner, entry.toString(2)));

            ds = new CtDroneSpec(rid, mid, org, model, owner);
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
            CTDebug(TAG, String.format(Locale.US, "Adding dronespec:%s", ds));
            mergedTable.put(ds.getRemoteId(), ds);
        }

        // Be sure to include any existing maps that weren't mentioned in the file:
        for (Map.Entry<String, CtDroneSpec> map : ccs.droneSpecTable.entrySet()) {
            String key = map.getKey();
            if (null == mergedTable.get(key)) {
                mergedTable.put(key, map.getValue());
            }
        }
        ccs.droneSpecTable = mergedTable;
        ArchiveState("merged ridmap");
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
            CTDebug(TAG, String.format(Locale.US, "Reading v%s %s config file last updated by %s on %s",
                    fileVersion, type, editor, updated));

            if (type.equals("ct_ridmap")) {
                readRidmapFileContent(json);
            } else if (type.equals("ct_credentials")) {
                readCredentialsFileContent(json);
            }
        } catch (JSONException e) {
            CTError(TAG, String.format(Locale.US,"Error processing '%s':", uri), e);
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
            CTError(TAG, String.format(Locale.US, "RequestConfigFile(%s).launcher() raised:", requestMessage), e);
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
        CTDebug(TAG, String.format(Locale.US, "In QueryUserForArchiveDir(%s)", intent));
        try {
            QueryArchivePath.launch(intent);
        } catch (Exception e) {
            CTError(TAG, "queryUserForArchiveDir() raised:", e);
        }
    }
    private static ActivityResultLauncher<Intent> InitLauncherForConfigFile(String requestMessage, Function<Uri, String> fileProcessor) {
        return AppActivity.registerForActivityResult(new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<>() {
                    @Override
                    public void onActivityResult(ActivityResult result) {
                        CTDebug(TAG, String.format(Locale.US, "In InitLauncherForConfigFile(%s):onActivityResult(%s)",
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
                        CTDebug(TAG, String.format(Locale.US, "In queryArchivePath:onActivityResult(%s)", result.toString()));
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
            CTError(TAG, "InitializeForActivityAndContext() raised:", e);
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
        if (null == AppContext) return null;
        try {
            FileInputStream fis = AppContext.openFileInput(MyStateFileName);
            ObjectInputStream ois = new ObjectInputStream(fis);
            ccs = (ClientClassState) ois.readObject();
            ois.close();
        } catch (FileNotFoundException e) {
            Log.e(TAG, "restoreState() no archive to restore from:", e);
            ccs = null;
        } catch (InvalidClassException e) {
            Log.e(TAG, "restoreState() not able to restore incompatible version of state:", e);
            ccs = null;
        } catch (Exception e) {
            Log.e(TAG, "restoreState() raised:", e);
            ccs = null;
        }
        return ccs;
    }

    private static ClientClassState GetState() {
        if (null == Ccstate) {
            AppContext = DebugActivity.getAppContext();
            if (null == AppContext) {
                Log.d(TAG, "GetState() called before AppContext initialized.");
            }
            ClientClassState ccs = RestoreState();
            if (null == ccs) ccs = new ClientClassState();
            Ccstate = ccs;
            Log.d(TAG, "GetState()" + Ccstate);
        }
        return Ccstate;
    }

    private static void ArchiveState(String reason) {
        if (null != Ccstate) try {
            FileOutputStream fos = AppContext.openFileOutput(MyStateFileName, 0);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(Ccstate);
            oos.flush();
            oos.close();
            CTDebug(TAG, String.format(Locale.US, "ArchiveState(%s):\n%s", reason, Ccstate));

            //  Files.move(Paths.get(MyTemporaryStateFileName), Paths.get(MyStateFileName), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            CTError(TAG, "ArchiveState() raised:", e);
        }
        CurrentDroneSpecTimestampInSec = 0; // state has changed, so invalidate CurrentDroneSpecArray
    }

    /**
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
        ArchiveState("archivePath changed.");
    }

    public static String SetGroupId(String gid) {
        ClientClassState ccs = GetState();
        String oldGid = ccs.groupId;

        if (gid != null && !gid.isEmpty()) {
            ccs.groupId = gid.replaceAll("[^A-Z0-9]", "");
        } else {
            ccs.groupId = "";
        }
        if (!oldGid.equals(ccs.groupId)) {
            WarnMissingGroupId = false;
            ArchiveState("groupId changed."); // save any time there is a chg.
        }
        return ccs.groupId;
    }

    public static void Shutdown() {
        if (ExecutorPool != null) {
            ExecutorPool.shutdownNow();
        }
        if (Csp != null) {
            CaltopoSession.Shutdown();
        }
        if (null != DebugOutputStream) {
            try {
                DebugOutputStream.flush();
                DebugOutputStream.close();
                DebugOutputStream = null;
            } catch (IOException e) {
                Log.e(TAG, "Shutdown raised: " + e);
            }
        }
    }


    // CaltopoClient instance methods:

    @Override
    @NonNull
    public String toString() {
        return String.format(Locale.US,
                "  rid:%s, mapped:%s", remoteId, droneSpec.getMappedId());
    }

    public static long SetNewTrackDelayInSeconds(long delayInSeconds) {
        ClientClassState ccs = GetState();

        if (delayInSeconds < MIN_NEW_TRACK_DELAY_IN_SECONDS) {
            delayInSeconds = MIN_NEW_TRACK_DELAY_IN_SECONDS;
        }

        if (ccs.newTrackDelayInSeconds != delayInSeconds) {
            ccs.newTrackDelayInSeconds = delayInSeconds;
            ArchiveState("newTrackDelayInSeconds changed");
        }
        return ccs.newTrackDelayInSeconds;
    }

    public static long SetMaxDisplayAgeInSeconds(long delayInSeconds) {
        ClientClassState ccs = GetState();
        if (ccs.maxDisplayAgeInSeconds != delayInSeconds) {
            ccs.maxDisplayAgeInSeconds = delayInSeconds;
            ArchiveState("maxDisplayAgeInSeconds changed.");
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
            ArchiveState("minDistanceInFeet changed");
        }
        return ccs.minDistanceInFeet;
    }

    public static String DateTimestampString() {
        // Yes, we really want the timestamp first to make it easier to spot
        // the latest track in caltopo's tiny feature window.
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("HHmmssLLLdd");
        return LocalDateTime.now().format(formatter);
    }

    public CtDroneSpec getDroneSpec() {
        return droneSpec;
    }

    public String newTrackLabel() {
        return droneSpec.getMappedId() + "_" + DateTimestampString();
    }

    public void finishTrack(@NonNull String reason) {
        if (null != liveTrackOp) {
            String newTrackLabel = newTrackLabel();
            CTDebug(TAG, String.format(Locale.US,
                    "finishTrack(%s:%s): starting new track: %s",
                    trackLabel, reason, newTrackLabel));
            trackLabel = newTrackLabel;
            liveTrackOp = null;
        } else {
            trackLabel = null;
            CTDebug(TAG, "Request to finish non-existent track.  " + reason);
        }
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
                CTError(TAG, "https bad response: " + responseCode);
            }
        } catch (IOException e) {
            CTError(TAG, "openConnection() raised:", e);
            ExecutorPool.shutdown();
            ClientClassState ccs = GetState();
            SetGroupId(""); // prevent new attempts til problem resolved.
        }
    }

    public long unsavedMsgCount() {
        if (null == trackLabel) return 0;
        return WaypointTrack.UnsavedMsgCountForTrack(trackLabel);
    }

    /*
    public static void CopyStringToClipboard(String message) {
        ClipboardManager clippy = (ClipboardManager) AppContext.getSystemService(Context.CLIPBOARD_SERVICE);
        clippy.setPrimaryClip(ClipData.newPlainText("OpenDroneId", message));
    }
    */

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
    public boolean caltopoMapIsUp(String mapId) throws RuntimeException, JSONException, InterruptedException {
        if (null == Csp || MapConfigChanged) {
            linePoints = new LinkedList<>();
            OpenMapOp = null;
            FolderIdOp = null;
            FolderId = null;
            liveTrackOp = null;
            trackLabel = null;
            MapConfigChanged = false;
            MapDumpedToLog = false;

            if (null == Ccstate.caltopoSessionConfig) {
                CTError(TAG, "caltopoMapIsUp(): Can't establish caltopo connection - missing config spec.");
                return false;
            }
            CTInfo(TAG, "caltopoMapIsUp() starting or MapConfigChanged.");
        }

        if (null == Csp) {
            Csp.SetCfg(Ccstate.caltopoSessionConfig);
            Csp = new CaltopoSession();
            CTInfo(TAG, "caltopoMapIsUp() created session.");
        }

        if (null == OpenMapOp && mapId != null && !mapId.isEmpty()) {
            try {
                CTDebug(TAG, String.format(Locale.US, "Opening map '%s'", mapId));
                OpenMapOp = Csp.openMap(mapId);
            } catch (Exception e) {
                CTError(TAG, "caltopoMapIsUp(): csp.openMap() raised:", e);
            }
        }
        if ((null == OpenMapOp) || (!OpenMapOp.isDone())) {
            CTInfo(TAG, "caltopoMapIsUp() waiting for openMap() to complete...");
            return false;
        }
        if (OpenMapOp.fail()) {
            ShowToast(String.format(Locale.US, "Not able to open map '%s':\n  %s",
                    mapId, OpenMapOp.responseString()));
            SetMapId("");
            return false;
        } else if (!MapDumpedToLog) {
            CTInfo(TAG, "caltopoMapIsUp() dumping map to logfile...");
            CTDebug(TAG, OpenMapOp.responseString());
            MapDumpedToLog = true;
        }

        if (FolderId == null && FolderIdOp == null) {
            // See if requested folder is already present - get it's folderId if so.
            // While we're at it, lets make sure we're using a unique track name as well.

            String folderName;
            if (null != Ccstate.caltopoTrackFolder && !Ccstate.caltopoTrackFolder.isEmpty()) {
                folderName = Ccstate.caltopoTrackFolder;
            } else {
                folderName = "Lines & Polygons";
            }
            CTInfo(TAG, "caltopoMapIsUp() Checking map for folder" + folderName);
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
                    CTDebug(TAG, String.format(Locale.US, "Found existing folder '%s' with id %s", folderName, FolderId));
                    break;
                }
            }
        }

        // Request the directory to be created if it wasn't found in the map dump
        if (null == FolderId) {
            CTInfo(TAG, "caltopoMapIsUp() folder not found - creating...");
            if (null == FolderIdOp) {
                FolderIdOp = Csp.addFolder(Ccstate.caltopoTrackFolder,
                        true, true);
                return false;
            } else if (!FolderIdOp.isDone()) return false;

            // get folderId from returned create op:
            if (FolderIdOp.isDone()) {
                if (FolderIdOp.fail()) {
                    ShowToast(String.format(Locale.US,
                            "Could not create folder in map '%s' - check mapId/permissions:\n  %s",
                            mapId, FolderIdOp.responseString()));
                    return false;
                }
                FolderId = FolderIdOp.id();
                CTDebug(TAG, String.format(Locale.US, "folderid for op %d is %s", FolderIdOp.opNum, FolderId));
            }
        }
        return true;
    }

    public void publishDirect(double lat, double lng, long altitudeInMeters, String mapId, String groupId)
            throws JSONException, RuntimeException, InterruptedException {

        if (null == linePoints) linePoints = new LinkedList<>();

        // add this new point to the list.
        double[] point = {lat, lng};
        linePoints.add(point);

        if (caltopoMapIsUp(mapId)) {
            CTInfo(TAG, "publishDirect() map is up.");
            if (groupId.isEmpty() || (null == trackLabel) || trackLabel.isEmpty()) {
                CTDebug(TAG, "publishDirect() - map is up, but groupId/label missing - this should have been caught by caller.");
                return;
            }
            // start sending our point list to caltopo if it seems to be up and running:
            if (null != FolderId ) {
                CTInfo(TAG, "publishDirect() we have a folderId.");
                if (null == liveTrackOp) {
                    String description = String.format(Locale.US,
                            "org:%s, model:%s, owner:%s", droneSpec.getOrg(), droneSpec.getModel(), droneSpec.getOwner());
                    CTDebug(TAG, String.format(Locale.US, "publishDirect(%s-%s): Starting LiveTrack.",
                            groupId, trackLabel));
                    liveTrackOp = Csp.startLiveTrack(groupId, trackLabel, FolderId, description, null);
                }
                if (liveTrackOp.isDone()) {
                    if (liveTrackOp.fail()) {
                        if (!WarnLiveTrackFailed) {
                            ShowToast(String.format(Locale.US, "Not able to open/write LiveTrack for group:'%s-%s':\n  %s",
                                    groupId, trackLabel, liveTrackOp.responseString()));
                            WarnLiveTrackFailed = true;
                        }
                        finishTrack("Not able to open/write LiveTrack");
                        return;
                    }
                    while (!linePoints.isEmpty()) {
                        point = linePoints.removeFirst();
                        CTDebug(TAG, String.format(Locale.US, "publishDirect(%s-%s): adding %.7f,%.7f to LiveTrack ",
                                groupId, trackLabel, point[0], point[1]));
                        liveTrackOp = Csp.addLiveTrackPoint(groupId, trackLabel, point[0], point[1]);
                    }
                }
            }
        }
    }

    public void publishLive(double lat, double lng, String groupId) {
        try {
            if (null == ExecutorPool) {
                ExecutorPool = Executors.newFixedThreadPool(ThreadPoolSize);
            }
            ExecutorPool.submit(() -> {
                bgPublishLive(groupId, droneSpec.getMappedId(), lat, lng);
            });
        } catch (Exception e) {
            CTError(TAG, "executorPool.submit() raised:", e);
            if (null != ExecutorPool) {
                ExecutorPool.shutdown();
            }
            SetGroupId(""); // prevent further messages to caltopo til problem resolved.
        }
    }

    public void userResponseForLabelChange(CtDroneSpec droneSpec, String existingLabel, boolean permitChange) {
        String msg = "userResponseForLabelChange:" + permitChange;
        CTDebug(TAG,msg);
        outstandingLabelChangeRequest = false;
        if (permitChange) {
            finishTrack(msg);
        } else {
            droneSpec.setMappedId(existingLabel);
        }
    }

    public boolean newWaypoint(double lat, double lng, long altitudeInMeters, long droneTimestampInSeconds, String transportType) {
        boolean droneTakingOff = false;
        boolean useDirectFlag = GetUseDirectFlag();
        String mapId = GetMapId();
        String groupId = GetGroupId();

        if (null == liveTrackOp) {
            if (null == trackLabel) trackLabel = newTrackLabel();
            trackGroupId = groupId;
            trackMappedId = droneSpec.getMappedId();
        }

        if (null != trackGroupId && !trackGroupId.equals(groupId)) {
            finishTrack("newWaypoint(): User changed groupId");
        }

        if (null != trackMappedId && !trackMappedId.equals(droneSpec.getMappedId())) {
            // we have an active track the user is trying to change the label on,
            // so make sure it's intentional.
            if (!outstandingLabelChangeRequest) {
                outstandingLabelChangeRequest = true;
                DebugActivity.ConfirmActiveTrackLabelChange(this, droneSpec, trackMappedId);
            }
        }

        if (-1000 == altitudeInMeters) {
            // -1000 is invalid value in open_drone_id - possibly associated with taking off.
            droneTakingOff = true;
            CTInfo(TAG, String.format(Locale.US,
                    "newWaypoint(%s/%s) w/Invalid altitude @ %.7f,%.7f - Drone is likely taking off.",
                    trackLabel, transportType, lat, lng));
        }
        boolean archived = WaypointTrack.AddWaypointForTrack(trackLabel, lat, lng, altitudeInMeters, droneTimestampInSeconds, transportType);
        if (archived) {
            CTInfo(TAG, "newWaypoint() archived.");

            if (useDirectFlag && !mapId.isEmpty()) {
                if (groupId.isEmpty()) {
                    if (!WarnMissingGroupId) {
                        ShowToast("Can't forward waypoint to caltopo - 'groupId' not specified in Caltopo Config panel.");
                        WarnMissingGroupId = true;
                    }
                    return archived;
                }
                long idleDuration;
                if (0 == droneSpec.mostRecentTimeInSeconds) {
                    idleDuration = 0;
                } else {
                    idleDuration = droneTimestampInSeconds - droneSpec.mostRecentTimeInSeconds;
                }
                if ( (null != liveTrackOp) &&
                        (droneTakingOff || (idleDuration > GetNewTrackDelayInSeconds())) ) {
                    String msg = String.format(Locale.US,
                            "Finishing track for %s after %d seconds idle between waypoints. takingOff:%s",
                            trackLabel, idleDuration, droneTakingOff);
                    CTDebug(TAG, msg);
                    finishTrack(msg);
                    return true;
                }

                try {
                    publishDirect(lat, lng, altitudeInMeters, mapId, groupId);
                } catch (Exception e) {
                    CTError(TAG, "publishDirect() raised:", e);
                }
            } else if (!groupId.isEmpty()) {
                try {
                    publishLive(lat, lng, groupId);
                } catch (Exception e) {
                    CTError(TAG, "publishLive() raised:", e);
                }
            } else if (mapId.isEmpty()){
                CTDebug(TAG,"newWaypoint(): Ignoring waypoint - missing mapId.");
            } else {
                CTDebug(TAG,"newWaypoint(): Ignoring waypoint - missing groupId");
            }
        }
        droneSpec.mostRecentTimeInSeconds = droneTimestampInSeconds;
        return archived;
    }
}


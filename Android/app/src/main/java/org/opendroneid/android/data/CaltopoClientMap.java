package org.opendroneid.android.data;

import static org.opendroneid.android.data.CaltopoClient.CTInfo;
import static org.opendroneid.android.data.CaltopoClient.CTDebug;
import static org.opendroneid.android.data.CaltopoClient.CTError;
import static org.opendroneid.android.data.CaltopoClient.ShowToast;

import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.opendroneid.android.app.DebugActivity;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

/**
 * Support bringing up the map session, creating the drone track folders (if not already
 * present).
 * The archive folder starts with the drone track folder name and ends with the
 * current date (i.e. 25Sep).
 *
 */
public class CaltopoClientMap {
    private static final String TAG = "CaltopoClientMap";
    private static CaltopoSession Csp;
    private static String MyPublicIpAddress = null;
    private static String MyUUID = null;
    private CaltopoOp openMapOp;
    private String folderId;
    private CaltopoOp folderIdOp;
    private String archiveFolderId;
    private CaltopoOp archiveFolderIdOp;
    private boolean mapDumpedToLog;
    private CaltopoSessionConfig sessionConfig;
    private String mapId;
    private String folderName;
    private String openMapFailedMsg;
    private boolean mapIsUp;
    private JSONArray shapeFeatures;
    private JSONArray markerFeatures;
    private CtLineProperty archiveLineProp;

    public CaltopoClientMap(@NonNull CaltopoSessionConfig config, @NonNull String mapId, @NonNull String folderName)
            throws RuntimeException {
        sessionConfig = config;
        if (mapId.isEmpty()) throw new RuntimeException("CaltopoClientMap(): mapId must be specified.");
        this.mapId = mapId;
        if (folderName.isEmpty()) folderName = "DroneTracks";
        this.folderName = folderName;
        if (null == MyUUID) SetMyUUID();
        startMapConnection();
    }

    public static void SetMyUUID() {
        Context ctxt = DebugActivity.getAppContext();
        if (null == ctxt) {
            DelayedExec.RunAfterDelayInMsec(CaltopoClientMap::SetMyUUID, 1000);
            CTDebug(TAG, "SetMyUUID() waiting for app to initialize...");
            return;
        }
        ContentResolver contentResolver = ctxt.getContentResolver();
        String androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID);
        UUID deviceUuid = UUID.nameUUIDFromBytes(androidId.getBytes(StandardCharsets.UTF_8));
        MyUUID = deviceUuid.toString();
        // FIXME: once I have a handle to the drone tracks folder, I need to create and or
        //  edit a Marker instance for this app in that folder.   Can I use my own UUUID
        //  as it's identifier?
    }

    public static void SetMyPublicIp(String myIpAddress) {
        MyPublicIpAddress = myIpAddress;
    }


    public void changeMap(@NonNull String newMapId) {
        if (mapIsUp) {
            CTDebug(TAG, String.format(Locale.US, "changeMap() changing from '%s' to '%s'.",
                    mapId, newMapId));
            // there is no close map operation - we only change to the new map and
            // the old references eventually go away...
            mapId = newMapId;
            startMapConnection();
        }
    }

    public String getMapId() {return mapId;}

    public void setMapId(@NonNull String newMapId) {
        if (newMapId.isEmpty()) {
            // user wants to shut down the map connection;
            resetMapConnection();
        } else if (!newMapId.equals(mapId)) {
            mapId = newMapId;
            startMapConnection();
        }
    }

    private void resetMapConnection() {
        openMapOp = null;
        folderIdOp = null;
        folderId = null;
        archiveFolderIdOp = null;
        archiveFolderId = null;
        mapDumpedToLog = false;
        openMapFailedMsg = null;
        mapIsUp = false;
    }

    private void startMapConnection() {
        resetMapConnection();
        if (null == Csp) {
            Csp = new CaltopoSession(sessionConfig);
            CTInfo(TAG, "startMapConnection() created session.");
        }
        try {
            CTDebug(TAG, String.format(Locale.US, "Opening map '%s'", mapId));
            openMapOp = Csp.openMap(mapId, this::openMapFinished);
        } catch (Exception e) {
            CTError(TAG, "startMapConnection(): csp.openMap() raised:", e);
        }
    }


    private void createArchiveDirFinished() {
        if (archiveFolderIdOp.fail()) {
            ShowToast(String.format(Locale.US,
                    "Could not create archive folder in map '%s' - check mapId/permissions:\n  %s",
                    mapId, archiveFolderIdOp.responseString()));
            return;
        }
        archiveFolderId = archiveFolderIdOp.id();
        CTDebug(TAG, String.format(Locale.US, "archive folder id is %s", archiveFolderId));

        processOldShapes();
    }

    private void createTrackDirFinished() {
        if (folderIdOp.fail()) {
            ShowToast(String.format(Locale.US,
                    "Could not create track folder in map '%s' - check mapId/permissions:\n  %s",
                    mapId, folderIdOp.responseString()));
            return;
        }
        folderId = folderIdOp.id();
        CTDebug(TAG, String.format(Locale.US, "track folder id is %s", folderId));
        processOldShapes();
    }

    /* Parse the feature set returned by the openMap()
     * to look for our track directory and it's companion archive dir.
     * Also make a list of all other Shape and LiveTrack that might
     * be old tracks in need of archival.
     */
    private void parseMap(JSONObject state)
            throws RuntimeException, JSONException, InterruptedException {

        shapeFeatures = new JSONArray();
        markerFeatures = new JSONArray();
        SimpleDateFormat sdf = new SimpleDateFormat("ddMMM", Locale.US);
        String archiveFolderName = folderName + sdf.format(new Date());

        CTInfo(TAG, String.format(Locale.US,
                "parseMap() Checking map for folders: '%s' and '%s'",
                folderName, archiveFolderName));

        JSONArray features = state.getJSONArray("features");

        for (int i = 0; i < features.length(); i++) {
            JSONObject feature = features.getJSONObject(i);
            CTInfo(TAG, "Parsing returned feature:\n" + feature.toString(2));
            JSONObject prop = feature.optJSONObject("properties");
            String title = prop.optString("title");
            if (title.isEmpty()) {
                CTError(TAG, "parseMap(): feature missing title: " + feature.toString(4));
// This happened a few times during trial and error with Caltopo's v1 API.  No way to delete
//                these runt features w/in the GUI, so...
//                String id = feature.optString("id");
//                if (!id.isEmpty()) Csp.deleteShapeWithId(id, null);
                continue;
            }
            String classProp = prop.optString("class", "");
            switch (classProp) {
                case "" -> {
                    CTError(TAG, "parseMap(): feature missing class: " + feature.toString(4));
                }
                case "Marker" -> {
                    markerFeatures.put(feature);
                }
                case "Shape", "LiveTrack" ->
                    // collect the list of features that might be ours - don't know if they
                    // are in our folder yet, because we may not even have folders.
                        shapeFeatures.put(feature);
                case "Folder" -> {
                    if (folderId == null && title.equals(folderName)) {
                        folderId = feature.getString("id");
                        CTDebug(TAG, String.format(Locale.US,
                                "Found existing folder '%s' with id %s", folderName, folderId));
                    } else if (null == archiveFolderId && title.equals(archiveFolderName)) {
                        archiveFolderId = feature.getString("id");
                        CTDebug(TAG, String.format(Locale.US,
                                "Found existing folder '%s' with id %s", archiveFolderName, archiveFolderId));
                    }
                }
            }
        }

        // Request the directories to be created if they weren't found in the existing map:
        if (null == folderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", folderName));
            folderIdOp = Csp.addFolder(folderName,true, true, this::createTrackDirFinished);
        }

        if (null == archiveFolderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", archiveFolderName));
            archiveFolderIdOp = Csp.addFolder(archiveFolderName, false, false, this::createArchiveDirFinished);
        }
        processOldShapes();
    }


    /**
     * Called when openMapOp completed.
     *   o Parse the returned map, look for existing TrackDir and ArchiveDir.
     *   o Also look for any old live tracks that didn't get archived (happens
     *     when the app was terminated mid-record).
     *   o Create TrackDir and ArchiveDir if they weren't already present.
     */
    private void openMapFinished() {
        if (mapId.isEmpty()) return;
        if (openMapOp.fail()) {
            openMapFailedMsg = String.format(Locale.US,"Not able to open map '%s':\n  %s",
                    mapId, openMapOp.responseString());
            ShowToast(openMapFailedMsg);
            mapId = "";
            return;
        }

        if (!mapDumpedToLog) {
            CTDebug(TAG, "openMapFinished() dumping map to logfile...");
            CTDebug(TAG, openMapOp.responseString());
            mapDumpedToLog = true; // this means map is up.
        }

        try {
            parseMap(openMapOp.responseJson.getJSONObject("state"));
        } catch (Exception e) {
            CTError(TAG, "openMapFinished(): parseMap raised:", e);
        }
        mapIsUp = true;
    }

    public CtLineProperty getArchiveLineProp() {
        if (null == archiveLineProp) archiveLineProp = new CtLineProperty("2", "1", "#ff00ff", "solid");
        return archiveLineProp;
    }

    /* Returns null if the map isn't up and/or the track folder isn't yet known,
     * otherwise returns an array of any host entries that were found, each of the form:
     *   {
     *      ipaddr: <ipaddr>,
     *      lat: <lat>,
     *      lng: <lng>,
     *      id: <marker_uuid>
     *   }
     */
    public JSONArray getR2cPeers() {
        JSONArray ar;

        if (!mapIsUp || null == folderId || null == markerFeatures ||
                0 == markerFeatures.length()) return null;
        ar = new JSONArray();
        try {
            for (int i = 0; i < markerFeatures.length(); i++) {
                JSONObject feature = markerFeatures.optJSONObject(i);
                JSONObject prop = feature.optJSONObject("properties");
                if (null == prop) continue;
                String featureFolderId = prop.optString("folderId");
                if (folderId.equals(featureFolderId)) {
                    // FIXME: Can we stuff a field that doesn't get displayed - just in case
                    // someone accidentally edits our title... let's see...
                    String title = prop.optString("title");
                    if (title.startsWith("R2C_")) {
                        JSONObject marker = new JSONObject();
                        // found a marker in the drone folder - is it one of ours:
                        marker.put("ipaddr", title.substring(4));
                        marker.put("backup_ipaddr", prop.optString("ipaddr"));
                        marker.put("id", feature.optString("id"));
                        JSONObject geometry = feature.optJSONObject("geometry");
                        JSONArray coordinates = geometry.optJSONArray("coordinates");
                        if (null != coordinates && coordinates.length() > 1) {
                            marker.put( "lat", coordinates.optString(1));
                            marker.put( "lng", coordinates.optString(0));
                        }
                    }
                }
            }
            CTDebug(TAG, "getR2cPeer() returning: " + ar.toString(4));
        } catch (Exception e) {
            CTError(TAG, "getR2cPeers(): Error parsing map.", e);
        }
        return ar;
    }


    private void processOldShapes() {
        if (null == folderId || null == archiveFolderId) return;
        long timeNowInMilliseconds = System.currentTimeMillis();
        long maxTrackAgeInMilliseconds = CaltopoClient.GetNewTrackDelayInSeconds() * 1000;

        CTInfo(TAG, String.format(Locale.US,
                "Parsing %d features to check for idle items in the drone folder",
                shapeFeatures.length()));

        while (0 != shapeFeatures.length()) {
            JSONObject feature = (JSONObject)shapeFeatures.remove(0);
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) continue;

            // only interested in features w/in the drone tracks directory:
            String featureFolderId = prop.optString("folderId", "");
            if (!featureFolderId.equals(folderId)) continue;

            // found a feature in the drone folder.
            String featureClass = prop.optString("class", null);
            CTDebug(TAG, String.format(Locale.US, "Found a %s in drone folder", featureClass));
            String lastUpdatedStr = prop.optString("updated", "");
            long lastUpdatedInMilliseconds = Long.parseLong(lastUpdatedStr);
            long trackAgeInMilliseconds = timeNowInMilliseconds - lastUpdatedInMilliseconds;

            if (trackAgeInMilliseconds < maxTrackAgeInMilliseconds) {
                CTDebug(TAG, String.format(Locale.US,
                        "%s feature last update was only %.3f seconds ago - ignoring",
                        featureClass, (double)trackAgeInMilliseconds / 1000.0));
                continue;
            }

            // found a feature in the drone folder old enough to archive.
            CTDebug(TAG, String.format(Locale.US, "%s last updated %.3f seconds ago - archiving.",
                    featureClass, (double)trackAgeInMilliseconds/1000.0));
            archiveFeature(feature, featureClass, timeNowInMilliseconds);
        }
    }

    /* feature is the complete feature description.   featureClass is the type of feature
     * that is being archived.  All are archived ultimately as 'Shape' class, but if
     * specified feature is a LiveTrack, the LiveTrack is deleted after archiving it's
     * state as a Shape.   That's the way the Caltopo v1 API wants it to happen.
     */
    public void archiveFeature(@NonNull JSONObject feature, @NonNull String featureClass,
                               long timeNowInMilliseconds) {
        String timeString = String.valueOf(timeNowInMilliseconds);
        if (null == archiveLineProp) getArchiveLineProp();
        if (null == archiveFolderId) {
            CTError(TAG, "archiveFeature(): can't archive - folder not created yet.");
            return;
        }
        try {
            JSONObject prop = feature.getJSONObject("properties");
            String trackId = feature.optString("id", "");
            if (trackId.isEmpty()) {
                CTError(TAG, "archiveFeature(): id for feature is empty - this shouldn't happen.\n  " +
                        feature.toString(4));
                return;
            }
            prop.put("stroke", archiveLineProp.color);
            prop.put("stroke-width", archiveLineProp.width);
            prop.put("stroke-opacity", archiveLineProp.opacity);
            prop.put("pattern", archiveLineProp.pattern);
            prop.put("folderId", archiveFolderId);
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            prop.put("class", "Shape");  // convert from LiveTrack to shape.
            Csp.editObjectWithId("Shape", trackId, feature, null);
            if (featureClass.equals("LiveTrack")) {
                CTDebug(TAG, String.format(Locale.US, "archiveFeature(): Stopping liveTrack %s....", trackId));
                Csp.deleteLiveTrackWithId(trackId, null);  // Then delete LiveTrack.
            }
        } catch (Exception e) {
            CTError(TAG, "archiveFeature() raised:", e);
        }
    }

    public boolean mapIsUp() {
        return this.mapIsUp;
    }

    /* N.B. map can be up, but folders not yet created, in which case these
     * will return null... patience.
     */
    @Nullable
    public String getFolderId() { return folderId; }
    @Nullable
    public String getArchiveFolderId() { return archiveFolderId; }

    /* Use with caution.
     * With great power comes great responsibility...
     */
    public  CaltopoSession session() {return Csp;}

    public static void Shutdown() {
        CaltopoSession.Shutdown();
    }
}

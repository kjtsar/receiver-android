package org.opendroneid.android.data;

import static org.opendroneid.android.data.CaltopoClient.CTInfo;
import static org.opendroneid.android.data.CaltopoClient.CTDebug;
import static org.opendroneid.android.data.CaltopoClient.CTError;
import static org.opendroneid.android.data.CaltopoClient.ShowToast;
import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
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
    private static String MyUUID = null;
    private static android.location.Location MyLocation;
    private static ArrayList<CaltopoClientMap> Maps = null;
    private CaltopoOp openMapOp;
    private String folderId;
    private CaltopoOp folderIdOp;
    private CaltopoOp myMarkerOp;
    private String archiveFolderId;
    private CaltopoOp archiveFolderIdOp;
    private boolean mapDumpedToLog;
    private CaltopoSessionConfig sessionConfig;
    private String mapId;
    private String folderName;
    private String openMapFailedMsg;
    private boolean mapIsUp;
    private JSONArray shapeFeatures;
    private int waitForGpsAccuracy;
    private JSONArray r2cPeers;
    private CtLineProperty archiveLineProp;
    private final ArrayList<CaltopoLiveTrack> liveTracks;

    public CaltopoClientMap(@NonNull CaltopoSessionConfig config, @NonNull String mapId, @NonNull String folderName)
            throws RuntimeException {
        sessionConfig = config;
        if (mapId.isEmpty())
            throw new RuntimeException("CaltopoClientMap(): mapId must be specified.");
        this.mapId = mapId;
        liveTracks = new ArrayList<>(16);
        if (folderName.isEmpty()) folderName = "DroneTracks";
        this.folderName = folderName;
        if (null == MyUUID) SetMyUUID();
        if (null == Maps) Maps = new ArrayList<>(16);
        Maps.add(this);
        startMapConnection();
    }

    public void addLiveTrack(@NonNull CaltopoLiveTrack track) {
        liveTracks.add(track);
    }

    public static float DistanceFromMeInMeters(double lat, double lng) {
        float[] dbResult = {Float.NaN};
        if (null == MyLocation || !MyLocation.hasAccuracy()) return Float.NaN;
        Location.distanceBetween(lat, lng, MyLocation.getLatitude(), MyLocation.getLongitude(), dbResult);
        return dbResult[0];
    }

    public static void SetMyUUID() {
        Context ctxt = DebugActivity.getAppContext();
        if (null == ctxt) {
            DelayedExec.RunAfterDelayInMsec(CaltopoClientMap::SetMyUUID, 1000);
            CTDebug(TAG, "SetMyUUID() waiting for app to initialize...");
            return;
        }
        ContentResolver contentResolver = ctxt.getContentResolver();
        // android studio warns: "Using 'GetString' to get device identifiers is not recommended",
        // but nothing in the method spec mentions this...
        String androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID);
        UUID deviceUuid = UUID.nameUUIDFromBytes(androidId.getBytes(StandardCharsets.UTF_8));
        MyUUID = deviceUuid.toString();
        // FIXME: once I have a handle to the drone tracks folder, I need to create and or
        //  edit a Marker instance for this app in that folder.   Can I use my own UUUID
        //  as it's identifier?
    }

    public void setMapId(@NonNull String newMapId) {
        if (newMapId.isEmpty()) {
            // user wants to shut down the map connection;
            resetMapConnection();

        } else if (!newMapId.equals(mapId)) {
            CTDebug(TAG, String.format(Locale.US, "setMapId() changing from '%s' to '%s'.",
                    mapId, newMapId));
            for (CaltopoLiveTrack track : liveTracks) {
                track.finishTrack("Map change");
            }
            // Then delete our marker before switching over to the new map.
            CaltopoOp op = Csp.deleteMarkerWithId(MyUUID, null);
            try {
                op.syncOpJSONObject(3);
                if (op.success()) {
                    CTDebug(TAG, String.format(Locale.US, "Marker removed in %.3f seconds",
                            (double) op.roundTripTimeInMsec() / 1000.0));
                }
            } catch (Exception e) {
                CTError(TAG, "changeMap(): deleteMarkerWithId() raised:", e);
            }

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
        if (null != folderId && null != archiveFolderId) mapIsUp = true;
        lookForOldShapes();
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
        if (null != folderId && null != archiveFolderId) mapIsUp = true;
    }

    /* Parse the feature set returned by the openMap()
     * to look for our track directory and it's companion archive dir.
     * Also make a list of all other Shape and LiveTrack that might
     * be old tracks in need of archival.
     * FIXME: Seems like this could take a long time on a multi-op period search, so
     *  might want to be able to do this in a background thread.   Also, would be nice
     *  to be able to get map updates (to see if there are new R2C peers that we missed
     *  when we started.
     */
    private void parseMap(JSONObject state)
            throws RuntimeException, JSONException {

        shapeFeatures = new JSONArray();
        JSONArray markerFeatures = new JSONArray();
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
            if (null == prop) {
                CTError(TAG, "feature missing properties - skipping:" + feature);
                continue;
            }
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
                case "" -> CTError(TAG, "parseMap(): feature missing class: " + feature.toString(4));
                case "Marker" -> markerFeatures.put(feature);
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
            folderIdOp = Csp.addFolder(folderName, true, true, this::createTrackDirFinished);
        }
        findR2cPeers(markerFeatures);


        if (null == archiveFolderId) {
            CTInfo(TAG, String.format(Locale.US,
                    "parseMap() '%s' folder not found - creating...", archiveFolderName));
            archiveFolderIdOp = Csp.addFolder(archiveFolderName, false, false, this::createArchiveDirFinished);
        } else lookForOldShapes();
    }


    /**
     * Called when openMapOp completed.
     * o Parse the returned map, look for existing TrackDir and ArchiveDir.
     * o Also look for any old live tracks that didn't get archived (happens
     * when the app was terminated mid-record).
     * o Create TrackDir and ArchiveDir if they weren't already present.
     */
    private void openMapFinished() {
        if (mapId.isEmpty()) return;
        if (openMapOp.fail()) {
            openMapFailedMsg = String.format(Locale.US, "Not able to open map '%s':\n  %s",
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
    }

    public CtLineProperty getArchiveLineProp() {
        if (null == archiveLineProp)
            archiveLineProp = new CtLineProperty(2, 0.5F, "#ff00ff", "solid");
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
    public void findR2cPeers(@NonNull JSONArray markerFeatures) {
        r2cPeers = new JSONArray();
        if (0 == markerFeatures.length()) {
            processPeerList();
            return;
        }

        try {
            for (int i = 0; i < markerFeatures.length(); i++) {
                JSONObject feature = markerFeatures.optJSONObject(i);
                JSONObject prop = feature.optJSONObject("properties");
                if (null == prop) continue;
                String featureFolderId = prop.optString("folderId");
                if (featureFolderId.equals(folderId)) {
                    String ipaddr = prop.optString("r2c-ipaddr");
                    if (ipaddr.isEmpty()) continue;
                    // found one of our markers in the drone folder:
                    JSONObject marker = new JSONObject();
                    marker.put("ipaddr", ipaddr);
                    marker.put("uuid", prop.optString("r2c-uuid"));
                    marker.put("id", feature.optString("id"));
                    marker.put("feature", feature);
                    JSONObject geometry = feature.optJSONObject("geometry");
                    if (null != geometry) {
                        JSONArray coordinates = geometry.optJSONArray("coordinates");
                        if (null != coordinates && coordinates.length() > 1) {
                            marker.put("lat", coordinates.optString(1));
                            marker.put("lng", coordinates.optString(0));
                        }
                    }
                    r2cPeers.put(marker);
                }
            }
            CTDebug(TAG, "getR2cPeer() returning: " + r2cPeers.toString(4));
        } catch (Exception e) {
            CTError(TAG, "getR2cPeers(): Error parsing map.", e);
        }
        processPeerList();
    }


    public static void UpdateMyLocation(@NonNull android.location.Location location) {
        if ( null == MyLocation || !MyLocation.hasAccuracy() ||
                (location.hasAccuracy() && location.getAccuracy() < MyLocation.getAccuracy())) {
            MyLocation = location;
        }
    }

    private int GetPeerCount() {
        return (null == r2cPeers) ? 0: r2cPeers.length();
    }

    @Nullable
   private String GetPeerAddress(int peerOffset) {
        if (peerOffset < r2cPeers.length()) {
            JSONObject peer = r2cPeers.optJSONObject(peerOffset);
            return peer.optString("ipaddr");
        }
        return null;
    }


    /* Returns distance from lat/lng to peer in meters or Float.NaN if bad parameter.
     */
    private double GetDistanceToPeerInMeters(int peerOffset, double lat, double lng) {
        float[] dbResult = {Float.NaN};
        if (peerOffset >= r2cPeers.length()) return Float.NaN;
        JSONObject peer = r2cPeers.optJSONObject(peerOffset);
        if (null == peer) return Float.NaN;
        double myLat = peer.optDouble("lat");
        double myLng = peer.optDouble("lng");
        if (Double.NaN == lat || Double.NaN == lng || Double.NaN == myLat || Double.NaN == myLng) return Float.NaN;
        Location.distanceBetween(lat, lng, myLat, myLng, dbResult);
        return dbResult[0];
    }

    private void processPeerList() {
        JSONObject myMarker = null;
        String myIpAddr = R2CRest.MyPublicIp();
        float[] dbResult = {Float.NaN};
        double accuracyInMeters = 0.0;

        if (!mapIsUp) {
            CTDebug(TAG, "processPeerList(): waiting for map processing to complete...");
            DelayedExec.RunAfterDelayInMsec(this::processPeerList, 1000);
            return;
        }
        if (null == myIpAddr && waitForGpsAccuracy++ < 5) {
            CTDebug(TAG, "processPeerList(): waiting for internet connectivity...");
            DelayedExec.RunAfterDelayInMsec(this::processPeerList, 1000);
            return;
        }
        if (null == MyLocation && waitForGpsAccuracy++ < 5) {
            CTDebug(TAG, "processPeerList(): No Location yet...retrying");
            DelayedExec.RunAfterDelayInMsec(this::processPeerList, 1000);
            waitForGpsAccuracy++;
            return;
        }
        if (null != MyLocation) {
            accuracyInMeters = MyLocation.getAccuracy();
            if ((!MyLocation.hasAccuracy() || accuracyInMeters > 10.0) && waitForGpsAccuracy++ < 5) {
                ShowToast(String.format(Locale.US, "Location accuracy of %.3f meters isn't great - waiting for better accuracy.",
                        accuracyInMeters));
                DelayedExec.RunAfterDelayInMsec(this::processPeerList, 5000);
                waitForGpsAccuracy++;
                return;
            }

            CTDebug(TAG, String.format(Locale.US, "My location is %.7f,%.7f w/in %.3f meters. My UUID is %s",
                    MyLocation.getLatitude(), MyLocation.getLongitude(), accuracyInMeters, MyUUID));
        }

        // find my Marker in the list of peers and fire-up clients for the others:
        for (int i=0; i<r2cPeers.length(); i++) {
            JSONObject peer = r2cPeers.optJSONObject(i);
            if (peer.optString("id").equals(MyUUID)) {
                myMarker = peer;
                CTDebug(TAG, "Found marker with my UUID: " + MyUUID);
            } else if (peer.optString("uuid").equals(MyUUID)) {
                myMarker = peer;// found my marker.
            } else {
                String peerIpAddr = peer.optString("ipaddr");
                if (!peerIpAddr.isEmpty()) R2CRest.ClientForRemoteIpAddr(peerIpAddr);
            }
        }

        long timeNowInMilliseconds = System.currentTimeMillis();
        String timeString = String.valueOf(timeNowInMilliseconds);
        if (null != myMarker) {
            // This can happen when app is terminated while internet is down.
            boolean updateRequired = false;
            JSONObject updateFeature = myMarker.optJSONObject("feature");
            if (null == updateFeature) {
                CTError(TAG, "processPeerList() marker missing feature: " + myMarker);
                return;
            }
            String markerIpAddr = myMarker.optString("ipaddr");
            try {
                if (!markerIpAddr.equals(myIpAddr)) {
                    myMarker.put("ipaddr", myIpAddr);
                    updateRequired = true;
                }
                if (null != MyLocation && MyLocation.hasAccuracy()) {
                    double lat = myMarker.optDouble("lat");
                    double lng = myMarker.optDouble("lng");
                    Location.distanceBetween(lat, lng, MyLocation.getLatitude(), MyLocation.getLongitude(), dbResult);
                    if (dbResult[0] >= accuracyInMeters) {
                        JSONObject geometry = updateFeature.optJSONObject("geometry");
                        JSONArray coordinates;
                        if (null == geometry) {
                            geometry = new JSONObject();
                            coordinates = new JSONArray();
                            geometry.put("coordinates", coordinates);
                            updateFeature.put("geometry", geometry);
                        }
                        coordinates = geometry.optJSONArray("coordinates");
                        if (null == coordinates) {
                            coordinates = new JSONArray();
                            geometry.put("coordinates", coordinates);
                        }
                        coordinates.put(0, lng);
                        coordinates.put(1, lat);
                        updateRequired = true;
                    }
                }
                if (updateRequired) {
                    JSONObject prop = updateFeature.optJSONObject("properties");
                    if (null == prop) {
                        prop = new JSONObject();
                        updateFeature.put("properties", prop);
                    }
                    prop.put("updated", timeString);
                    prop.put("-updated-on", timeString);
                    myMarkerOp = Csp.editObjectWithId("Marker", myMarker.optString("id"),
                            updateFeature, this::myMarkerCompleted);
                    CTDebug(TAG, "addOurMarker() (After): " + updateFeature.toString(4));
                }
            } catch (Exception e) {
                CTError(TAG, "addOurMaker() raised: ", e);
            }
        } else { // we get to create our marker from scratch - yipee!
            CTDebug(TAG, String.format(Locale.US,
                    "Didn't find our existing marker in %d peers, so adding a new one:", r2cPeers.length()));
            JSONObject prop = new JSONObject();
            try {
                prop.put("updated", timeString);
                prop.put("-updated-on", timeString);
                prop.put("r2c-ipaddr", myIpAddr);
                prop.put("marker-color", "#0000FF");
            } catch (Exception e) {
                CTError(TAG, "Make compiler happy.", e);
            }
            if (null != MyLocation) {
                myMarkerOp = Csp.addMarker(MyLocation.getLatitude(), MyLocation.getLongitude(),
                        "R2C", "radiotower", folderId, MyUUID, prop, this::myMarkerCompleted);
            }
        }
        if (null != folderId && null != archiveFolderId) mapIsUp = true;
    }

    private void myMarkerCompleted() {
        if (!myMarkerOp.isDone() || myMarkerOp.fail()) {
            CTError(TAG, "Not able to create marker: " + myMarkerOp.response);
        }
    }

    private void lookForOldShapes() {
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
            String trackId = feature.optString("id", "");
            if (trackId.isEmpty()) {
                CTError(TAG, "archiveFeature(): id for feature is empty - this shouldn't happen.\n  " +
                        feature.toString(4));
                return;
            }
            JSONObject prop = feature.optJSONObject("properties");
            if (null == prop) {
                prop = new JSONObject();
                feature.put("properties", prop);
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

    public boolean getMapIsUp() {
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
        if (null != Csp) try {
            if (null != Maps) for (CaltopoClientMap map : Maps) {
                for (CaltopoLiveTrack track : map.liveTracks) {
                    CTDebug(TAG, "ShutDown() - shutting down track: " + track.getTrackLabel());
                    track.archiveTrackOnCaltopo();
                }
            }
            CaltopoOp op = Csp.deleteMarkerWithId(MyUUID, null);
            op.syncOpJSONObject(5);
            if (op.success()) {
                CTDebug(TAG, String.format(Locale.US, "Marker removed in %.3f seconds",
                        (double)op.roundTripTimeInMsec() / 1000.0));
            }
            R2CRest.Shutdown();
        } catch (Exception e) {
            CTError(TAG, "Attempting to remove my Marker from caltopo raised: ", e);
        }
        CaltopoSession.Shutdown();
    }

}

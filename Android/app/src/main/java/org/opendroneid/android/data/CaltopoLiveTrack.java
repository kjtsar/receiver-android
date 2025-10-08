package org.opendroneid.android.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;


import static org.opendroneid.android.data.CaltopoClient.CTDebug;
import static org.opendroneid.android.data.CaltopoClient.CTError;

import androidx.annotation.NonNull;

import java.util.LinkedList;

public class CaltopoLiveTrack {
    private static final String TAG = "CaltopoLiveTrack";
    private static CaltopoSession Csp;
    private static CtLineProperty ArchiveTrackLineProp;
    private static JSONObject R2cPeers;
    private static SimpleMovingAverage CaltopoRttInMsec;
    private CaltopoOp startLiveTrackOp;
    private CaltopoOp renameTrackOp;
    private String renameTrackLabel;
    private CaltopoOp liveTrackOp;
    private String liveTrackId;
    private LinkedList<double[]> linePoints; // array of arrays of [lat,lng] pairs
    private int linePointsSentCount;
    private String folderId;
    private final CaltopoClientMap myMap;
    private String myTrackLabel;
    private boolean active;
    private boolean locallyOwnedTrack;
    private boolean blocked; // pending owner determination through discovery.
    private R2CRest r2cClient;
    private String myGroupId;
    private CtDroneSpec droneSpec;

    public static long GetCaltopoRttInMsec() { return CaltopoRttInMsec.get();}

    public static class SimpleMovingAverage {
        private final long[] window;
        private int ix;
        private long sum;

        public SimpleMovingAverage(int size) {
            window = new long[size];
            sum = 0;
        }

        public long next(long val) {
            sum -= window[ix];
            sum += val;
            window[ix++] = val;
            ix = ix % window.length;
            return sum / ((0 == ix) ? window.length : ix);
        }
        public long get() {return sum / ((0 == ix) ? window.length : ix);}
    }

    public CaltopoLiveTrack(@NonNull CaltopoClientMap map, @NonNull String trackLabel, @NonNull String groupId,
                            @NonNull CtDroneSpec droneSpec, double lat, double lng, long droneTimestampInMsec)
            throws RuntimeException {
        if (trackLabel.isEmpty() || groupId.isEmpty()) {
            throw new RuntimeException("CaltopoLiveTrack(): trackLabel and groupId are both required.");
        }
        if (null == CaltopoRttInMsec) CaltopoRttInMsec = new SimpleMovingAverage(10);
        myMap = map;
        myTrackLabel = trackLabel;
        myGroupId = groupId;
        active = true;
        this.droneSpec = droneSpec;
        droneSpec.setMyLiveTrack(this);
        if (null == linePoints) linePoints = new LinkedList<>();
        double[] point = {lat, lng, (double)droneTimestampInMsec};
        linePoints.add(point);
        if (null == ArchiveTrackLineProp) ArchiveTrackLineProp =
                new CtLineProperty("2", "1", "#ff00ff", "solid");
        if (null == Csp) {
            Csp = myMap.session();
        }
        switch (R2CRest.StatusForNewRemoteId(droneSpec, lat, lng, droneTimestampInMsec)) {
            case forwardToClient -> r2cClient = R2CRest.ClientForRemoteId(droneSpec.getRemoteId());
            case pending -> blocked = true;
            case okToPublishLocally -> locallyOwnedTrack = true;
        }

        startNewTrack(trackLabel);
    }

    public void r2cSetClient(R2CRest client) {
        this.r2cClient = client;
        blocked = false;
    }

    public void r2cPublishDirect() {
        locallyOwnedTrack = true;
        blocked = false;
        startNewTrack(myTrackLabel);
    }

    /**  Archive this track segment on Caltopo if we're the owner.
     */
    public void archiveTrackOnCaltopo() {
        if (!locallyOwnedTrack) {
            // We don't own this track, so ignore request to archive on Caltopo.
            CTDebug(TAG, "archiveTrackOnCaltopo(): attempt to archive a track that is owned by a remote R2C ignored.");
            return;
        }

        int size = (linePoints != null) ? linePoints.size() : 0;
        if (0 == size || null == liveTrackId) {
            CTDebug(TAG, String.format(Locale.US,
                    "finishTrack(%s): w/no waypoints ignored.", myTrackLabel));
            return;
        }
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < size; i++) {
            double[] point = linePoints.get(i);
            JSONArray pointArray = new JSONArray();
            pointArray.put(String.format(Locale.US, "%.7f", point[1]));
            pointArray.put(String.format(Locale.US, "%.7f", point[0]));
            jsonArray.put(pointArray);
        }
        String archiveFolderId = myMap.getArchiveFolderId();
        CTDebug(TAG, String.format(Locale.US, "archiveTrackOnCaltopo(): Archiving track %s with %d points.",
                myTrackLabel, size));
        if (null != startLiveTrackOp && startLiveTrackOp.isDone() && startLiveTrackOp.success()) {
            // convert the LiveTrack to a Shape w/archive properties and add in all the waypoints.
            JSONObject feature = startLiveTrackOp.responseJson;
            JSONObject geometry = new JSONObject();
            try {
                geometry.put("coordinates", jsonArray);
                geometry.put("type", "LineString");
                feature.put("geometry", geometry);
            } catch (JSONException e) {
                CTError(TAG, "archiveTrackCaltopo() JSONObject.put() raised - for no apparent reason.", e);
            }
            myMap.archiveFeature(feature, "LiveTrack", System.currentTimeMillis());
        } else {
            // for some reason, we weren't able to start the live track, so this will likely block as well
            try {
                Csp.addLine(jsonArray, myTrackLabel, "", "", archiveFolderId, myMap.getArchiveLineProp(), null);
            } catch (Exception e) {
                CTError(TAG, "archiveTrackCaltopo() addLine() raised - for no apparent reason.", e);
            }
        }
        linePoints.clear();
        liveTrackId = null;
        linePointsSentCount = 0;
        startLiveTrackOp = null;
    }

    public void renameTrackCompleted() {
        if (renameTrackOp.fail()) {
            CTError(TAG, "renameTrackCompleted(): Failed to rename LiveTrack: " + renameTrackOp.responseString());
        } else {
            CTDebug(TAG, "renameTrackCompleted(): succeeded: " + renameTrackOp.responseString());
        }
    }

    public void renameTrack(String trackLabel) {
        renameTrackLabel = null;
        // Just edit the current live track - replacing the title.
        if (!active || null == startLiveTrackOp) {
            CTError(TAG, "renameTrack(): received on inactive track.");
            return;
        }
        if (!startLiveTrackOp.isDone()) {
            renameTrackLabel = trackLabel;
            return;
        }
        try {
            long timeNowInMilliseconds = System.currentTimeMillis();
            String timeString = String.valueOf(timeNowInMilliseconds);
            JSONObject feature = startLiveTrackOp.responseJson;
            JSONObject prop = feature.optJSONObject("properties");
            if (null != prop) prop.put("title", trackLabel);
            prop.put("updated", timeString);
            prop.put("-updated-on", timeString);
            renameTrackOp = Csp.editObjectWithId("LiveTrack", liveTrackId, feature, this::renameTrackCompleted);
        } catch (Exception e) {
            CTError(TAG, "renameTrack() raised.", e);
        }
    }

    public void startNewTrack(String trackLabel) {
        myTrackLabel = trackLabel;
        linePointsSentCount = 0;
        liveTrackId = null;
        liveTrackOp = null;
        startLiveTrackOp = null;
        active = true;
        if (null == folderId) folderId = myMap.getFolderId();
        if (null == folderId) {
            CTDebug(TAG, "startNewTrack(): missing required folderId - delaying...");
            DelayedExec.RunAfterDelayInMsec(() -> {startNewTrack(trackLabel);}, 1000);
            return;
        }
        CTDebug(TAG, String.format(Locale.US, "startNewTrack(%s-%s): Starting LiveTrack.",
                myGroupId, myTrackLabel));
        try {
            startLiveTrackOp = Csp.startLiveTrack(myGroupId, myTrackLabel, folderId,
                    null, null, this::startLiveTrackComplete);
        } catch (Exception e) {
            CTError(TAG, "startNewTrack(): startLiveTrack() raised: ", e);
        }
    }

    public void finishTrack(@NonNull String reason) {
        try {
            archiveTrackOnCaltopo();
        } catch (Exception e) {
            CTError(TAG, String.format(Locale.US, "finishTrack(%s) '%s' failed:", myTrackLabel, reason), e);
        }
        liveTrackId = null;
        startLiveTrackOp = null;
        active = false;
    }

    public boolean isActive() {return active; }

    private void startLiveTrackComplete() {
        CTDebug(TAG, "startLiveTrackComplete():\n  " + startLiveTrackOp);
        if (null == startLiveTrackOp || !startLiveTrackOp.isDone()) return;

        if (startLiveTrackOp.fail()) {
            CTError(TAG, String.format(Locale.US, "Not able to open LiveTrack for:'%s-%s':\n  %s",
                    myGroupId, myTrackLabel, startLiveTrackOp.responseString()));
            finishTrack("Not able to open/write LiveTrack");
        } else try {
            liveTrackId = startLiveTrackOp.id();
            CTDebug(TAG, String.format(Locale.US, "startLiveTrackComplete(%s): liveTrackId: '%s'",
                    myTrackLabel, liveTrackId));
            if (null != renameTrackLabel) renameTrack(renameTrackLabel);
        } catch (Exception e) {
            CTError(TAG, "startLiveTrackComplete(): id() raised:", e);
        }
        processNextWaypoint();
    }

    /* Now this is where things get interesting.   Starting out, the first time we
     * see a new drone, we don't know if it is ours or one owned by another R2C
     * instance that is just no, so...
     * We need to consult the R2C database to make that determination.
     */
    public void publishDirect(double lat, double lng, long altitudeInMeters, long droneTimestampInMillisec) {
        if (null == linePoints) linePoints = new LinkedList<>();
        double[] point = {lat, lng, (double)droneTimestampInMillisec};
        linePoints.add(point);

        CTDebug(TAG, String.format(Locale.US,
                "publishDirect(%s): added waypoint to queue. size is %d",
                myTrackLabel, linePoints.size()));
        if (!myMap.mapIsUp()) {
            CTDebug(TAG, "publishDirect(): Waiting for map to initialize.");
            return;
        }
        folderId = myMap.getFolderId();
        if (null == liveTrackId  && null == startLiveTrackOp) {
            startNewTrack(myTrackLabel);
            return;
        }
        processNextWaypoint();
    }

    public void processNextWaypoint() {
        if (!active) {
            CTDebug(TAG, "processNextWaypoint(): no longer active - stopping.");
            return; // signals for send no more waypoints.
        }
        if (null == liveTrackOp || liveTrackOp.isDone()) {
            if (null != liveTrackOp && liveTrackOp.isDone()) {
                CaltopoRttInMsec.next(liveTrackOp.roundTripTimeInMsec());
            }
            try {
                int pointCount = linePoints.size();
                if (linePointsSentCount < pointCount) {
                    double[] point = linePoints.get(linePointsSentCount++);
                    CTDebug(TAG, String.format(Locale.US, "processNextWaypoint(%s-%s#%d): adding %.7f,%.7f to LiveTrack.  Avg rtt is %.3f seconds.",
                            myGroupId, myTrackLabel, linePointsSentCount, point[0], point[1], (double)CaltopoRttInMsec.get() / 1000.0));
                    liveTrackOp = Csp.addLiveTrackPoint(myGroupId, myTrackLabel, point[0], point[1], this::processNextWaypoint);
                }
            } catch (Exception e) {
                CTError(TAG, "processNextWaypoint(): addLiveTrackPoint() raised: ", e);
            }
        } else if (null != liveTrackOp && liveTrackOp.fail()) {
            CTError(TAG, "processNextWaypoint(): addLiveTrackPoint failed: " + liveTrackOp.response);
            active = false;
        }
    }

}

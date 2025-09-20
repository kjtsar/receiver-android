package org.opendroneid.android.data;
import org.json.*;

import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Date;
import java.util.Locale;
import java.util.Map;

import android.content.ContentResolver;
import android.content.Context;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

/*
 * Module for building waypoint-based Caltopo tracks on the fly as waypoints come in.
 * This is to support rapid archival to a geojson file on application termination.
 *
 * Compare one waypoint to the next to determine if there is significant enough change
 * in location to warrant archiving the new point - per MinDistanceInFeet parameter.
 *
 * TODO: May also want to consider starting a new track if there is a significant change
 *    in location or time from one waypoint to the next...
 *
 * Sample Caltopo .json file format:
  * filename: <mappedID><startTimestamp>.json
 * 
 * {
 *   "type": "FeatureCollection",
 *   "features": [
 *     {
 *       "type": "Feature",
 *       "properties": {
 *         "title": "<mappedID><startTimestamp>"
 *       },
 *       "geometry": {
 *         "type": "LineString",
 *         "coordinates": [
 *           [
 *             -121.09279,
 *             39.2966,
 *             358,
 *             1752642725896
 *           ],
 *           [
 *             -121.09279,
 *             39.2966,
 *             358,
 *             1752642726897
 *           ]
 *         ]
 *       }   // geometry feature
 *     }     // Feature
 *   ]       // Feature array   
 * }         // geojson FeatureCollection
 *
 */


public class WaypointTrack {

	public static int WaypointCount = 0;
	private static final String TAG = "WaypointTrack";

	public static long MaxDistanceInFeet = 1000;

	// map trackLabel to WaypointTrack.
	public static HashMap<String, WaypointTrack> TrackMap = new HashMap<>();

	public JSONArray coordinates;
	public long lastArchiveLength;
	public String trackLabel;
	// startTimeStr is the time the track was started - used in track archive.
	public String startTimeStr;
	public double lastLat;
	public double lastLng;
	// lastTimestampInSeconds - there can be multiple sources for timestamps - discard earlier duplicates.
	public long lastTimestampInSeconds;

	public WaypointTrack(String trackLabel) {
		SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy-HHmmss", Locale.US);
		startTimeStr = sdf.format(new Date());
		this.trackLabel = trackLabel;
		this.coordinates = new JSONArray();
		this.lastLat = this.lastLng = 0.0;
		Log.d(TAG, String.format("AddWaypointForTrack(%s):%s Starting new track.", trackLabel, startTimeStr));

	}

	// Rough distance measurement based on Equirectangular Distance Approximation.
	private static long RoughLatLongDeltaInFeet(double lat1, double lng1, double lat2, double lng2) {
		double retval = 0.0;
		if (lat1 != lat2 || lng1 != lng2) {
			final double EARTH_RADIUS_IN_FEET = 2.093e+7;
			double lat1rad = Math.toRadians(lat1);
			double lng1rad = Math.toRadians(lng1);
			double lat2rad = Math.toRadians(lat2);
			double lng2rad = Math.toRadians(lng2);
			double x = (lng2rad - lng1rad) * Math.cos((lat1rad + lat2rad) / 2);
			double y = (lat2rad - lat1rad);
			retval = Math.sqrt(x * x + y * y) * EARTH_RADIUS_IN_FEET;
		}
	//	Log.d(TAG, String.format("RoughLatLongDeltaInFeet(1:%.7f,%.7f 2:%.7f,%.7f) diff:%f feet",
	//			lat1, lng1, lat2, lng2, retval));
		return (long)retval;
	}

	public static long UnsavedMsgCountForTrack(String trackLabel) {
		WaypointTrack track = TrackMap.get(trackLabel);
		if (null == track) return 0;
		long numCoords = track.coordinates.length();
		if (0 == numCoords || numCoords == track.lastArchiveLength) return 0;

		return numCoords - track.lastArchiveLength;
	}

	// returns true if waypoint meets requirements and is added to track.
	public static boolean AddWaypointForTrack(String trackLabel, double lat, double lng,
											  long altAboveLaunchInMeters, long timestampInSec) {
		WaypointTrack track = TrackMap.get(trackLabel);
		if (null == track) {
			track = new WaypointTrack(trackLabel);
			TrackMap.put(trackLabel, track);
		}
		return track.addWaypoint(lat, lng, altAboveLaunchInMeters, timestampInSec);
	}

	public static void ArchiveTracks(Context ctxt, DocumentFile archiveDir) {
		if (0 == WaypointCount) {
			Log.e(TAG, "ArchiveTracks(): no waypoints recorded");
			return;
		}

		SimpleDateFormat sdf = new SimpleDateFormat("ddMMMyyyy", Locale.US);
		String dirpath = "tracks-" + sdf.format(new Date());
		DocumentFile todaysDir = archiveDir.findFile(dirpath);
		if (null == todaysDir) {
			todaysDir = archiveDir.createDirectory(dirpath);
			if (null == todaysDir) {
				Log.e(TAG, String.format("Not able to create '%s'", archiveDir));
			}
		}

		for (Map.Entry<String, WaypointTrack> map : TrackMap.entrySet()) {
		//	String Key = map.getKey();
			WaypointTrack track = map.getValue();
			track.archive(ctxt, todaysDir);
		}
	}

	public void archive(Context ctxt, DocumentFile archiveDir) {
		long numCoords = coordinates.length();
		if (0 == numCoords || numCoords == lastArchiveLength) {
			Log.i(TAG, "archive(): No new coordinates to archive.");
			return;
		}
		String filename = trackLabel + "-" + startTimeStr + ".json";
		Log.i(TAG, String.format(Locale.US, "archive(%s): writing %d coordinates.", filename, numCoords));

		try {
			JSONObject jo = new JSONObject();
			jo.put("type", "Feature");

			JSONObject joTitle = new JSONObject();
			joTitle.put("title", trackLabel);
			joTitle.put("start_time", startTimeStr);
			jo.put("properties", joTitle);

			JSONObject joGeometry = new JSONObject();
			joGeometry.put("type", "LineString");
			joGeometry.put("coordinates", coordinates);
			jo.put("geometry", joGeometry);

			JSONArray jaFeatures = new JSONArray();
			jaFeatures.put(jo);

			JSONObject joTop = new JSONObject();
			joTop.put("type", "FeatureCollection");
			joTop.put("features", jaFeatures);
			if (0 != lastArchiveLength) {
				// FIXME: better to rename/move, then delete after the new file is written
				DocumentFile dataFilepath = archiveDir.findFile(filename);
				if (null != dataFilepath) {
					dataFilepath.delete();
				}
			}
			DocumentFile dataFilepath = archiveDir.createFile("application/geo+json", filename);

			try {
				ContentResolver resolver = ctxt.getContentResolver();
				OutputStream os = resolver.openOutputStream(dataFilepath.getUri());
				os.write(joTop.toString(4).getBytes());
				os.flush();
				os.close();
				lastArchiveLength = numCoords;
			} catch (IOException e) {
				Log.e(TAG, String.format("archive(%s):%s raised:\n%s.", dataFilepath,
						filename, e));
			}
			Log.d(TAG, String.format("archive(%s):%s.", archiveDir, filename));
		} catch (JSONException e) {
			Log.e(TAG, String.format("archive(%s):%s raised:\n%s.", archiveDir,
					filename, e));
		}
	}

	// returns true if waypoint added
	public boolean addWaypoint(double lat, double lng,
							   long altInMeters, long timestampInSeconds) {
		boolean retval = false;
		long distanceInFeet = 0;

		if ((lastTimestampInSeconds != 0) && (timestampInSeconds < lastTimestampInSeconds)) {
			return false;
		}
		if (lastLat != 0.0 && lastLng != 0.0) {
			distanceInFeet = RoughLatLongDeltaInFeet(lat, lng, lastLat, lastLng);

			if (distanceInFeet > MaxDistanceInFeet) {
				// first archive this track with it's own unique label
				TrackMap.put(trackLabel + startTimeStr, this);

				// Then we need to start a new track with this label.
				WaypointTrack newTrack = new WaypointTrack(this.trackLabel);
				TrackMap.put(trackLabel, newTrack);
				return newTrack.addWaypoint(lat, lng, altInMeters, timestampInSeconds);
			} else if (distanceInFeet < CaltopoClient.GetMinDistanceInFeet()) {
				return false;
			}
		}
		if (lat != 0.0 && lng != 0.0) {
			JSONArray ja = new JSONArray();
			ja.put(String.format(Locale.US, "%.6f", lng));
			ja.put(String.format(Locale.US, "%.6f", lat));
			ja.put(String.format(Locale.US, "%d", altInMeters));
			ja.put(String.format(Locale.US, "%d", timestampInSeconds));
			coordinates.put(ja);
			WaypointCount++;
			lastLat = lat;
			lastLng = lng;
			long deltaTimeInSeconds = (0 != lastTimestampInSeconds) ? timestampInSeconds - lastTimestampInSeconds : 0;
			lastTimestampInSeconds = timestampInSeconds;

			Log.d(TAG, String.format("addWaypoint(%s): delta %d feet after %d seconds, adding %s", trackLabel,
					distanceInFeet, deltaTimeInSeconds, ja));
			retval = true;
		} else {
			Log.d(TAG, String.format("addWaypoint(%s):  lat/lng both zero.", trackLabel));
		}
		return retval;
	}
}

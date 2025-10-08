package org.opendroneid.android.data;

import fi.iki.elonen.NanoHTTPD;
import static org.opendroneid.android.data.CaltopoClient.CTDebug;
import static org.opendroneid.android.data.CaltopoClient.CTError;
import static org.opendroneid.android.data.CaltopoClient.CTInfo;
import static org.opendroneid.android.data.CaltopoSession.EncodeParams;

import static java.lang.Thread.sleep;
import java.security.KeyStore;

import android.content.Context;
import android.os.Build;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.opendroneid.android.BuildConfig;
import org.opendroneid.android.R;
import org.opendroneid.android.app.DebugActivity;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.Security;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.util.Arrays;

/**
 *
 */
public class R2CRest {
    public enum R2CRespEnum {
        pending,
        okToPublishLocally,
        forwardToClient,
    }
    private static final String TAG = "R2CRest";
    private static final String WimaUrl = "https://whatismyip.akamai.com/";

    // maps ip address to the client.
    private static Hashtable<String, R2CRest> ClientIpMap;

    // maps remote id to the client that owns it (or null if we are the owner):
    private static Hashtable<String, R2CRest> ClientRidMap;

    private static Hashtable<String, CaltopoLiveTrack> OurLiveTracks;

    private static String MyIpAddress = null;
    private static ExecutorService ExecutorPool;
    private static R2CRest.Server server;
    private static CaltopoOp WimaOp;
    private Hashtable<CtDroneSpec, CaltopoOp> outstandingOpsByDronespec;
    private Hashtable<String, CtDroneSpec> myDronespecsByRid;
    private String myRemoteIpAddr;
    private CaltopoOp helloOp;

    public static class Server extends NanoHTTPD {
        private static final String TAG = "R2CRest.Server";
        private static final String MIME_PLAINTEXT = "text/plain";
        private static final String HTTP_PROTOCOL = "https";
        private static final int HTTPS_PORT = 8443;
        private static ServerListener myListener;
        private static Server ServerInstance;

        public interface ServerListener {
            Response parsePost(@NonNull IHTTPSession session, @NonNull String remoteIpAddr,
                               @NonNull String path, @NonNull Map<String, List<String>> parms);

            Response parseGet(@NonNull IHTTPSession session, @NonNull String remoteIpAddr,
                              @NonNull String path, @NonNull Map<String, List<String>> params);
        }

        public Server(int port, KeyStore keyStore, KeyManagerFactory keyManagerFactory) {
            super("0.0.0.0", port);
            if (null != ServerInstance) {
                throw new RuntimeException("R2CRest.Server() already running.");
            }

            try {
                if (null != keyStore && null != keyManagerFactory) {
                    makeSecure(NanoHTTPD.makeSSLSocketFactory(keyStore, keyManagerFactory), null);
                }
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                CTDebug(TAG, "R2CRestServer() started on port " + port);
            } catch (Exception e) {
                CTError(TAG, "R2CRestServer start() raised.", e);
            }
        }

        @Override
        public Response serve(IHTTPSession session) {
            Method method = session.getMethod();
            String path = session.getUri();
            String remoteIp = session.getRemoteIpAddress();
            CTDebug(TAG, String.format(Locale.US,
                    "serve() received from %s:%s(%s)",
                    remoteIp, method, path));

            Map<String, List<String>> parms = session.getParameters();
            for (String key : parms.keySet()) {
                CTDebug(TAG, String.format(Locale.US, "serv(): Received '%s':'%s'", key, parms.get(key)));
            }
            if (path.startsWith("/R2Cv1")) {
                if (Method.POST == method) {
                    return this.parseV1Post(session, remoteIp, path, parms);
                } else if (Method.GET == method) {
                    return this.parseV1Get(session, remoteIp, path, parms);
                }
            } else if (null != myListener) {
                if (Method.POST == method) {
                    return myListener.parsePost(session, remoteIp, path, parms);
                } else if (Method.GET == method) {
                    return myListener.parseGet(session, remoteIp, path, parms);
                }
            }

            return newFixedLengthResponse(Response.Status.OK,
                    MIME_PLAINTEXT, "Nothing happens.");
           }

        public Response parseV1Post(@NonNull IHTTPSession session, @NonNull String remoteIpAddr,
                                    @NonNull String path, @NonNull Map<String, List<String>> parms) {
            Map<String, String> map = new HashMap<>();
            JSONObject payload;
            try { // FIXME: how to check the mime type of the payload?
                session.parseBody(map);
                payload = new JSONObject(map.get("postData"));
                CTDebug(TAG, String.format(Locale.US, "Received from %s: %s, body:\n%s",
                        remoteIpAddr, parms, payload.toString(4)));
            } catch (Exception e) {
                CTDebug(TAG, "parseBody: " + map);
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "That's all for now folks.");
        }

        public Response parseV1Get(@NonNull IHTTPSession session, @NonNull String remoteIpAddr,
                                   @NonNull String path, @NonNull Map<String, List<String>> parms) {
            CTDebug(TAG, String.format(Locale.US,
                    "parseV1Get() received from %s: %s", remoteIpAddr, parms));
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "TBD");
        }
    }

    /** R2CRest
     *  1) Use WimIpUrl to determine my pubic IP address.  This also has the
     *    side-effect of verifying that the internet is up.
     *    idea to export
     */
    public R2CRest(@NonNull String remoteR2cIpAddr)  {
        myRemoteIpAddr = remoteR2cIpAddr;
        outstandingOpsByDronespec = new Hashtable<>(8);
        myDronespecsByRid = new Hashtable<>(16);
        if (null == ClientIpMap) ClientIpMap = new Hashtable<>(16);
    }
    public static R2CRest ClientForRemoteIpAddr(@NonNull String remoteR2cIpAddr) {
        if (null == ClientIpMap) ClientIpMap = new Hashtable<>(16);
        R2CRest r2cRest = ClientIpMap.get(remoteR2cIpAddr);
        if (null == r2cRest) {
            r2cRest = new R2CRest(remoteR2cIpAddr);
            r2cRest.helloOp = r2cRest.sayHello();
        }
        return r2cRest;
    }

    /** Start here for initial lookup of a new drone.
     *  If the client isn't found in any of the maps, we need to make sure some other
     *  R2C instance hasn't adopted it first.
     *
     * @param droneSpec dronespec for the drone
     * @param lat latitude of the first reported waypoint
     * @param lng longitude of the first reported waypoint
     * @param droneTimestampInMsec timestamp from the drone's first reported waypoint.
     * @return Response indicates current status for the specified drone.
     */
    public static R2CRespEnum StatusForNewRemoteId(@NonNull CtDroneSpec droneSpec, double lat, double lng, long droneTimestampInMsec) {
        if (null == ClientIpMap) ClientIpMap = new Hashtable<>(16);
        if (ClientIpMap.isEmpty()) {
            if (null == OurLiveTracks) OurLiveTracks = new Hashtable<>(16);
            return R2CRespEnum.okToPublishLocally;
        }
        String remoteId = droneSpec.getRemoteId();
        if (null != OurLiveTracks.get(remoteId)) return R2CRespEnum.okToPublishLocally;

        if (null == ClientRidMap) {
            ClientRidMap = new Hashtable<>(16);
        } else {
            if (null != ClientRidMap.get(remoteId)) return R2CRespEnum.forwardToClient;
        }

        // This one is a new drone, so check with peers before claiming:
        for (Map.Entry<String,R2CRest> map : ClientIpMap.entrySet()) {
            R2CRest client = map.getValue();
            CtDroneSpec myDronespec = client.myDronespecsByRid.get(remoteId);
            if (null != myDronespec) {
                // FIXME: This seems unlikely.    We're supposed to notify as soon as a drone goes idle,
                // FIXME: so technically, we wouuld have sent the "drone-drop" to peers when that happened
                // FIXME: and removed this from my list of drones.  Potential race condition?
                return R2CRespEnum.okToPublishLocally;
            }
            CaltopoOp op = client.outstandingOpsByDronespec.get(droneSpec);
            if (null != op) return R2CRespEnum.pending;
            client.outstandingOpsByDronespec.put(droneSpec,
                    client.sendAdd(droneSpec, lat, lng, droneTimestampInMsec));
        }
        return R2CRespEnum.pending;
    }

    private CaltopoOp sendAdd(@NonNull CtDroneSpec droneSpec, double lat, double lng, long droneTimestampInMsec) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", "add-drone");
            payload.put("rid", droneSpec.getRemoteId());
            payload.put("drone-timestamp-ms", droneTimestampInMsec);
            payload.put("drone-lat", String.valueOf(lat));
            payload.put("drone-lng", String.valueOf(lng));
            payload.put("distance-from-me", String.valueOf(CaltopoClientMap.DistanceFromMeInMeters(lat, lng)));
            payload.put("caltopo-rtt-msec", CaltopoLiveTrack.GetCaltopoRttInMsec());
        } catch (Exception e) {
            CTError(TAG, "trying to keep compiler happy", e);
        }
        return sendRequest(CtsMethod_t.POST, null, null, payload, () -> {
            handleAddResponse(droneSpec);
        });
    }

    private void handleAddResponse(@NonNull CtDroneSpec droneSpec) {
        R2CRest client;
        CaltopoOp op = outstandingOpsByDronespec.remove(droneSpec);
        CaltopoLiveTrack liveTrack;

        if (null != op && op.fail()) {
            if (op.responseCode == 202) {
                // add this client as owner of the remote id.
                ClientRidMap.put(droneSpec.getRemoteId(), this);
                myDronespecsByRid.put(droneSpec.getRemoteId(), droneSpec);
                liveTrack = droneSpec.getMyLiveTrack();
                if (null != liveTrack) liveTrack.r2cSetClient(this);
                // FIXME: handle case where more than one remote claims a rid.
            } else {
                CTError(TAG, String.format(Locale.US,
                        "handleAddResponse(%s) failed w/code:%d - %s",
                        droneSpec.getRemoteId(), op.responseCode, op.response));
            }
        }
        for (Map.Entry<String,R2CRest> map : ClientIpMap.entrySet()) {
            client = map.getValue();
            if (null != client.outstandingOpsByDronespec.get(droneSpec)) {
                // still waiting for an outstanding response
                return;
            }
        }
        // otherwise, all remotes have reported back with 200, so we're good to go.
        liveTrack = droneSpec.getMyLiveTrack();
        if (null != liveTrack) liveTrack.r2cPublishDirect();
    }

    /** Find client for specified remote id.  This only returns a client
     *  if one has been assigned to the remote id.  This is not the way
     *  to find out if a client has been assigned - use StatusForRemoteId
     *  to find out if one has been assigned to a client first.
     *
     * @param remoteId remote id string
     * @return  Returns client handle if one exists, null otherwise.
     */
    @Nullable
    public static R2CRest ClientForRemoteId(@NonNull String remoteId) {

        if (null == ClientRidMap) ClientRidMap = new Hashtable<>(16);
        return ClientRidMap.get(remoteId);
    }

    private void helloCompleted() {
        if (helloOp.isDone() && helloOp.success()) {
            JSONObject responsePayload = helloOp.getResponse();
            if (responsePayload != null) try {
                CTDebug(TAG, "helloCompleted, received response:" + responsePayload.toString(4));
            } catch (Exception e) {
                CTError(TAG, "toString() choked: ", e);
            }
        } else {
            CTError(TAG, String.format(Locale.US,
                    "Not able to say hello to my good buddy at %s - %s", myRemoteIpAddr,
                    helloOp.response));
        }
    }
    public CaltopoOp sayHello() {
        if (null != MyIpAddress) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("type", "hello");
                payload.put("my-addr", MyIpAddress);
            } catch (Exception e) {
                CTError(TAG, "stupid compiler", e);
            }
            helloOp = sendRequest(CtsMethod_t.POST, null, null, payload, this::helloCompleted);
        }
        return helloOp;
    }


    public static void Init() {
        if (null == R2CRest.Server.ServerInstance) {
            int port = 80;
            KeyManagerFactory keyManagerFactory = null;
            KeyStore keyStore = null;
            try {
                SSLContext context = SSLContext.getDefault();
                SSLParameters sslParameters = context.getSupportedSSLParameters();
                CTDebug(TAG, "Supported TLS/SSL Application Protocols: " + Arrays.toString(sslParameters.getApplicationProtocols()));
                CTDebug(TAG,"Enabled TLS/SSL Protocols: " + Arrays.toString(sslParameters.getProtocols()));

                String storePassword = BuildConfig.STORE_PASSWORD;
                InputStream inputStream;
                Context appContext = DebugActivity.getAppContext();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    inputStream = appContext.getResources().openRawResource(R.raw.keystore);
                    keyStore = KeyStore.getInstance("PKCS12");
                    keyStore.load(inputStream, storePassword.toCharArray());
                    inputStream.close();
                    keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                    keyManagerFactory.init(keyStore, storePassword.toCharArray());
                    port = R2CRest.Server.HTTPS_PORT;
                    CTDebug(TAG, "HTTP.Server() listening for tls/ssl connections on port " + port);
                } else {
                    // Can't figure out how to get earlier versions of Android to load the keystore:
                    CTDebug(TAG, "HTTP.Server() listening for cleartext connections on port " + port);
                }
            } catch (Exception e) {
                CTError(TAG, "Init(): raised...", e);
            }

            Security.addProvider(new BouncyCastleProvider());
            R2CRest.Server.ServerInstance = new R2CRest.Server(port, keyStore, keyManagerFactory);
            if (null == MyIpAddress) {
                WimaOp = SendRequest(CtsMethod_t.GET, WimaUrl, null, null, R2CRest::ProcessWimaOp);
            }
        }
    }

    public static void ProcessWimaOp() {
        MyIpAddress = WimaOp.response.trim();
        CTDebug(TAG, String.format(Locale.US, "wimIp() returned '%s' after %.3f seconds.",
                MyIpAddress, (double)WimaOp.roundTripTimeInMsec()/1000.0));
    }

    /** posts message to the background executor pool and returns immediately.
     *
     * @param method This enum specifies the http operation see <CtsMethod_t></CtsMethod_t>
     * @param url url suffix.
     * @param getParams Any query parameters that are to be escaped and tacked on to the path.
     * @param payload The JSON structure to be sent as the payload of a POST. Ignored for GET/DELETE
     * @param onCompletion action to take on completion - delivered on app's main thread.
     */
    public CaltopoOp sendRequest(CtsMethod_t method, @Nullable String url, @Nullable Map <String, String> getParams,
                @Nullable JSONObject payload, @Nullable Runnable onCompletion) {
        CaltopoOp op = new CaltopoOp(onCompletion);
        op.method = method;
        op.ipaddr = myRemoteIpAddr;
        op.url = url;
        op.getParams = getParams;
        op.payload = payload;
        if (null == ExecutorPool) {
            ExecutorPool = Executors.newFixedThreadPool(1);
        }
        op.asyncFuture = ExecutorPool.submit(() -> BgSendRequest(op));
        return op;
    }
    public static CaltopoOp SendRequest(CtsMethod_t method, @NonNull String url, @Nullable Map <String, String> getParams,
                @Nullable JSONObject payload, @Nullable Runnable onCompletion) {
        CaltopoOp op = new CaltopoOp(onCompletion);
        op.method = method;
        op.url = url;
        op.getParams = getParams;
        op.payload = payload;
        if (null == ExecutorPool) {
            ExecutorPool = Executors.newFixedThreadPool(1);
        }
        op.asyncFuture = ExecutorPool.submit(() -> BgSendRequest(op));
        return op;
    }

    private static CaltopoOp BgSendRequest(CaltopoOp op) {
        boolean retry = false;
        do {
            try {
                op.sentTimestampMsec = System.currentTimeMillis();
                String url;
                if (null != op.ipaddr) {
                    StringBuilder builder = new StringBuilder();
                    builder.append(Server.HTTP_PROTOCOL);
                    builder.append("://");
                    builder.append(op.ipaddr);
                    builder.append(":");
                    builder.append(Server.HTTPS_PORT);
                    builder.append("/R2CRest");
                    if (null != op.url) builder.append(op.url);
                    if (op.method == CtsMethod_t.GET && !op.getParams.isEmpty()) {
                        builder.append("?");
                        builder.append(EncodeParams(op.getParams));
                    }
                    url = builder.toString();
                } else {
                    url = op.url;
                }
                CTDebug(TAG, String.format(Locale.US, "Attempting to connect to '%s'...", url));
                // Open a connection
                HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
                connection.setRequestMethod(op.method.toString());
                connection.setRequestProperty("User-Agent", "R2CRest/0.1");
                if (op.method == CtsMethod_t.POST && op.payload != null) {
                    String body;
                    if (CaltopoClient.GetDebugLevel() > 1) {
                        body = op.payload.toString(2);
                    } else {
                        body = op.payload.toString();
                    }
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    connection.setRequestProperty("Content-Length", String.valueOf(body.length()));
                    connection.setDoOutput(true);
                    OutputStream os = connection.getOutputStream();
                    DataOutputStream dos = new DataOutputStream(os);
                    dos.writeBytes(body);
                    dos.flush();
                    dos.close();
                    os.close();
                }

                // Get the response code
                op.responseCode = connection.getResponseCode();
                BufferedReader reader;

                // Handle the response stream
                if (op.responseCode == HttpURLConnection.HTTP_OK) {
                    reader = new BufferedReader(new InputStreamReader(connection.getInputStream(),
                            StandardCharsets.UTF_8));
                } else {
                    reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(),
                            StandardCharsets.UTF_8));
                }

                // Read the response
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();
                op.receivedTimestampMsec = System.currentTimeMillis();
                op.response = response.toString();
                boolean opPassed;
                if (op.responseCode >= HttpURLConnection.HTTP_OK) {
                    opPassed = true;
                    if (!op.response.isEmpty()) {
                        try {
                            JSONObject responseJson = new JSONObject(op.response);
                            op.responseJson = responseJson.getJSONObject("result");
                        } catch (Exception e) {
                            // no harm in trying.  Maybe pass a flag that says if json expected.
                        }
                    }
                } else opPassed = false;
                op.setOperationIsDone(opPassed); // Provide option to handle on main thread.
                CTInfo(TAG, "BgSendRequest(): Normal Completion:\n  " + op);

            } catch (UnknownHostException e) {
                // this happens when no network connection, so retry after some delay period.
                long minRetryDelayInMsec = 3000; long maxRetryDelayInMsec = 60000;
                try {
                    sleep(minRetryDelayInMsec + (int) (java.lang.Math.random() * (maxRetryDelayInMsec - minRetryDelayInMsec)));
                } catch (InterruptedException e2) {
                    CTDebug(TAG, "sleep() interrupted.");
                }
                retry = true;
            } catch (Exception e) {
                op.goodResponse = false;
                op.response = "Exception raised during request:\n  " + e;
                CTError(TAG, "Exception raised during request:", e);
            }
        } while (retry);
        return op;
    }


    public static String MyPublicIp() {
        return MyIpAddress;
    }

    public CaltopoOp shutdown() {
        Map <String, String>params = new HashMap<>();
        params.put("addr", MyIpAddress);
        return sendRequest(CtsMethod_t.GET, "/leaving", params, null, null);
    }

    public static void Shutdown() {
        if (null == ClientIpMap) return;
        ArrayList <CaltopoOp>opArrayList = new ArrayList<>(ClientIpMap.size());
        for (String ipaddr : ClientIpMap.keySet()) {
            R2CRest client = ClientIpMap.get(ipaddr);
            if (null != client) opArrayList.add(client.shutdown());
            for (CaltopoOp op : opArrayList) {
                try {
                    op.syncOp(2000);
                } catch (Exception e) {
                    CTError(TAG, "exception trying to shut down connection to remote R2C", e);
                }
            }
        }
        // notify each of my attached bretheren that I'm leaving the network.
    }
}


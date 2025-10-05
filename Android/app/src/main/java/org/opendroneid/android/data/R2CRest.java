package org.opendroneid.android.data;
import fi.iki.elonen.NanoHTTPD;
import static org.opendroneid.android.data.CaltopoClient.CTDebug;
import static org.opendroneid.android.data.CaltopoClient.CTError;
import static org.opendroneid.android.data.CaltopoClient.CTInfo;

import static java.lang.Thread.sleep;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 *
 */
public class R2CRest {
    private static final String TAG = "R2CRest";
    private static final String WimaUrl = "https://whatismyip.akamai.com/";
    private static final int R2CPort = 11207; // unassigned as of 3Sep2025
    private static String MyIpAddress = null;
    private static ExecutorService ExecutorPool;
    private static R2CRest.Server server;
    private CaltopoOp wimaOp;
    private Listener myAppListener;

    public static class Server extends NanoHTTPD {
        private static String TAG = "R2CRest.Server";
        private static ServerListener myListener;

        public interface ServerListener {
            public Response parseV1Post(IHTTPSession session, String remoteIpAddr,
                                        String path, Map<String, List<String>> parms);

            public Response parseV1Get(IHTTPSession session, String remoteIpAddr,
                                       String path, Map<String, List<String>> params);
        }

        public Server() {
            super(R2CPort);
            try {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            } catch (Exception e) {
                CTError(TAG, "R2CRestServer start() raised.", e);
                return;
            }
            CTDebug(TAG, "R2CRestServer listening.");
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
            if (path.startsWith("/R2Cv1") && null != myListener) {
                if (Method.POST == method) {
                    return (null != myListener) ? myListener.parseV1Post(session, remoteIp, path, parms) :
                            this.parseV1Post(session, remoteIp, path, parms);
                } else if (Method.GET == method) {
                    return (null != myListener) ? myListener.parseV1Get(session, remoteIp, path, parms) :
                            this.parseV1Get(session, remoteIp, path, parms);
                }
            }
            return newFixedLengthResponse(Response.Status.OK,
                    MIME_PLAINTEXT, "Nothing happens.");
        }

        public Response parseV1Post(IHTTPSession session, String remoteIpAddr, String path, Map<String, List<String>> parms) {
            Map<String, String> map = new HashMap<>();
            JSONObject payload;
            try { // FIXME: how to check the mime type of the payload?
                session.parseBody(map);
                payload = new JSONObject(map.get("postData"));
                CTDebug(TAG, String.format(Locale.US, "Received from %s body:\n%s",
                        remoteIpAddr, payload.toString(4)));
            } catch (Exception e) {
                CTDebug(TAG, "parseBody: " + map);
            }
            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "That's all for now folks.");
        }

        public Response parseV1Get(IHTTPSession session, String remoteIpAddr, String path, Map<String, List<String>> parms) {

            return newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "TBD");
        }
    }

    /* R2CRest.Listener: messages beginning with "r2cRestFg" are delivered via the
     * application thread, while those starting with "r2cRestBg" are delivered from
     * the background thread immediately upon receipt - so don't daly.
     */
    public interface Listener {
        /** r2cRestFgConnect()
         *  @param publicIP is null if initial connection attempt timed-out, otherwise it
         *                  contains the public IP address of the server which is now up
         *                  and running and accepting requests.
         */
        public void r2cRestFgConnect(@Nullable String publicIP);


        /** r2cRestBgMessage()
         *
         * @param ipaddr senders public ip address
         * @param path portion following the port number
         * @param message incoming message payload
         * @return return payload content
         */
        public JSONObject r2cRestBgMessage(@NonNull String ipaddr, @NonNull String path, JSONObject message);
    }

    /** R2CRest
     *  1) Use WimIpUrl to determine my pubic IP address.  This also has the
     *    side-effect of verifying that the internet is up.
     *    idea to export
     */
    public R2CRest(R2CRest.Listener appListener) {
        myAppListener = appListener;
        if (null == MyIpAddress) {
            wimaOp = sendRequest(CtsMethod_t.GET, WimaUrl, null, () -> {
                processWimaOp();
            });
        } else if (null != myAppListener) {
            myAppListener.r2cRestFgConnect(MyIpAddress);
        }
    }

    public void processWimaOp() {
        MyIpAddress = wimaOp.response.trim();
        CTDebug(TAG, "wimIp() returned: " + MyIpAddress);
        if (null != myAppListener) {
            myAppListener.r2cRestFgConnect(MyIpAddress);
        }
        server = new Server();
    }

    public static String R2CUrlForAddr(String ipAddress) {
        return String.format(Locale.US, "http://%s:%d/",
                ipAddress, R2CPort);
    }

    /** posts message to the background executor pool and returns immediately.
     *
     * @param method This enum specifies the http operation see <CtsMethod_t></CtsMethod_t>
     * @param url url suffix if goNaked false, otherwise the complete url to send to.
     * @param payload The JSON structure to be sent as the payload.
     * @param onCompletion action to take on completion - delivered on app's main thread.
     */
    public CaltopoOp sendRequest(CtsMethod_t method, @NonNull String url, JSONObject payload, @Nullable Runnable onCompletion) {
        CaltopoOp op = new CaltopoOp(onCompletion);
        op.method = method;
        op.url = url;
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

                // Open a connection
                HttpURLConnection connection = (HttpURLConnection) new URL(op.url).openConnection();
                connection.setRequestMethod(op.method.toString());
                connection.setRequestProperty("User-Agent", "R2CRest/0.1");
                if (op.method == CtsMethod_t.POST && op.payload != null) {
                    String body = op.payload.toString(2);
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
                int responseCode = connection.getResponseCode();
                BufferedReader reader;

                // Handle the response stream
                if (responseCode == HttpURLConnection.HTTP_OK) {
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
                if (responseCode == HttpURLConnection.HTTP_OK) {
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



}

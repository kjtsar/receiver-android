package org.opendroneid.android.data;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import android.util.Log;

import androidx.annotation.NonNull;

/* Object for keeping track of communications to/from Caltopo server.
 * Note that each operation returns an integer operation number for the 
 * corresponding operation.   You can block your thread on that op #,
 * waiting for the operation to finish, or establish a CaltopoListener
 * to monitor completion status for all operations.   If neither option
 * is used, all state associated with an operation is destroyed at the
 * completion of the operation.
 */
public class CaltopoOp implements Future <CaltopoOp> {
    private static final String TAG = "CaltopoOp";
	private static final boolean DEBUG = false;
    public CaltopoSession cts;
    public long opNum;
    public long queuedTimestampMsec;
    public long sentTimestampMsec;
    public long receivedTimestampMsec;

    // the actual message to be sent - in case it needs to be resent:
    public CtsMethod_t method;
    public String url;
    public JSONObject payload;
	public static int lastOpNum;

    // response to the async message execution:
    public Future<CaltopoOp> asyncFuture; // if op was scheduled for execution.
	    
    public boolean goodResponse;    // Valid if receivedTimestampInMsec != 0;
    public String response;    // if receivedTimestampInMsec && goodResponse == false;
    public JSONObject responseJson; // if receivedTimestampInMsec && goodResponse == true;
	public boolean goNaked;
	    
    public CaltopoOp() {
		throw new RuntimeException("use: new CaltopoOp(CaltopoSession) instead.");
    }

    public CaltopoOp(CaltopoSession cts) {
		this.cts = cts;
		opNum = ++lastOpNum;
		queuedTimestampMsec = System.currentTimeMillis();
		if (DEBUG) Log.i(TAG, String.format(Locale.US, "creating op %d", opNum));
    }

    public long roundTripTimeInMsec() {
			return receivedTimestampMsec - queuedTimestampMsec;
    }
	
    public boolean success() {
	return (this.asyncFuture.isDone() && this.goodResponse);
    }

    public boolean fail() {
	return (asyncFuture.isDone() && !goodResponse);
    }

    public String getErrorResponse() { return response; }
	    
    public JSONObject getResponse() { return responseJson; }

	@Override
	@NonNull
	public String toString() {
		String jsonStringRep = "";
		String responseJsonStringRep = "";
		if (payload != null) {
			try {
				jsonStringRep = payload.toString(2);
			} catch (JSONException e) {
				Log.e(TAG, "payload.toString() raised:\n" + e);
			}
		}
		if (responseJson != null) {
			try {
				responseJsonStringRep = responseJson.toString(2);
			} catch (JSONException e) {
				Log.e(TAG, "responseJson.toString() raised:\n" + e);
			}
		}
		return String.format(Locale.US,
			"CaltopoOp %d: %s, %s, payload:\n%s\n  queued:%d\n  sent:%d\n  " +
					"received: %d  \n  good:%s, response:%s\n  jsonResponse:\n%s",
			opNum, method, url, jsonStringRep,
				queuedTimestampMsec, sentTimestampMsec, receivedTimestampMsec,
				goodResponse ? "true" : "false", response, responseJsonStringRep);
    }
	
	// syncOp... options for blocking until completion for results:
    public JSONObject syncOpJSONObject()
			throws ExecutionException, InterruptedException, JSONException {
		this.get();
		if (fail()) {
			throw new JSONException("Op failed - '" + response + "'");
		}
		if (null == responseJson) {
			throw new JSONException("op failed to return expected JSONObject in response.\n" + this);
		}
		return responseJson;
    }

	@NonNull
	public String responseString() {
		String msg = "";
		if (null != responseJson) {
			try {
				msg = responseJson.toString(4);
			} catch (JSONException e) {
				msg = (null != response) ? response : "";
			}
		}
		return msg;
	}

    public JSONObject syncOpJSONObject(double timeoutInSeconds)
			throws ExecutionException, InterruptedException,
			TimeoutException, JSONException {

		this.get((long)(timeoutInSeconds * 1000), TimeUnit.MILLISECONDS);
		if (fail()) {
			throw new JSONException("Op failed - '" + response + "'");
		}
		if (null == responseJson) {
			throw new JSONException("op failed to return expected JSONObject in response.\n" + this);
		}
		return responseJson;
    }

	public String id() throws JSONException {
		if (null == responseJson) {
			throw new JSONException("op failed to return expected JSONObject in response.\n" + this);
		}
		return responseJson.getString("id");
	}
    public String syncOpId()
			throws ExecutionException, InterruptedException, JSONException {
		syncOpJSONObject();
		return id();
	}


    public String syncOpId(double timeoutInSec)
			throws ExecutionException, InterruptedException,
			TimeoutException, JSONException {
		syncOpJSONObject(timeoutInSec);
		return id();
	}


    public void finalize() {
		if (DEBUG) Log.i(TAG, String.format(Locale.US, "destroying op %d", this.opNum));
    }

    // Future interface implementation:
    public boolean cancel(boolean mayInterruptIfRunning) {
		if (null == asyncFuture) {
			throw new RuntimeException("cancel() called on unscheduled operation.");
		}
		return asyncFuture.cancel(mayInterruptIfRunning);
    }

	
    public CaltopoOp get()
			throws ExecutionException, InterruptedException, RuntimeException {
		if (null == asyncFuture) {
			throw new RuntimeException("get() called on unscheduled operation.");
		}
		asyncFuture.get();
		return this;
    }

    public CaltopoOp get(long timeout, TimeUnit unit)
			throws ExecutionException, InterruptedException, TimeoutException {
		if (null == asyncFuture) {
			throw new RuntimeException("get() called on unscheduled operation.");
		}
		asyncFuture.get(timeout, unit);
		return this;
    }

    public boolean isCancelled() {
		if (null == asyncFuture) {
			throw new RuntimeException("isCancelled() called on unscheduled operation.");
		}
		return asyncFuture.isCancelled();
    }

    public boolean isDone() {
		if (null == asyncFuture) {
			throw new RuntimeException("isDone() called on unscheduled operation.");
		}
		return asyncFuture.isDone();
    }
	
} // end of CaltopoOp class spec.


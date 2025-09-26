
/*
 *
 */
package org.opendroneid.android.data;

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.Locale;

public class CtDroneSpec implements Comparable<CtDroneSpec>, Serializable {
    private static final long Version = 1L;
    private static final String TAG = "CtDroneSpec";

    private final String remoteId;
    private String mappedId;   /* The track label prefix assigned to drone */

    private String org;
    private String owner;
    private String model; /* This is the concise text description of the drone. */
    public long mostRecentTimeInSeconds; /* timestamp of most recent packet received */

    public CtDroneSpec() throws RuntimeException {
        throw new RuntimeException("Use one of the other constructor methods.");
    }
    public CtDroneSpec(String remoteId) throws RuntimeException {
        if (null == remoteId || remoteId.isEmpty()) {
            throw new RuntimeException("missing/invalid required remoteId spec.");
        }
        this.remoteId = remoteId;
        this.mappedId = remoteId;
        this.org = "";
        this.model = "";
        this.owner = "";
    }

    public CtDroneSpec(String remoteIdIn, String mappedIdIn, String orgIn, String modelIn, String ownerIn)
            throws RuntimeException {
        if (null == remoteIdIn || remoteIdIn.isEmpty()) {
            throw new RuntimeException("missing/invalid required remoteId spec.");
        }
        this.remoteId = remoteIdIn;
        if (null == mappedIdIn || mappedIdIn.isEmpty()) {
            this.mappedId = remoteIdIn;
        } else this.mappedId = mappedIdIn;

        if (null == orgIn) this.org = "";
        else this.org = orgIn;

        if (null == modelIn) this.model = "";
        else this.model = modelIn;

        if (null == ownerIn) this.owner = "";
        else this.owner = ownerIn;
    }

    public String setMappedId(@NonNull String newMappedId) {
        String newStr = newMappedId.replaceAll("[^a-zA-Z0-9]", "");
        if (!newStr.isEmpty()) {
            mappedId = newStr;
        }
        return mappedId;
    }

    public String getRemoteId() { return remoteId;}
    public String getMappedId() { return mappedId;}
    public String getOrg() { return org;}
    public String setOrg(String newVal) { return org = newVal;}
    public String getModel() { return model;}
    public String setModel(String newVal) { return model = newVal;}
    public String getOwner() { return owner;}
    public String setOwner(String newVal) { return owner = newVal;}


    /** merge a new dronespec into this spec.
     *  Don't override anything other than the default mappedId.
     *
     * @param newSpec Add the contents of newSpec to this spec.
     */
    public void mergeWithNew(CtDroneSpec newSpec) {
        CaltopoClient.CTInfo(TAG, String.format(Locale.US,
                "Merging new dronespec:%s\n into existing:%s",
                newSpec.toString(), this.toString()));
        // one exception is if the mappedId is same as remoteId (default)
        if (!this.remoteId.equals(this.mappedId)) {
            this.mappedId = newSpec.mappedId;
        }
        if (this.model.isEmpty()) this.model = newSpec.model;
        if (this.org.isEmpty()) this.org = newSpec.org;
        if (this.owner.isEmpty()) this.owner = newSpec.owner;
    }

     @Override
     @NonNull
     public String toString() {
        return String.format(Locale.US,
                "rid:'%s', mid:'%s', org:'%s', model:'%s', owner:'%s', timestamp:%d",
                remoteId, mappedId, org, model, owner, mostRecentTimeInSeconds);
     }

    /** Default sort
     *   First compares age of dronespecs.
     *   If ages same, then compares mappedIds.   If mappedIds same (shouldn't be),
     *   then compares remoteIds, which are guaranteed to be unique.
     *
     * @param  other to be compared against.
     * @return returns most recently seen towards end.
     */
    @Override
    public int compareTo(@NonNull CtDroneSpec other) {
        int retval;
        if (other.mostRecentTimeInSeconds == this.mostRecentTimeInSeconds) {
            retval = this.mappedId.compareTo(other.mappedId);
            if (0 == retval) {
                retval = this.remoteId.compareTo(other.remoteId);
            }
        } else if (other.mostRecentTimeInSeconds < this.mostRecentTimeInSeconds) {
            retval = -1;
        } else {
            retval = 1;
        }
        return retval;
    }

    @NonNull
    public CtDroneSpec clone() {
        CtDroneSpec ds = new CtDroneSpec(this.remoteId, this.mappedId, this.org, this.model, this.owner);
        ds.mostRecentTimeInSeconds = this.mostRecentTimeInSeconds;
        return ds;
    }

    public boolean sameAs(@NonNull CtDroneSpec other) {
        if (other.mostRecentTimeInSeconds != this.mostRecentTimeInSeconds) return false;
        if (!other.remoteId.equals(this.remoteId)) return false;
        if (!other.mappedId.equals(this.mappedId)) return false;
        if (!other.org.equals(this.org)) return false;
        if (!other.owner.equals(this.owner)) return false;
        return other.model.equals(this.model);
    }
     public int reverseCompareTo(@NonNull CtDroneSpec other) {
         return other.compareTo(this);
     }
 }


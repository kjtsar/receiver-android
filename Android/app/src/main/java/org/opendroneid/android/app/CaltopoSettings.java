package org.opendroneid.android.app;

import android.database.DataSetObserver;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import org.opendroneid.android.data.CaltopoClient;
import org.opendroneid.android.R;

import java.util.Hashtable;
import java.util.Locale;
import java.util.Map;

class ViewMap {
    String key; // Remote ID
    String val; // mapped value - default same as Remote ID
    TextView labelText; // The textfield containing the remote ID.
    EditText editText;  // The 'editable' textfield containing the current val
    Editable editable;  // The real editable portion of the edittext
    TextView msgCountText;
    long lastUnsavedMsgsCount;
    View convertView;
    boolean viewIsCurrent;
}

/**
 * Use the {@link CaltopoSettings#newInstance} factory method to
 * create an instance of this fragment.
 */
public class CaltopoSettings extends DialogFragment implements TextWatcher, ListAdapter, View.OnClickListener {
    private static final String TAG = "CaltopoSettings";

    public CaltopoSettings() {
        // Required empty public constructor
    }
    private View settingsView;
    EditText groupIdText;
    String groupIdTextVal;
    EditText minChangedText;
    long minChangedVal;
    TextView archivePathText;
    Button saveChanges;
    Button closeButton;
    Button archiveDirButton;
    ListView mapListView;
    LayoutInflater inflater;
    ViewMap[] viewMaps;
    Hashtable<String, String> htClone;

    public static CaltopoSettings newInstance() {
        return new CaltopoSettings();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
    public void beforeTextChanged(CharSequence s, int start, int count, int after) {
    }
    public void onTextChanged(CharSequence s, int start, int before, int count) {
    }
    public void afterTextChanged(Editable e) {
     //   Log.i(TAG, String.format("afterTextChanged(%X: '%s'", System.identityHashCode(e), e.toString()));
        saveChanges.setEnabled(true);
    }

    public void checkGroupId() {
        String newVal = groupIdText.getText().toString();
        if (0 != newVal.compareTo(groupIdTextVal)) {
            Log.i(TAG, String.format("groupId changing to '%s' from '%s'", newVal, groupIdTextVal));
            groupIdTextVal = CaltopoClient.setGroupId(newVal);
            if (0 != groupIdTextVal.compareTo(newVal)) {
                groupIdText.setText(groupIdTextVal);
                Log.i(TAG, String.format("... but CaltopoClient went with '%s' instead.", groupIdTextVal));
            }
        }
    }

    public void checkMinDistance() {
        String rawInput = minChangedText.getText().toString();
        Log.i(TAG, String.format("checkMinDistance read string '%s'.", rawInput));
        long inputVal;

        try {
            inputVal = Long.parseLong(rawInput);

        } catch (NumberFormatException e) {
            Log.i(TAG, String.format("checkMinDistance(%s) not a valid numeric value.", rawInput));
            minChangedText.setText(String.format(Locale.US, "%d", minChangedVal));
            inputVal = 0;
        }

        if (inputVal != minChangedVal) {
            Log.i(TAG, String.format("minDistanceInDegrees changing to '%d' from '%d'.", inputVal, minChangedVal));
            minChangedVal = CaltopoClient.setMinDistanceInFeet(inputVal);
            if (inputVal != minChangedVal) {
                minChangedText.setText(String.format(Locale.US, "%d", minChangedVal));
                Log.i(TAG, String.format("... but CaltopoClient went with '%d' instead of '%d'.",
                        minChangedVal, inputVal));
            }
        }
    }
    private void checkRidMap() {
        int i;

        for (i = 0; i < viewMaps.length; i++) {
            ViewMap vm = viewMaps[i];
            String newVal = vm.editable.toString();
    //        Log.i(TAG, String.format("ridMap[%s]%d testing '%s' to '%s'", vm.key, System.identityHashCode(vm.editable), vm.val, newVal));
            CaltopoClient client = CaltopoClient.clientForRemoteId(vm.key);
            if (0 != newVal.compareTo(vm.val)) {
                Log.i(TAG, String.format("ridMap[%s] changing from '%s' to '%s'", vm.key, vm.val, newVal));
                String setVal = client.setMappedId(newVal);
                if (0 != newVal.compareTo(setVal)) {
                    Log.i(TAG, String.format("... but CaltopoClient disallowed - changing to '%s'",setVal));
                    vm.viewIsCurrent = false;
                }
                vm.val = newVal;
            }

            if (vm.lastUnsavedMsgsCount != client.unsavedMsgCount()) {
                vm.viewIsCurrent = false;
            }
        }
    }

    // User pushed the "save" or "close" button - check 4 && save any changes:
    public void onClick(View v){
        if (v == archivePathText || v == archiveDirButton) {
            CaltopoClient.queryUserForArchiveDir();
        } else if (v != saveChanges) {
            dismiss();
            return;
        }
        saveChanges.setEnabled(false);
        checkGroupId();
        checkMinDistance();
        checkRidMap();
        (DebugActivity.getDebugActivity()).archiveTracks();
    }

    @Override public boolean isEnabled(int pos) { return true;}

    @Override public boolean areAllItemsEnabled() {return true;}

    @Override public int getViewTypeCount() {return 1;}

    @Override public long getItemId(int pos) {
        return pos ;
    }
    @Override public int getItemViewType(int pos) {
        return 1;
    }
    @Override public void registerDataSetObserver(DataSetObserver obs) {

    }
    @Override public void unregisterDataSetObserver(DataSetObserver obs) {

    }
    @Override public View getView(int pos, @Nullable View convertView, @NonNull ViewGroup parent) {
        ViewMap vm = viewMaps[pos];
        CaltopoClient client;

        if (null == convertView || null == vm.convertView) {
            // Log.i(TAG, String.format("getView(%d)%d,%d fabricating view for key:%s, val:%s.", pos, System.identityHashCode(vm.convertView), System.identityHashCode(convertView), vm.key, vm.val));
            if (null == vm.convertView) {
                vm.convertView = inflater.inflate(R.layout.listitem_ctmap, parent, false);
            }
            vm.viewIsCurrent = false;
        }

        if (!vm.viewIsCurrent) {
        //    Log.i(TAG, String.format("getView(%d)%d not current for key:%s, val:%s.", pos, System.identityHashCode(vm.convertView), vm.key, vm.val));
            vm.labelText = vm.convertView.findViewById(R.id.ct_mapLabel);
            vm.labelText.setText(vm.key);
            vm.editText = vm.convertView.findViewById(R.id.ct_mapText);
            vm.editText.setText(vm.val);
            vm.editable = vm.editText.getText();
    //        Log.i(TAG, String.format("getView(%d)%d editable for key:%s, val:%s.", pos, System.identityHashCode(vm.editable), vm.key, vm.val));
            vm.editText.addTextChangedListener(this);
            vm.msgCountText = vm.convertView.findViewById(R.id.msgCount);
            client = CaltopoClient.clientForRemoteId(vm.key);
            if (null != client) {
                long unsavedMsgCount = client.unsavedMsgCount();
                vm.msgCountText.setText(String.format(Locale.US, "%d", unsavedMsgCount));
                vm.lastUnsavedMsgsCount = unsavedMsgCount;
            }
            vm.viewIsCurrent = true;
        } else {
    //        Log.i(TAG, String.format("getView(%d)%d is current for editable:%d key:%s, val:%s.", pos,
    //                System.identityHashCode(vm.convertView), System.identityHashCode(vm.editable), vm.key, vm.val));
        }
        return vm.convertView;
    }

    @Override public boolean hasStableIds() {return true;}

    @Override public boolean isEmpty() {
        return ((null != viewMaps) && (0 != viewMaps.length));
    }

    @Override public int getCount() {
        int retval = 0;

        buildViewMap(); // fixme: better than polling would be to have the CaltopoClient notify us when the map has changed.
        if (null != viewMaps) retval = viewMaps.length;
        return retval;
    }

    @Override public Object getItem(int pos) {
        return viewMaps[pos];
    }

    // Build viewMap for the gridview
    @SuppressWarnings("unchecked")
    private void buildViewMap() {
        Hashtable<String, String> ht = CaltopoClient.getRidTable();

        if ((null != ht) && (null != htClone)) {
            //      Log.i(TAG, String.format("buildViewMap() checking old ht:%s\n\nto new ht:%s\n."
            //            CaltopoClient.htStringRep(htClone), CaltopoClient.htStringRep(ht)));

            if (ht.equals(htClone)) return;
        }
        if (null == ht) {
            viewMaps = null;
            return;
        }

        htClone = (Hashtable<String, String>)ht.clone();

        if ((null == viewMaps) || (viewMaps.length != ht.size())) {
            viewMaps = new ViewMap[ht.size()];
        }
        int i = 0;
        for (Map.Entry<String, String>map : ht.entrySet()) {
            String key = map.getKey(), val = map.getValue();
            if (null == viewMaps[i]) {
                viewMaps[i] = new ViewMap();
            }
            viewMaps[i].key = key;
            viewMaps[i].val = val;
            viewMaps[i].viewIsCurrent = false;
     //       Log.i(TAG, String.format("buildViewMap(%d) key:%s, val:%s.", i, key, val));
            i++;
        }
    }


    @Override @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        String archivePathVal = CaltopoClient.getArchivePath();
        this.inflater = inflater;
        settingsView = inflater.inflate(R.layout.fragment_caltopo_settings, container, false);
        saveChanges = settingsView.findViewById(R.id.ct_saveButton);
        saveChanges.setOnClickListener(this);
        saveChanges.setEnabled(false);
        closeButton = settingsView.findViewById(R.id.ct_closeButton);
        closeButton.setOnClickListener(this);
        groupIdText = settingsView.findViewById(R.id.groupIdText);
        groupIdTextVal = CaltopoClient.getGroupId();
        groupIdText.setText(groupIdTextVal);
        groupIdText.addTextChangedListener(this);
        archivePathText = settingsView.findViewById(R.id.archiveDirVal);
        archivePathText.setText(archivePathVal == null ? "<undefined>" : archivePathVal);
        archivePathText.setOnClickListener(this);
        archiveDirButton = settingsView.findViewById(R.id.archiveDirButton);
        archiveDirButton.setOnClickListener(this);

        minChangedText = settingsView.findViewById(R.id.minChangedText);
        minChangedVal = CaltopoClient.getMinDistanceInFeet();
        minChangedText.setText(String.format(Locale.US, "%d", minChangedVal));
        minChangedText.addTextChangedListener(this);
        mapListView = settingsView.findViewById(R.id.ct_mapListview);
        mapListView.setAdapter(this);
        return settingsView;
    }
}
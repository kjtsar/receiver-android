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
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentTransaction;

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
    Button finishTrackButton;
    CaltopoClient ctClient;
}

/**
 * Use the {@link CaltopoSettings#newInstance} factory method to
 * create an instance of this fragment.
 */
public class CaltopoSettings extends DialogFragment implements TextWatcher, ListAdapter, View.OnClickListener {
    private static final String TAG = "CaltopoSettings";
    private static final String GROUP_ID_LABEL = "Group Id";
    private static final String MAP_ID_LABEL = "Map Id";

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
    TextView groupMapLabel;
    ToggleButton directToggle;

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
        String oldVal;

        Boolean useDirectFlag = CaltopoClient.getUseDirectFlag();
        if (useDirectFlag) {
            // then groupId is being used as Map Id:
            oldVal = CaltopoClient.getMapId();
        } else {
            oldVal = groupIdTextVal;
        }
        if (0 != newVal.compareTo(oldVal)) {
            Log.i(TAG, String.format(Locale.US, "%s changing to '%s' from '%s'",
                    useDirectFlag ? MAP_ID_LABEL : GROUP_ID_LABEL,  newVal, oldVal));
            if (useDirectFlag) {
                oldVal = CaltopoClient.setMapId(newVal);
            } else {
                oldVal = CaltopoClient.setGroupId(newVal);
            }
            if (0 != oldVal.compareTo(newVal)) {
                groupIdText.setText(oldVal);
                Log.i(TAG, String.format("... but CaltopoClient went with '%s' instead.", oldVal));
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
            vm.finishTrackButton = vm.convertView.findViewById(R.id.finishTrack);
            if (CaltopoClient.getUseDirectFlag()) {
                vm.finishTrackButton.setEnabled(true);
                vm.finishTrackButton.setText("Finish");
                vm.finishTrackButton.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        Log.i(TAG, String.format(Locale.US, "starting new track for '%s:%s'",
                                vm.key, vm.val));
                        (DebugActivity.getDebugActivity()).archiveTracks();
                        vm.msgCountText.setText("0");
                        vm.ctClient.finishTrack();
                    }
                });
            } else {
                vm.finishTrackButton.setEnabled(false);
                vm.finishTrackButton.setText("");
                vm.finishTrackButton.setOnClickListener(null);
            }

            long unsavedMsgCount = vm.ctClient.unsavedMsgCount();
            vm.msgCountText.setText(String.format(Locale.US, "%d", unsavedMsgCount));
            vm.lastUnsavedMsgsCount = unsavedMsgCount;
            vm.viewIsCurrent = true;
  //          Log.i(TAG, String.format(Locale.US, "getView(%d:%s(%d)) now current.", pos, vm.val, vm.convertView.hashCode()));
        } else {
  //          Log.i(TAG, String.format(Locale.US, "getView(%d:%s(%d)) is current.", pos, vm.val, vm.convertView.hashCode()));
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
            viewMaps[i].ctClient = CaltopoClient.clientForRemoteId(key);
            i++;
        }
    }

    public void updateViewMaps() {
        if (null == viewMaps) return;

        for (int i = 0; i < viewMaps.length; i++) {
            viewMaps[i].viewIsCurrent = false;
            Log.i(TAG, String.format(Locale.US, "updateViewMaps(%s) not current.", viewMaps[i].val));
        }
        mapListView.invalidateViews();
    }
    public void runCaltopoDirectConfigPanel() {
        CaltopoDirectSettings configPanel = new CaltopoDirectSettings();
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        Log.d(TAG, "runCaltopoDirectConfigPanel(): starting CaltopoDirectSettings...");
        configPanel.show(transaction, "CaltopoDirectSettings");
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
        groupMapLabel = settingsView.findViewById(R.id.groupLabelText);
        directToggle = settingsView.findViewById(R.id.ct_directToggle);

        boolean useDirect = CaltopoClient.getUseDirectFlag();

        if (useDirect) {
            directToggle.setChecked(true);
            groupMapLabel.setText(MAP_ID_LABEL);
            groupIdText.setText(CaltopoClient.getMapId());
        } else {
            directToggle.setChecked(false);
            groupMapLabel.setText(GROUP_ID_LABEL);
            groupIdText.setText(groupIdTextVal);
        }
        directToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                // This block of code will be executed when the checked state changes
                if (isChecked) {

                    // ToggleButton is ON
                    Log.i(TAG, "directToggle is ON");
                    // Configure for direct tracking (groupIdText now MapId):
                    groupMapLabel.setText(MAP_ID_LABEL);
                    runCaltopoDirectConfigPanel();
                    groupIdText.setText(CaltopoClient.getMapId());
                    CaltopoClient.setUseDirect(true);
                } else {

                    // ToggleButton is OFF
                    Log.i(TAG, "directToggle is OFF");
                    // Configure for live tracking (groupIdText now groupId):
                    groupMapLabel.setText(GROUP_ID_LABEL);
                    groupIdText.setText(groupIdTextVal);
                    CaltopoClient.setUseDirect(false);
                }
                updateViewMaps();
            }
        });

        return settingsView;
    }
}
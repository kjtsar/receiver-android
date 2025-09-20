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
import org.opendroneid.android.data.CtDroneSpec;

import java.util.ArrayList;
import java.util.Locale;

class ViewMap {
    String remoteId; // Remote ID
    TextView labelText; // The textfield containing the remote ID.
    EditText ridEditText;  // The 'editable' textfield containing the remote id
    EditText orgEditText;
    EditText modelEditText;
    EditText ownerEditText;
    TextView msgCountText;
    long lastUnsavedMsgsCount;
    View convertView;
    boolean viewIsCurrent;
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
    EditText mapIdText;
    EditText minChangedText;
    EditText newTrackDelayInSecEditText;
    EditText maxAgeInSecEditText;
    long minChangedVal;
    TextView archivePathText;
    Button saveChanges;
    Button closeButton;
    Button archiveDirButton;
    ListView mapListView;
    LayoutInflater inflater;
    ViewMap[] viewMaps;
    TextView groupMapLabel;
    ToggleButton directToggle;

    ArrayList<CtDroneSpec>currentDroneSpecsClone;

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
        String newVal = groupIdText.getText().toString().trim();
        String oldVal = CaltopoClient.GetGroupId();

        if (!newVal.equals(oldVal)) {
            Log.i(TAG, String.format(Locale.US, "%s changing to '%s' from '%s'",
                    GROUP_ID_LABEL,  newVal, oldVal));
            oldVal = CaltopoClient.SetGroupId(newVal);
            if (!oldVal.equals(newVal)) {
                groupIdText.setText(oldVal);
                Log.i(TAG, String.format("... but CaltopoClient went with '%s' instead.", oldVal));
            }
        }
    }


    public void checkMapId() {
        String newVal = mapIdText.getText().toString().trim();
        String oldVal = CaltopoClient.GetMapId();

        if (!newVal.equals(oldVal)) {
            Log.i(TAG, String.format(Locale.US, "%s changing to '%s' from '%s'",
                    MAP_ID_LABEL,  newVal, oldVal));
            oldVal = CaltopoClient.SetMapId(newVal);
            if (!oldVal.equals(newVal)) {
                mapIdText.setText(oldVal);
                Log.i(TAG, String.format("... but CaltopoClient went with '%s' instead.", oldVal));
            }
        }
    }

    public void checkMinDistance() {
        String rawInput = minChangedText.getText().toString().trim();
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

    public void checkNewTrackDelay() {
        String rawInput = newTrackDelayInSecEditText.getText().toString().trim();
        long inputVal;
        long currentVal = CaltopoClient.GetNewTrackDelayInSeconds();

        try {
            inputVal = Long.parseLong(rawInput);
        } catch (NumberFormatException e) {
            Log.i(TAG, String.format("checkNewTrackDelay(%s) not a valid numeric value.", rawInput));
            minChangedText.setText(String.format(Locale.US, "%d", currentVal));
            return;
        }

        if (inputVal != currentVal) {
            Log.i(TAG, String.format("NewTrackDelayInSeconds changing to '%d' from '%d'.", inputVal, currentVal));
            currentVal = CaltopoClient.SetNewTrackDelayInSeconds(inputVal);
            if (inputVal != currentVal) {
                minChangedText.setText(String.format(Locale.US, "%d", currentVal));
                Log.i(TAG, String.format("... but CaltopoClient went with '%d' instead of '%d'.",
                        currentVal, inputVal));
            }
        }
    }
    private void checkMaxDisplayAgeInSec() {
        String rawInput = maxAgeInSecEditText.getText().toString().trim();
        long inputVal;
        long currentVal = CaltopoClient.GetMaxDisplayAgeInSeconds();

        try {
            inputVal = Long.parseLong(rawInput);
        } catch (NumberFormatException e) {
            Log.i(TAG, String.format("checkMaxDisplayAgeInSec(%s) not a valid numeric value.", rawInput));
            maxAgeInSecEditText.setText(String.format(Locale.US, "%d", currentVal));
            return;
        }
        if (inputVal != currentVal) {
            Log.i(TAG, String.format("MaxDelayInSeconds changing to '%d' from '%d'.", inputVal, currentVal));
            currentVal = CaltopoClient.SetMaxDisplayAgeInSeconds(inputVal);
        }
    }
    private void checkRidMap() {
        if (null == viewMaps) return;
        for (int i = 0; i < viewMaps.length; i++) {
            ViewMap vm = viewMaps[i];
            CtDroneSpec ds = vm.ctClient.getDroneSpec().clone();
            boolean dsChanged = false;

            String newVal = vm.ridEditText.getText().toString().trim();
            if (!newVal.equals(ds.mappedId)) {
                Log.i(TAG, String.format(Locale.US,
                        "ridMap[%s] changing mappedId from '%s' to '%s'", vm.remoteId, ds.mappedId, newVal));
                ds.mappedId = newVal; dsChanged = true;
            }

            newVal = vm.orgEditText.getText().toString().trim();
            if (!newVal.isEmpty() && !newVal.equals(ds.org)) {
                Log.i(TAG, String.format(Locale.US,
                        "ridMap[%s] changing org from '%s' to '%s'", vm.remoteId, ds.org, newVal));
                ds.org = newVal; dsChanged = true;
            }

            newVal = vm.modelEditText.getText().toString().trim();
            if (!newVal.isEmpty() && !newVal.equals(ds.model)) {
                Log.i(TAG, String.format(Locale.US,
                        "ridMap[%s] changing model from '%s' to '%s'", vm.remoteId, ds.model, newVal));
                ds.model = newVal; dsChanged = true;
            }

            newVal = vm.ownerEditText.getText().toString().trim();
            if (!newVal.isEmpty() && !newVal.equals(ds.owner)) {
                Log.i(TAG, String.format(Locale.US,
                        "ridMap[%s] changing owner from '%s' to '%s'", vm.remoteId, ds.owner, newVal));
                ds.owner = newVal; dsChanged = true;
            }

            if (dsChanged) {
                vm.viewIsCurrent = false;
                vm.ctClient.setDroneSpec(ds);
            }

            if (vm.lastUnsavedMsgsCount != vm.ctClient.unsavedMsgCount()) {
                vm.viewIsCurrent = false;
            }
        }
    }

    // User pushed the "save" or "close" button - check 4 && save any changes:
    public void onClick(View v){
        if (v == archivePathText || v == archiveDirButton) {
            CaltopoClient.QueryUserForArchiveDir();
        }

        saveChanges.setEnabled(false);
        checkGroupId();
        checkMapId();
        checkMinDistance();
        checkNewTrackDelay();
        checkMaxDisplayAgeInSec();
        checkRidMap();
        (DebugActivity.getDebugActivity()).archiveTracks();
        dismiss();
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
            CtDroneSpec ds = CaltopoClient.DroneSpecForRemoteId(vm.remoteId);
        //    Log.i(TAG, String.format("getView(%d)%d not current for key:%s, val:%s.", pos, System.identityHashCode(vm.convertView), vm.key, vm.val));
            vm.labelText = vm.convertView.findViewById(R.id.ct_mapLabel);
            vm.labelText.setText(vm.remoteId);
            vm.ridEditText = vm.convertView.findViewById(R.id.ct_mapText);
            vm.ridEditText.setText(ds.mappedId);

    //        Log.i(TAG, String.format("getView(%d)%d editable for key:%s, val:%s.", pos, System.identityHashCode(vm.editable), vm.key, vm.val));
            vm.ridEditText.addTextChangedListener(this);

            vm.orgEditText = vm.convertView.findViewById(R.id.org);
            vm.orgEditText.setText(ds.org);
            vm.orgEditText.addTextChangedListener(this);

            vm.ownerEditText = vm.convertView.findViewById(R.id.owner);
            vm.ownerEditText.setText(ds.owner);
            vm.ownerEditText.addTextChangedListener(this);

            vm.modelEditText = vm.convertView.findViewById(R.id.model);
            vm.modelEditText.setText(ds.model);
            vm.modelEditText.addTextChangedListener(this);

            vm.msgCountText = vm.convertView.findViewById(R.id.msgCount);

            long unsavedMsgCount = vm.ctClient.unsavedMsgCount();
            vm.msgCountText.setText(String.format(Locale.US, "%d", unsavedMsgCount));
            vm.lastUnsavedMsgsCount = unsavedMsgCount;
            vm.viewIsCurrent = true;
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

    /** Build viewMap for the gridview using remoteIds that have been seen in the past day.
     *
     */
    @SuppressWarnings("unchecked")
    private void buildViewMap() {
        long ageInSec = CaltopoClient.GetMaxDisplayAgeInSeconds();
        ArrayList<CtDroneSpec> currentDroneSpecs = CaltopoClient.GetSortedCurrentDroneSpecArray(ageInSec);
        if (null != currentDroneSpecsClone && currentDroneSpecsClone.equals(currentDroneSpecs)) return;
        currentDroneSpecsClone = (ArrayList<CtDroneSpec>)currentDroneSpecs.clone();

        int size = currentDroneSpecsClone.size();
        if (0 == size) return;

        if ((null == viewMaps) || (viewMaps.length != size)) {
            viewMaps = new ViewMap[size];
        }
        for (int i=0; i < size; i++) {
            CtDroneSpec ds = currentDroneSpecsClone.get(i);
            if (null == viewMaps[i]) viewMaps[i] = new ViewMap();
            viewMaps[i].remoteId = ds.remoteId;
            viewMaps[i].ctClient = CaltopoClient.ClientForRemoteId(ds.remoteId);
            viewMaps[i].viewIsCurrent = false;
        }
    }

    public void updateViewMaps() {
        if (null == viewMaps) return;

        for (ViewMap viewMap : viewMaps) {
            viewMap.viewIsCurrent = false;
            Log.i(TAG, String.format(Locale.US, "updateViewMaps(%s) not current.", viewMap.remoteId));
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


        this.inflater = inflater;
        settingsView = inflater.inflate(R.layout.fragment_caltopo_settings, container, false);
        saveChanges = settingsView.findViewById(R.id.ct_saveButton);
        saveChanges.setOnClickListener(this);
        saveChanges.setEnabled(false);
        closeButton = settingsView.findViewById(R.id.ct_closeButton);
        closeButton.setOnClickListener(this);

        groupIdText = settingsView.findViewById(R.id.groupIdText);
        groupIdText.setText(CaltopoClient.GetGroupId());
        groupIdText.addTextChangedListener(this);

        mapIdText = settingsView.findViewById(R.id.mapIdEditText);
        mapIdText.setText(CaltopoClient.GetMapId());
        mapIdText.addTextChangedListener(this);

        String archivePathVal = CaltopoClient.GetArchivePath();
        archivePathText = settingsView.findViewById(R.id.archiveDirVal);
        archivePathText.setText(archivePathVal == null ? "<undefined>" : archivePathVal);
        archivePathText.setOnClickListener(this);
        archiveDirButton = settingsView.findViewById(R.id.archiveDirButton);
        archiveDirButton.setOnClickListener(this);

        minChangedText = settingsView.findViewById(R.id.minChangedText);
        minChangedVal = CaltopoClient.GetMinDistanceInFeet();
        minChangedText.setText(String.format(Locale.US, "%d", minChangedVal));
        minChangedText.addTextChangedListener(this);

        newTrackDelayInSecEditText = settingsView.findViewById(R.id.newTrackDelayInSecEditText);
        newTrackDelayInSecEditText.setText(String.format(Locale.US, "%d",
                CaltopoClient.GetNewTrackDelayInSeconds()));
        newTrackDelayInSecEditText.addTextChangedListener(this);

        maxAgeInSecEditText = settingsView.findViewById(R.id.maxAgeInSecEditText);
        maxAgeInSecEditText.setText(String.format(Locale.US, "%d",
                CaltopoClient.GetMaxDisplayAgeInSeconds()));
        maxAgeInSecEditText.addTextChangedListener(this);

        mapListView = settingsView.findViewById(R.id.ct_mapListview);
        mapListView.setAdapter(this);
        groupMapLabel = settingsView.findViewById(R.id.groupLabelText);
        directToggle = settingsView.findViewById(R.id.ct_directToggle);

        groupIdText.setText(CaltopoClient.GetGroupId());
        mapIdText.setText(CaltopoClient.GetMapId());
        boolean useDirect = CaltopoClient.GetUseDirectFlag();
        directToggle.setChecked(useDirect);
        mapIdText.setEnabled(useDirect);

        directToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                // This block of code will be executed when the checked state changes
                Log.i(TAG, "directToggle is " + String.valueOf(isChecked));
                if (isChecked) {
                    runCaltopoDirectConfigPanel();
                    mapIdText.setText(CaltopoClient.GetMapId());
                }
                mapIdText.setEnabled(isChecked);
                CaltopoClient.SetUseDirect(isChecked);
                updateViewMaps();
            }
        });

        return settingsView;
    }
}
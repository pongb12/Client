package net.kdt.pojavlaunch.prefs.screens;

import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.multirt.MultiRTConfigDialog;
import net.kdt.pojavlaunch.prefs.CustomSeekBarPreference;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DeviceCapabilityDetector;

public class LauncherPreferenceJavaFragment extends LauncherPreferenceFragment {
    private MultiRTConfigDialog mDialogScreen;
    private SwitchPreference mSwitchAutoJRE;
    private final ActivityResultLauncher<Object> mVmInstallLauncher =
            registerForActivityResult(new OpenDocumentWithExtension("xz"), (data)->{
                if(data != null) Tools.installRuntimeFromUri(getContext(), data);
            });

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mSwitchAutoJRE = findPreference("disable_autojre_select");
        mSwitchAutoJRE.setSummary("Stops automatic selection of which runtime to use in \"" + getString(R.string.main_install_jar_file) + "\"");

    }

    @Override
    public void onCreatePreferences(Bundle b, String str) {
        int ramAllocation = LauncherPreferences.PREF_RAM_ALLOCATION;
        // Triggers a write for some reason
        addPreferencesFromResource(R.xml.pref_java);

        CustomSeekBarPreference memorySeekbar = requirePreference("allocation",
                CustomSeekBarPreference.class);

        // Capability-based ceiling: the max heap must leave Android, the GPU driver
        // and zRAM enough RAM to breathe on low-end devices.
        int maxRAM = DeviceCapabilityDetector.getRamAllocationCeilingMb(memorySeekbar.getContext());
        int sliderMin = getResources().getInteger(R.integer.memory_seekbar_min);

        // CustomSeekBarPreference stores (rawProgress + min); reduce the raw max so the
        // displayed value can never exceed the real ceiling.
        memorySeekbar.setMaxKeepIncrement(Math.max(0, maxRAM - sliderMin));

        // Self-heal installs that saved an over-allocation before the ceiling existed.
        if (ramAllocation > maxRAM) {
            ramAllocation = maxRAM;
            LauncherPreferences.DEFAULT_PREF.edit().putInt("allocation", maxRAM).apply();
            LauncherPreferences.PREF_RAM_ALLOCATION = maxRAM;
        }

        memorySeekbar.setValue(ramAllocation);
        memorySeekbar.setSuffix(" MB");

        EditTextPreference editJVMArgs = findPreference("javaArgs");
        if (editJVMArgs != null) {
            editJVMArgs.setOnBindEditTextListener(TextView::setSingleLine);
        }

        requirePreference("install_jre").setOnPreferenceClickListener(preference->{
            openMultiRTDialog();
            return true;
        });
    }

    private void openMultiRTDialog() {
        if (mDialogScreen == null) {
            mDialogScreen = new MultiRTConfigDialog();
            mDialogScreen.prepare(getContext(), mVmInstallLauncher);
        }
        mDialogScreen.show();
    }
}

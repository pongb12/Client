package net.kdt.pojavlaunch.prefs.screens;

import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreference;
import androidx.preference.SwitchPreferenceCompat;

import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.contracts.OpenDocumentWithExtension;
import net.kdt.pojavlaunch.multirt.MultiRTConfigDialog;
import net.kdt.pojavlaunch.prefs.CustomSeekBarPreference;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.DeviceCapabilityDetector;
import net.kdt.pojavlaunch.utils.MemoryOptimizer;

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

        maybeShowRiskAcknowledgment();
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

        setupRamOptimizationPreferences(memorySeekbar);
    }

    /**
     * First access to the RAM optimization settings: show the risk
     * acknowledgment dialog. It is shown once (key ramRiskAckV1) and only
     * comes back if the user clears the launcher data.
     */
    private void maybeShowRiskAcknowledgment() {
        if (LauncherPreferences.DEFAULT_PREF.getBoolean(LauncherPreferences.PREF_KEY_RAM_RISK_ACK, false)) return;
        new AlertDialog.Builder(requireActivity())
                .setTitle(R.string.ram_risk_warning_title)
                .setMessage(R.string.ram_risk_warning)
                .setPositiveButton(R.string.ram_risk_accept, (d, w) ->
                        LauncherPreferences.DEFAULT_PREF.edit()
                                .putBoolean(LauncherPreferences.PREF_KEY_RAM_RISK_ACK, true).apply())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void setupRamOptimizationPreferences(CustomSeekBarPreference memorySeekbar) {
        Preference cleanupPref = findPreference("ramAggressiveCleanup");
        if (cleanupPref != null) {
            // The cleanup switch is low risk: it only affects the launcher
            // process itself, so it toggles without a confirmation dialog.
            cleanupPref.setOnPreferenceChangeListener((p, newValue) -> {
                LauncherPreferences.PREF_RAM_AGGRESSIVE_CLEANUP = (Boolean) newValue;
                return true;
            });
        }

        SwitchPreferenceCompat autoRam = findPreference("autoRamAllocation");
        if (autoRam != null) {
            autoRam.setOnPreferenceChangeListener((p, newValue) -> {
                boolean enabled = (Boolean) newValue;
                if (enabled) {
                    showRamFunctionDialog(getString(R.string.ram_auto_allocation_title),
                            R.string.ram_function_confirm_auto,
                            () -> {
                                // Note: programmatic setChecked does not fire
                                // this listener, so the static is set here.
                                LauncherPreferences.PREF_AUTO_RAM_ALLOCATION = true;
                                autoRam.setChecked(true);
                                memorySeekbar.setEnabled(false);
                            });
                    return false; // The dialog applies the change on confirmation.
                }
                LauncherPreferences.PREF_AUTO_RAM_ALLOCATION = false;
                memorySeekbar.setEnabled(true);
                return true;
            });
            // Reflect the persisted state; also covers the case where the user
            // re-enters the screen while automatic allocation is active.
            autoRam.setChecked(LauncherPreferences.PREF_AUTO_RAM_ALLOCATION);
            memorySeekbar.setEnabled(!LauncherPreferences.PREF_AUTO_RAM_ALLOCATION);
        }

        ListPreference gcProfile = findPreference("ramGcProfile");
        if (gcProfile != null) {
            gcProfile.setValue(LauncherPreferences.PREF_RAM_GC_PROFILE);
            gcProfile.setOnPreferenceChangeListener((p, newValue) -> {
                String value = (String) newValue;
                if (MemoryOptimizer.GC_PROFILE_DEFAULT.equals(value)) {
                    LauncherPreferences.PREF_RAM_GC_PROFILE = value;
                    return true;
                }
                showRamFunctionDialog(getString(R.string.ram_gc_profile_title),
                        R.string.ram_function_confirm_gc,
                        () -> {
                            // setValue() below persists but does not fire this
                            // listener, so the static is set here as well.
                            LauncherPreferences.PREF_RAM_GC_PROFILE = value;
                            gcProfile.setValue(value);
                        });
                return false;
            });
        }
    }

    /**
     * Per-function confirmation, shown when the user turns on one of the RAM
     * functions that can affect game stability (auto -Xmx, GC profile).
     * The change is only applied from the confirmation callback.
     */
    private void showRamFunctionDialog(String functionTitle, int messageRes, Runnable onConfirmed) {
        new AlertDialog.Builder(requireActivity())
                .setTitle(functionTitle)
                .setMessage(messageRes)
                .setPositiveButton(R.string.ram_risk_accept, (d, w) -> onConfirmed.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void openMultiRTDialog() {
        if (mDialogScreen == null) {
            mDialogScreen = new MultiRTConfigDialog();
            mDialogScreen.prepare(getContext(), mVmInstallLauncher);
        }
        mDialogScreen.show();
    }
}

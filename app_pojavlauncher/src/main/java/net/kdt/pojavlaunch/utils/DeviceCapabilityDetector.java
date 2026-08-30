package net.kdt.pojavlaunch.utils;

import android.app.ActivityManager;
import android.content.Context;

import net.kdt.pojavlaunch.Architecture;
import net.kdt.pojavlaunch.R;
import net.kdt.pojavlaunch.Tools;

/**
 * Capability-based detection of low-end hardware, used to derive conservative
 * launcher/runtime defaults (heap ceiling, JVM-visible CPU count).
 *
 * Detection relies on device capabilities (total RAM, ActivityManager low-RAM
 * flag, CPU core count) instead of device model names, so it generalizes to
 * every constrained phone rather than a single model.
 *
 * Reference constrained target: 4 GB physical RAM ("4 GB" phones usually report
 * ~3.6-3.8 GB to ActivityManager because of carve-outs), 8x Cortex-A53 class
 * CPU (Helio G35), PowerVR GE8320 class GPU. On such hardware the cores are
 * slow and homogeneous: 8-way JVM thread pools mostly add context switches,
 * memory-bandwidth contention and heat instead of throughput, and committing a
 * huge heap triggers zRAM pressure and aggressive Android reclaim.
 */
public final class DeviceCapabilityDetector {
    /** Total device RAM (MB) at or below which only 2 JVM-visible CPUs are recommended. */
    private static final int VERY_LOW_RAM_THRESHOLD_MB = 2048;
    /** Total device RAM (MB) at or below which the device counts as low-end. */
    private static final int LOW_RAM_THRESHOLD_MB = 4096;
    /** RAM reserved for Android + launcher + native graphics on >= 3 GB devices (MB). */
    private static final int RAM_RESERVE_MB = 1024;
    /** Slightly smaller reserve for 2-3 GB devices, matching historical behavior (MB). */
    private static final int RAM_RESERVE_SMALL_DEVICE_MB = 800;

    private DeviceCapabilityDetector() {}

    /** Android's own low-RAM classification (ActivityManager.isLowRamDevice, API 21+). */
    public static boolean isAndroidLowRamDevice(Context context) {
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        return activityManager != null && activityManager.isLowRamDevice();
    }

    /**
     * True when the device should use conservative runtime defaults.
     * A "4 GB" phone usually reports less than 4096 MB of total RAM, so the
     * inclusive comparison catches it without any device-name check.
     */
    public static boolean isLowRamHardware(Context context) {
        return isAndroidLowRamDevice(context)
                || Tools.getTotalDeviceMemory(context) <= LOW_RAM_THRESHOLD_MB;
    }

    /**
     * Number of CPUs the JVM should see on this device.
     *
     * HotSpot derives its GC thread count, JIT compiler thread count and the
     * ForkJoinPool/commonPool parallelism from this value, and Minecraft itself
     * sizes its worker pools from Runtime.availableProcessors(). On 8x A53
     * hardware with <= 4 GB RAM, capping at 4 halves background thread churn
     * with no measurable loss of game throughput, while leaving the remaining
     * cores to the system, the Android UI thread and the GPU driver.
     */
    public static int getRecommendedJvmProcessorCount(Context context) {
        int cores = Runtime.getRuntime().availableProcessors();
        int totalRamMb = Tools.getTotalDeviceMemory(context);
        if (totalRamMb <= VERY_LOW_RAM_THRESHOLD_MB) return Math.min(2, cores);
        if (isLowRamHardware(context)) return Math.min(4, cores);
        return cores;
    }

    /**
     * Hard ceiling (in MB) for the Minecraft heap allocation slider.
     *
     * Derived only from device capabilities. The CustomSeekBarPreference stores
     * (rawSeekbarProgress + min), so callers must subtract the slider minimum
     * from this ceiling when configuring the raw seekbar max, otherwise the
     * user can end up selecting up to (ceiling + min) - e.g. ~2.9 GB on a
     * 3.6 GB device - which starves Android, the GPU driver and zRAM.
     */
    public static int getRamAllocationCeilingMb(Context context) {
        int deviceRam = Tools.getTotalDeviceMemory(context);
        int sliderMin = context.getResources().getInteger(R.integer.memory_seekbar_min);
        int ceiling;
        if (Architecture.is32BitsDevice() || deviceRam < 2048) {
            ceiling = Math.min(1024, deviceRam);
        } else {
            // To leave a minimum for the device to breathe
            ceiling = deviceRam
                    - (deviceRam < 3064 ? RAM_RESERVE_SMALL_DEVICE_MB : RAM_RESERVE_MB);
        }
        return Math.max(ceiling, sliderMin);
    }
}

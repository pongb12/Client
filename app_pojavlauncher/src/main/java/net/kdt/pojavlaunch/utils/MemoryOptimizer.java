package net.kdt.pojavlaunch.utils;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-java decision logic for the non-root RAM optimization feature.
 * Kept free of Android imports so it can be unit tested with plain JUnit.
 *
 * Everything this class suggests is applied at JVM launch time; nothing here
 * requires root. The device-facing parts (reading ActivityManager.MemoryInfo,
 * showing the risk dialogs) live in the preference fragment and in Tools.
 */
public final class MemoryOptimizer {
    private MemoryOptimizer() {}

    /** RAM reserved for Android, GPU drivers and LWJGL native allocations. */
    public static final int NATIVE_RESERVE_SMALL_MB = 650; // <= 3 GB total RAM devices
    public static final int NATIVE_RESERVE_MEDIUM_MB = 800; // <= 4 GB total RAM devices
    public static final int NATIVE_RESERVE_LARGE_MB = 1000; // > 4 GB total RAM devices

    /** Never propose a heap below this; fall back to the manual slider instead. */
    public static final int AUTO_XMX_FLOOR_MB = 512;
    /** Heap values are rounded down to this granularity so numbers stay readable. */
    public static final int AUTO_XMX_GRANULARITY_MB = 64;

    /** GC profiles selectable in Settings > Java > RAM optimization. */
    public static final String GC_PROFILE_DEFAULT = "default";
    public static final String GC_PROFILE_G1 = "g1";
    public static final String GC_PROFILE_SHENANDOAH = "shenandoah";

    /**
     * Compute the heap size (-Xmx, in MB) to hand to the JVM when automatic
     * allocation is enabled.
     *
     * SafeXmx = availMem - NativeReserve, where NativeReserve is 650..1000 MB
     * depending on the total device RAM, as proposed for low-end devices.
     *
     * @param availMemMb memory currently available on the device (availMem)
     * @param totalMemMb total device RAM
     * @param is32BitDevice true on 32-bit devices (address space is scarce)
     * @param addressSpaceLimitMb largest continuous address space, or <=0 when unknown
     * @param ceilingMb capability-based allocation ceiling (DeviceCapabilityDetector)
     * @param manualValueMb the manual slider value, used as a fallback whenever
     *                      the automatic computation cannot produce a safe result
     * @return the heap size in MB to actually use for this launch
     */
    public static int computeAutoXmxMb(long availMemMb, long totalMemMb, boolean is32BitDevice,
                                       long addressSpaceLimitMb, int ceilingMb, int manualValueMb) {
        long reserveMb = pickNativeReserveMb(totalMemMb);
        long candidate = availMemMb - reserveMb;

        // The available memory is read right before the JVM starts, so it is
        // already depleted by the launcher itself; still keep a floor so a
        // momentarily memory-starved device cannot push the heap to nonsense.
        if (candidate < AUTO_XMX_FLOOR_MB) return manualValueMb;

        if (is32BitDevice && addressSpaceLimitMb > 0) {
            candidate = Math.min(candidate, addressSpaceLimitMb);
        }
        if (ceilingMb > 0) {
            candidate = Math.min(candidate, ceilingMb);
        }

        candidate = roundDown(candidate, AUTO_XMX_GRANULARITY_MB);
        if (candidate < AUTO_XMX_FLOOR_MB) return manualValueMb;

        return (int) candidate;
    }

    /**
     * Pick how much RAM to leave for Android itself, its graphics drivers and
     * the native side of LWJGL: 650 MB on small devices, up to 1000 MB on
     * devices that can afford it.
     */
    public static long pickNativeReserveMb(long totalMemMb) {
        if (totalMemMb <= 0) return NATIVE_RESERVE_MEDIUM_MB;
        if (totalMemMb < 3072) return NATIVE_RESERVE_SMALL_MB;
        if (totalMemMb < 4096) return NATIVE_RESERVE_MEDIUM_MB;
        return NATIVE_RESERVE_LARGE_MB;
    }

    private static long roundDown(long value, long granularity) {
        if (granularity <= 1) return value;
        return (value / granularity) * granularity;
    }

    /**
     * Build the JVM GC flag list for the selected profile.
     *
     * - "g1": tune G1 for a small heap (faster, more regular collections that
     *   give memory back to Android quicker).
     * - "shenandoah": low-pause concurrent collector, only offered on runtimes
     *   that ship it (checked at launch; falls back to "g1" otherwise).
     * - "default": no extra flags, Mojang's own JVM args stay untouched.
     *
     * @param javaVersion major version of the runtime the game will use (8, 17, 21...)
     * @param profile one of the GC_PROFILE_* constants
     * @param shenandoahSupported result of the runtime support probe
     * @param is64BitRuntime whether the selected JVM is a 64-bit build
     * @return flags to append AFTER the Mojang-provided JVM args
     */
    public static List<String> buildGcFlags(int javaVersion, String profile,
                                            boolean shenandoahSupported, boolean is64BitRuntime) {
        List<String> flags = new ArrayList<>();
        if (profile == null) return flags;

        switch (profile) {
            case GC_PROFILE_SHENANDOAH:
                if (javaVersion < 12 || !shenandoahSupported) {
                    // Runtime probe failed: degrade to the tuned G1 profile
                    // instead of handing the JVM a flag it cannot start with.
                    addG1Flags(flags, javaVersion, is64BitRuntime);
                } else {
                    addShenandoahFlags(flags);
                }
                break;
            case GC_PROFILE_G1:
                addG1Flags(flags, javaVersion, is64BitRuntime);
                break;
            default:
                // Unknown profile values behave like "default": no injection.
                break;
        }
        return flags;
    }

    private static void addShenandoahFlags(List<String> flags) {
        flags.add("-XX:+UseShenandoahGC");
        // Compact heuristics: proactively uncommit memory back to Android,
        // which is what we want on low-RAM devices.
        flags.add("-XX:ShenandoahGCHeuristics=compact");
        flags.add("-XX:ShenandoahAllocationThreshold=20");
    }

    private static void addG1Flags(List<String> flags, int javaVersion, boolean is64BitRuntime) {
        flags.add("-XX:+UseG1GC");
        // Start the young generation at 20% so short-lived MC allocations are
        // collected before the heap bloats on devices where RAM is scarce.
        flags.add("-XX:G1NewSizePercent=20");
        // Reserve 20% of the heap as a safety buffer for mixed collections.
        flags.add("-XX:G1ReservePercent=20");
        // Cap pause times; small heaps can afford more frequent, shorter GCs.
        flags.add("-XX:MaxGCPauseMillis=50");
        // Compressed Oops halves the size of plain object references; the JVM
        // enables it by default under 32 GB heaps, we pin it explicitly so the
        // intent is visible and cannot be silently lost to ergonomics changes.
        // (64-bit runtimes only, the flag does not apply otherwise.)
        if (is64BitRuntime) flags.add("-XX:+UseCompressedOops");
        // Biased locking is a win on single-threaded startup for old runtimes,
        // and is simply ignored/removed in newer JDKs, so gate it by version:
        // deprecated in 15, removed in 18 where the flag would fail the JVM.
        if (javaVersion >= 8 && javaVersion <= 14) flags.add("-XX:-UseBiasedLocking");
    }

    /**
     * True when the given GC profile needs the runtime support probe to run.
     */
    public static boolean needsShenandoahProbe(String profile) {
        return GC_PROFILE_SHENANDOAH.equals(profile);
    }
}

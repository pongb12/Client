package net.kdt.pojavlaunch.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the non-root RAM optimization decision logic.
 * Run with: gradle :app_pojavlauncher:testReleaseUnitTest
 */
public class MemoryOptimizerTest {

    // ------------------------------------------------------------------ //
    // Auto heap sizing
    // ------------------------------------------------------------------ //

    @Test
    public void autoXmx_subtractsLargeReserve_onExact4GiB() {
        // Exactly 4 GiB total => large reserve 1000 MB: 2458-1000 = 1458 -> round 64 => 1408
        int xmx = MemoryOptimizer.computeAutoXmxMb(2458, 4096, false, -1, 3096, 1144);
        assertEquals(1408, xmx);
    }

    @Test
    public void autoXmx_subtractsMediumReserve_onTypical4GbPhone() {
        // A "4 GB" phone actually reports ~3.7 GB total: 3700 -> medium reserve 800
        // avail 2200 - 800 = 1400 -> round 64 => 1344
        int xmx = MemoryOptimizer.computeAutoXmxMb(2200, 3700, false, -1, 3096, 1144);
        assertEquals(1344, xmx);
    }

    @Test
    public void autoXmx_subtractsSmallReserve_on2GbDevice() {
        // 2 GB total => reserve 650 MB; 1024 avail - 650 = 374 < 512 floor
        // -> must fall back to the manual slider value.
        int xmx = MemoryOptimizer.computeAutoXmxMb(1024, 2048, false, -1, 1024, 656);
        assertEquals(656, xmx);
    }

    @Test
    public void autoXmx_hitsFloorAboveIt_on3GbDevice() {
        // 3 GB total => reserve 800; avail 1536 -> 736 -> round 64 => 704
        int xmx = MemoryOptimizer.computeAutoXmxMb(1536, 3072, false, -1, 2048, 936);
        assertEquals(704, xmx);
    }

    @Test
    public void autoXmx_usesLargeReserve_on8GbDevice() {
        // 8 GB total => reserve 1000 MB; 6000 avail - 1000 = 5000 -> 4992
        int xmx = MemoryOptimizer.computeAutoXmxMb(6000, 8192, false, -1, 6096, 2048);
        assertEquals(4992, xmx);
    }

    @Test
    public void autoXmx_respectsCapabilityCeiling() {
        // Ceiling (DeviceCapabilityDetector) must win over the raw computation.
        int xmx = MemoryOptimizer.computeAutoXmxMb(4000, 4096, false, -1, 1024, 1144);
        // candidate = 4000-800=3200, min(3200,1024)=1024 -> round 64 => 1024
        assertEquals(1024, xmx);
    }

    @Test
    public void autoXmx_respects32BitAddressSpace() {
        int xmx = MemoryOptimizer.computeAutoXmxMb(4000, 4096, true, 600, 3096, 696);
        // candidate = 3200, address space cap 600 -> round 64 => 576
        assertEquals(576, xmx);
    }

    @Test
    public void autoXmx_starvedDeviceFallsBackToManual() {
        // availMem below the reserve must never produce a tiny/negative heap.
        int xmx = MemoryOptimizer.computeAutoXmxMb(700, 4096, false, -1, 3096, 1144);
        assertEquals(1144, xmx);
    }

    @Test
    public void autoXmx_neverBelowFloor() {
        // Computation lands between 0 and the floor -> fallback.
        int xmx = MemoryOptimizer.computeAutoXmxMb(1000, 4096, false, -1, 3096, 936);
        // 1000-800=200 < 512 floor -> manual
        assertEquals(936, xmx);
    }

    @Test
    public void reserveScaling_matchesDeviceClasses() {
        assertEquals(650, MemoryOptimizer.pickNativeReserveMb(2048));
        assertEquals(650, MemoryOptimizer.pickNativeReserveMb(3071));
        assertEquals(800, MemoryOptimizer.pickNativeReserveMb(3072));
        assertEquals(800, MemoryOptimizer.pickNativeReserveMb(4095));
        assertEquals(1000, MemoryOptimizer.pickNativeReserveMb(4096));
        assertEquals(1000, MemoryOptimizer.pickNativeReserveMb(16384));
        // Unknown total: use a middle ground
        assertEquals(800, MemoryOptimizer.pickNativeReserveMb(0));
    }

    // ------------------------------------------------------------------ //
    // GC flag building
    // ------------------------------------------------------------------ //

    @Test
    public void gc_defaultProfileInjectsNothing() {
        assertTrue(MemoryOptimizer.buildGcFlags(8, MemoryOptimizer.GC_PROFILE_DEFAULT, false, true).isEmpty());
        assertTrue(MemoryOptimizer.buildGcFlags(21, null, true, true).isEmpty());
        assertTrue(MemoryOptimizer.buildGcFlags(8, "unknown-value", false, true).isEmpty());
    }

    @Test
    public void gc_g1Profile_onJava8() {
        List<String> flags = MemoryOptimizer.buildGcFlags(8, MemoryOptimizer.GC_PROFILE_G1, false, true);
        assertEquals(Arrays.asList(
                "-XX:+UseG1GC",
                "-XX:G1NewSizePercent=20",
                "-XX:G1ReservePercent=20",
                "-XX:MaxGCPauseMillis=50",
                "-XX:+UseCompressedOops",
                // Biased locking flag is valid up to JDK 14
                "-XX:-UseBiasedLocking"
        ), flags);
    }

    @Test
    public void gc_g1Profile_onJava21_noBiasedLocking() {
        List<String> flags = MemoryOptimizer.buildGcFlags(21, MemoryOptimizer.GC_PROFILE_G1, false, true);
        assertFalse(flags.contains("-XX:-UseBiasedLocking"));
        assertTrue(flags.contains("-XX:+UseG1GC"));
        assertTrue(flags.contains("-XX:+UseCompressedOops"));
    }

    @Test
    public void gc_g1Profile_on32BitRuntime_noCompressedOops() {
        List<String> flags = MemoryOptimizer.buildGcFlags(8, MemoryOptimizer.GC_PROFILE_G1, false, false);
        assertFalse(flags.contains("-XX:+UseCompressedOops"));
    }

    @Test
    public void gc_shenandoah_onSupportedRuntime() {
        List<String> flags = MemoryOptimizer.buildGcFlags(17, MemoryOptimizer.GC_PROFILE_SHENANDOAH, true, true);
        assertEquals(Arrays.asList(
                "-XX:+UseShenandoahGC",
                "-XX:ShenandoahGCHeuristics=compact",
                "-XX:ShenandoahAllocationThreshold=20"
        ), flags);
    }

    @Test
    public void gc_shenandoah_unsupportedRuntime_fallsBackToG1() {
        List<String> flags = MemoryOptimizer.buildGcFlags(17, MemoryOptimizer.GC_PROFILE_SHENANDOAH, false, true);
        assertFalse(flags.contains("-XX:+UseShenandoahGC"));
        assertTrue(flags.contains("-XX:+UseG1GC"));
    }

    @Test
    public void gc_shenandoah_onJava8_fallsBackToG1() {
        // Shenandoah never existed on Java 8; probe result is irrelevant.
        List<String> flags = MemoryOptimizer.buildGcFlags(8, MemoryOptimizer.GC_PROFILE_SHENANDOAH, true, true);
        assertFalse(flags.contains("-XX:+UseShenandoahGC"));
        assertTrue(flags.contains("-XX:+UseG1GC"));
    }

    // ------------------------------------------------------------------ //
    // Misc
    // ------------------------------------------------------------------ //

    @Test
    public void shenandoahProbeOnlyForShenandoahProfile() {
        assertTrue(MemoryOptimizer.needsShenandoahProbe(MemoryOptimizer.GC_PROFILE_SHENANDOAH));
        assertFalse(MemoryOptimizer.needsShenandoahProbe(MemoryOptimizer.GC_PROFILE_G1));
        assertFalse(MemoryOptimizer.needsShenandoahProbe(MemoryOptimizer.GC_PROFILE_DEFAULT));
    }
}

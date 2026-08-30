package net.kdt.pojavlaunch.utils;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.os.Process;

import net.kdt.pojavlaunch.Logger;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;

/**
 * Non-root memory hygiene for the launcher process. Everything here only
 * releases memory the launcher owns; no other process is ever touched.
 *
 * Note: the launcher holds no long-lived RAM image cache of its own (mod icons
 * are kept on disk and decoded straight into the views that need them), so the
 * effective tool we have at Play time is a garbage collection pass over the
 * view/bitmaps of the dying launcher process, plus lowering its scheduling
 * priority so the remnants of the :launcher process never compete with the
 * freshly created :game process.
 */
public final class MemoryReleaseManager {
    private MemoryReleaseManager() {}

    /**
     * Executed when the user presses Play, before the game process is created.
     * The launcher process kills itself once the game activity is up, so
     * releasing everything collectible here lets Android reclaim the launcher
     * footprint during the exact window where the :game process forks and
     * reserves its own memory - the moment where low-RAM devices are the most
     * stressed.
     */
    public static void releaseBeforeGameStart(Context context) {
        if (!LauncherPreferences.PREF_RAM_AGGRESSIVE_CLEANUP) return;
        try {
            releaseCaches();
            // Lower our own scheduling priority: whatever remains of this
            // process should never compete with the starting game process.
            Process.setThreadPriority(Process.myTid(), Process.THREAD_PRIORITY_BACKGROUND);
        } catch (Throwable t) {
            Logger.appendToLog("MemoryReleaseManager: cleanup failed: " + t);
        }
    }

    /**
     * Cache release pass, also used by onTrimMemory(UI_HIDDEN).
     */
    public static void releaseCaches() {
        // Best-effort GC. On ART this also runs reference/phantom cleanup for
        // direct ByteBuffers, releasing native memory back to the OS.
        System.gc();
        Logger.appendToLog("MemoryReleaseManager: launcher caches released");
    }

    /**
     * Called from LauncherActivity.onTrimMemory when the launcher UI went to
     * the background or the system is getting memory-starved.
     */
    public static void onTrimMemory(int level) {
        if (!LauncherPreferences.PREF_RAM_AGGRESSIVE_CLEANUP) return;
        if (level < ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) return;
        releaseCaches();
    }
}

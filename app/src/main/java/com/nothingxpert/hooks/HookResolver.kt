package com.nothingxpert.hooks

import de.robv.android.xposed.XposedHelpers

/**
 * Resolves hook target classes from a candidate list, newest candidate first.
 * Keeps hooks alive across OEM refactors/renames and lets HookHealth report
 * exactly which candidate chain survived on the running OS build.
 */
object HookResolver {

    fun resolveClass(loader: ClassLoader, vararg candidates: String): Class<*>? {
        for (name in candidates) {
            try {
                return XposedHelpers.findClass(name, loader)
            } catch (_: Throwable) {
            }
        }
        return null
    }

    fun firstExisting(loader: ClassLoader, vararg candidates: String): String? {
        return candidates.firstOrNull { resolveClass(loader, it) != null }
    }
}

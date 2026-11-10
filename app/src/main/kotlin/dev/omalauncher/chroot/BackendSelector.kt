package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import java.io.File

/**
 * Picks and wires the native chroot backend into the Runtime. The legacy
 * PRoot path stays fully in place until each consumer migrates; this only
 * decides whether the chroot backend EXISTS for the runtime to prepare.
 */
object BackendSelector {

    /**
     * The chroot backend is usable whenever a su candidate exists (as a path
     * string): the Android sandbox hides su from un-allowlisted uids, so an
     * app-side stat is not a reliable check. Whether su actually answers as
     * root for THIS install is verified by the escalated prepare, which
     * reports PrivilegeUnavailable with the exec detail instead of silently
     * falling back to the legacy flow.
     */
    fun canUseChroot(config: ChrootConfig): Boolean =
        config.suCandidates.any { it.isNotBlank() }

    /** Runtime config, rooted at the app's files dir (the Termux prefix remains the default). */
    fun runtimeConfig(filesDir: File): ChrootConfig =
        ChrootConfig.termuxDefaults(stateDir = filesDir.path)
}

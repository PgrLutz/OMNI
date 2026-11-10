package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.HostTools
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSelectorTest {

    private fun config(su: String?): ChrootConfig =
        ChrootConfig(
            rootfs = "/data/.../kali-rolling/rootfs",
            hostTools = HostTools.forPrefix("/x"),
            suCandidates = listOfNotNull(su),
            stateDir = "/s",
        )

    @Test
    fun `usable when a su candidate is configured`() {
        assertTrue(BackendSelector.canUseChroot(config("/system/bin/su")))
    }

    @Test
    fun `not usable without any su candidate`() {
        assertFalse(BackendSelector.canUseChroot(config(null)))
    }

    // The Android sandbox hides su from un-allowlisted uids, so an app-side
    // stat is not a reliable check: presence is decided on the path strings,
    // and whether su answers as root for THIS install is verified by the
    // escalated prepare (PrivilegeUnavailable with the exec detail otherwise).
}

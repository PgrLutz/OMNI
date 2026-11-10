package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.HostTools
import dev.omalauncher.backend.ProcessFactory
import dev.omalauncher.backend.SuEscalator
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionBridgeTest {

    private fun bridge(): SessionBridge =
        SessionBridge(
            File("/tmp/omni-test"),
            ProcessFactory(
                ChrootConfig(
                    rootfs = "/data/.../kali-rolling/rootfs",
                    hostTools = HostTools.forPrefix("/x"),
                    suCandidates = listOf("/system/bin/su"),
                    stateDir = "/tmp/omni-test",
                ),
                SuEscalator("/system/bin/su"),
            ),
            null, // context only used by start(); these tests check script()
        )

    @Test
    fun `bridge script consumes one command per line and appends output plus the exit marker`() {
        val s = bridge().script()
        assertTrue(s.contains("D=/run/user/0"))
        assertTrue(s.contains("bc.cmd"))
        assertTrue(s.contains("bc.out"))
        assertTrue(s.contains("sed -n '1p'"))
        assertTrue(s.contains("sed -i '1d'"))
        assertTrue(s.contains("__OMARCHY_EXIT__"))
        assertTrue(s.contains("__OMARCHY_QUIT__"))
        // No FIFO opens: regular files only, so no open-blocking or SELinux wall.
        assertTrue(!s.contains("mkfifo"))
    }
}

package dev.omalauncher.chroot

import dev.omalauncher.backend.ChrootConfig
import dev.omalauncher.backend.HostTools
import dev.omalauncher.backend.ProcessFactory
import dev.omalauncher.backend.SuEscalator
import org.junit.Assert.assertEquals
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
            null as android.content.Context,
        )

    @Test
    fun `bridge script creates the fifos in the bound runtime dir and holds both ends`() {
        val s = bridge().script()
        assertTrue(s.contains("D=/run/user/0"))
        assertTrue(s.contains("bc.cmd"))
        assertTrue(s.contains("bc.out"))
        assertTrue(s.contains("exec 3>"))
        assertTrue(s.contains("exec 4<"))
        assertTrue(s.contains("__OMARCHY_EXIT__"))
        assertTrue(s.contains("__OMARCHY_QUIT__"))
    }

    @Test
    fun `send strips the command echo and parses the exit marker`() {
        // The bridge's output for `echo hi`:
        //   $ echo hi
        //   hi
        //   __OMARCHY_EXIT__0
        // send() must drop the echo line and return stdout + the parsed code.
        val s = bridge().script()
        assertTrue(s.contains("printf '%s\\n' \"${'$'} $line\" >&3"))
        assertTrue(s.contains("__OMARCHY_EXIT__"))
    }
}

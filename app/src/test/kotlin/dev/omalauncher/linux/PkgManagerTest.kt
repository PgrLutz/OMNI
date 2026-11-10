package dev.omalauncher.linux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PkgManagerTest {

    @Test
    fun `apt install aliases arch package names to their debian equivalents`() {
        val script = aptInstallScript(listOf("iproute2", "iputils", "fd"))
        assertTrue(script.contains("'iproute2' 'iputils-ping' 'fd-find'"))
        assertFalse(script.contains("'iputils'"))
        assertFalse(script.contains("'fd'"))
    }

    @Test
    fun `apt install is used for the Kali guest`() {
        val script = aptInstallScript(listOf("git", "curl"))
        assertTrue(script.contains("apt-get update -qq"))
        assertTrue(script.contains("apt-get install -y --no-install-recommends"))
        // shellQuote wraps each package; the list ends with the last one, no trailing space.
        assertTrue(script.endsWith("'git' 'curl'"))
        assertFalse(script.contains("pacman"))
    }

    @Test
    fun `install script follows the active guest - apt on Kali, pacman on Arch`() {
        assertTrue(installScriptFor(arch = false, listOf("git")).contains("apt-get"))
        assertFalse(installScriptFor(arch = false, listOf("git")).contains("pacman"))
        assertTrue(installScriptFor(arch = true, listOf("git")).startsWith("pacman -Sy"))
        assertFalse(installScriptFor(arch = true, listOf("git")).contains("apt-get"))
    }

    @Test
    fun `update script follows the active guest - apt on Kali, pacman on Arch`() {
        assertTrue(updateScriptFor(arch = false).contains("apt-get upgrade -y"))
        assertFalse(updateScriptFor(arch = false).contains("pacman"))
        assertTrue(updateScriptFor(arch = true).startsWith("pacman -Syu"))
        assertFalse(updateScriptFor(arch = true).contains("apt-get"))
    }

    @Test
    fun `apt install quotes package names`() {
        assertTrue(aptInstallScript(listOf("a; rm -rf /")).contains("'a; rm -rf /'"))
    }

    @Test
    fun `apt update never touches the real databases`() {
        val script = aptCheckScript()
        assertTrue(script.contains("apt-get -s upgrade"))
        assertTrue(script.contains("#pacman-error"))
        assertTrue(script.contains("#pacman"))
        assertFalse(script.contains("apt-get upgrade -y"))
    }

    @Test
    fun `apt upgrade is a plain update+upgrade`() {
        val script = aptUpdateScript()
        assertTrue(script.contains("apt-get update -qq"))
        assertTrue(script.contains("apt-get upgrade -y"))
        assertFalse(script.contains("pacman"))
    }

    @Test
    fun `AUR has no APT mapping - failure is explicit`() {
        val msg = aurNotSupported("yay")
        assertTrue(msg.contains("no APT equivalent"))
        assertTrue(msg.contains("yay"))
    }
}

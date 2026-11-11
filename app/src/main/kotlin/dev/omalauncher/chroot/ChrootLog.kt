package dev.omalauncher.chroot

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * File-based log for the chroot backend, so on-device failures are readable
 * from Termux (logcat is wiped by force-stop and invisible to the user).
 * Writes to `<filesDir>/chroot.log`; every entry is one timestamped line.
 */
object ChrootLog {

    private var file: File? = null

    /** Point the log at the app's files dir; call once at startup. */
    fun start(filesDir: File) {
        file = File(filesDir, "chroot.log")
        log("=== chroot log started ===")
    }

    /** Absolute path of the log file, for the report. */
    fun path(): File? = file

    @Synchronized
    fun log(line: String) {
        val f = file ?: return
        runCatching {
            // Rotate at ~2 MB so the log stays readable and Termux cat is fast.
            if (f.length() > 2 * 1024 * 1024) {
                val rotated = File(f.parentFile, f.name + ".1")
                f.copyTo(rotated, overwrite = true)
                f.delete()
                log("=== chroot log rotated ===")
                return
            }
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            f.appendText("$stamp $line\n")
        }
    }
}

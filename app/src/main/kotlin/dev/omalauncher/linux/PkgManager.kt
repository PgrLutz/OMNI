package dev.omalauncher.linux

import kotlinx.coroutines.sync.Mutex

/**
 * Package operations for the ACTIVE guest: APT/dpkg on Kali, pacman on the
 * legacy Arch guest. AUR packages have no APT equivalent and fail explicitly
 * instead of pretending to succeed.
 */
class PkgManager(
    private val guest: LinuxGuest,
) {
    /** One install at a time, so apt/pacman never run concurrently. */
    val lock = Mutex()

    /** True when the active guest is the legacy Arch (PRoot) one. */
    val arch: Boolean get() = guest !is ChrootGuest

    /** The guest install script for [packages]: pacman on Arch, apt on Kali. */
    fun installScript(packages: List<String>): String = installScriptFor(arch, packages)

    /** The guest update script (packages and their upgrades), for the active guest. */
    fun updateScript(): String = updateScriptFor(arch)
}

/** The guest install script: pacman on the legacy Arch guest, apt/dpkg on Kali. */
internal fun installScriptFor(arch: Boolean, packages: List<String>): String =
    if (arch) pacmanInstall(packages) else aptInstallScript(packages)

/** The guest update script for the active guest. */
internal fun updateScriptFor(arch: Boolean): String =
    if (arch) pacmanUpdateScript() else aptUpdateScript()

/** AUR has no APT mapping: a clear failure, never a translated guess. */
internal fun aurNotSupported(name: String): String = "AUR package \"$name\" has no APT equivalent on Kali; install it manually in the guest."

/** Arch package names mapped to their Debian/Kali equivalents; passthrough otherwise. */
internal val APT_ALIASES: Map<String, String> =
    mapOf(
        "iputils" to "iputils-ping",
        "fd" to "fd-find",
        "ttf-dejavu" to "fonts-dejavu-core",
        "ttf-jetbrains-mono" to "fonts-jetbrains-mono",
        "ttf-nerd-fonts-symbols-mono" to "fonts-noto-core",
        "pulseaudio-alsa" to "pulseaudio-utils",
        "procps-ng" to "procps",
    )

/** `apt-get` one-shot install; no AUR, no guessing package names. */
internal fun aptInstallScript(packages: List<String>): String =
    "DEBIAN_FRONTEND=noninteractive apt-get update -qq && " +
        "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends " +
        packages.map { APT_ALIASES[it] ?: it }.joinToString(" ") { shellQuote(it) }

internal fun aptUpdateScript(): String =
    "DEBIAN_FRONTEND=noninteractive apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y"

/** Simulated upgrade, printed in the same shape the updates screen parses: packages, then the `#pacman` marker. */
internal fun aptCheckScript(): String =
    """
    if apt-get -s upgrade 2>&1 | grep -qE '^(E:|Err)'; then
      echo '#pacman-error'
    else
      apt-get -s upgrade 2>&1 | awk '/^Inst /{sub(/^Inst /, ""); print}'
      echo '#pacman'
    fi
    """.trimIndent()

internal fun pacmanUpdateScript(): String =
    "pacman -Syu --noconfirm --needed && $CLEAN_PACMAN_CACHE"
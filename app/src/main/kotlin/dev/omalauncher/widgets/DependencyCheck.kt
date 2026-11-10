package dev.omalauncher.widgets

import dev.omalauncher.linux.shellQuote

/**
 * Which declared pacman packages are missing, and which programs widgets run
 * that aren't installed, from one guest shell call: [script] prints a
 * `pkg <name>` or `bin <name>` line for each, and [parse] reads them back.
 */
internal object DependencyCheck {
    class Missing(
        val packages: Set<String>,
        val programs: Set<String>,
    )

    /** pacman -T prints the packages that aren't installed. Names come from widget files: quoted. */
    fun script(
        packages: Collection<String>,
        programs: Collection<String>,
        pacman: Boolean,
    ): String {
        // The check must query the DEBIAN names on Kali, exactly like the
        // install script aliases them (iputils -> iputils-ping, fd -> fd-find...).
        val pkgs = if (pacman) packages else packages.map { dev.omalauncher.linux.APT_ALIASES[it] ?: it }
        return (if (pkgs.isEmpty() || !pacman) "" else "pacman -T ${pkgs.joinToString(" ") { shellQuote(it) }} | sed 's/^/pkg /'; ") +
            (if (pkgs.isEmpty() || pacman) "" else "for p in ${pkgs.joinToString(" ") { shellQuote(it) }}; do dpkg-query -W -f='\${Status}' \"\$p\" 2>/dev/null | grep -q '^install ok installed' || echo \"pkg \$p\"; done; ") +
            (if (programs.isEmpty()) "" else "for c in ${programs.joinToString(" ") { shellQuote(it) }}; do command -v \"\$c\" >/dev/null || echo \"bin \$c\"; done; ") +
            "true"
    }

    fun parse(out: String): Missing {
        val lines = out.lines().map { it.trim() }
        return Missing(
            lines.filter { it.startsWith("pkg ") }.map { it.removePrefix("pkg ") }.toSet(),
            lines.filter { it.startsWith("bin ") }.map { it.removePrefix("bin ") }.toSet(),
        )
    }
}

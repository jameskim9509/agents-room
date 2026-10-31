package agentsroom.runtime.process;

import java.util.Locale;
import java.util.Set;

/**
 * Web browsers, by the name of their executable: what a sign-in's login page may open in (SCRUM-65). The
 * process kills spare these, and only these, once a sign-in began ({@code acp.ProcessTreeWatch}) or when
 * a sign-in released them from the runtime's job ({@link ProcessTreeKiller#killDescendants}).
 */
public final class WebBrowsers {

    /** Executable names (lower case, without {@code .exe}). */
    static final Set<String> NAMES = Set.of("chrome", "google-chrome", "google-chrome-stable", "chromium",
            "chromium-browser", "msedge", "microsoft-edge", "microsoft-edge-stable", "firefox", "brave", "opera",
            "vivaldi", "whale", "naver-whale-stable", "iexplore", "librewolf", "waterfox", "floorp", "zen",
            "yandex", "arc");

    private WebBrowsers() {
    }

    /** Whether the process runs a web browser's executable; {@code false} when its command cannot be read. */
    public static boolean isWebBrowser(ProcessHandle process) {
        return process.info().command().map(WebBrowsers::names).orElse(false);
    }

    /** Whether an executable path names a web browser. */
    public static boolean names(String command) {
        String normalized = command.replace('\\', '/');
        String name = normalized.substring(normalized.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return NAMES.contains(name.endsWith(".exe") ? name.substring(0, name.length() - ".exe".length()) : name);
    }
}

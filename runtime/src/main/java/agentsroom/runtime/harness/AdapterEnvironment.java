package agentsroom.runtime.harness;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The environment an ACP adapter process gets (ADR 0004 decision 5, D12). The adapter is spawned with
 * {@code inheritEnvironment(false)}, so it sees exactly this map: an allowlist of the runtime's own
 * variables, never everything. What is not on the list never reaches it, which keeps the shell's
 * {@code AGENTS_ROOM_*} variables (the WebSocket token above all), credential variables and the
 * adapter's own switches ({@code NO_BROWSER}, {@code APP_SERVER_LOGS}, {@code DEFAULT_AUTH_REQUEST},
 * {@code CODEX_PATH}, ...) out of it.
 *
 * <p>Names are compared without case: Windows treats {@code Path} and {@code PATH} as one variable, and
 * the lower-case proxy variables of POSIX tools are the same settings as the upper-case ones.
 *
 * @param variables the adapter's environment, with the names as the runtime's environment spells them
 * @param withheldSecrets the names of credential variables (ADR 0004 decision 5) that were set and are
 *     therefore not passed; only the names, for the diagnostic log
 */
public record AdapterEnvironment(Map<String, String> variables, Set<String> withheldSecrets) {

    /**
     * Variables passed through when set. What the adapter, Codex and the tools Codex runs (git, shells)
     * need to find the user's home, Codex login and temp folders, to start Windows programs, and to
     * reach the network through the user's proxy.
     */
    static final Set<String> PASSED = Set.of(
            // Windows system and program locations
            "SYSTEMROOT", "WINDIR", "SYSTEMDRIVE", "COMSPEC", "PATHEXT", "OS", "PROCESSOR_ARCHITECTURE",
            "PROCESSOR_IDENTIFIER", "NUMBER_OF_PROCESSORS", "COMPUTERNAME", "USERDOMAIN", "USERNAME",
            "PROGRAMDATA", "PROGRAMFILES", "PROGRAMFILES(X86)", "PROGRAMW6432", "COMMONPROGRAMFILES",
            "COMMONPROGRAMFILES(X86)", "COMMONPROGRAMW6432", "ALLUSERSPROFILE", "PUBLIC",
            // The user's folders: the adapter finds its own login through them (ADR 0004 decision 5)
            "USERPROFILE", "HOMEDRIVE", "HOMEPATH", "HOME", "APPDATA", "LOCALAPPDATA",
            "TEMP", "TMP", "TMPDIR",
            "XDG_CONFIG_HOME", "XDG_DATA_HOME", "XDG_CACHE_HOME", "XDG_STATE_HOME", "XDG_RUNTIME_DIR",
            // POSIX session
            "PATH", "SHELL", "USER", "LOGNAME", "TERM", "LANG", "LANGUAGE", "TZ",
            // Codex reads its home from here when the user set one; the runtime never sets it (D8)
            "CODEX_HOME",
            // Network through the user's proxy and CA bundle
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "NODE_EXTRA_CA_CERTS");

    /** Locale variables ({@code LC_ALL}, {@code LC_CTYPE}, ...). */
    private static final String LOCALE_PREFIX = "LC_";

    /** ADR 0004 decision 5: credential variables a user's environment may still hold. */
    static final List<String> CREDENTIAL_VARIABLES = List.of(
            "ANTHROPIC_API_KEY", "ANTHROPIC_AUTH_TOKEN", "CLAUDE_CODE_OAUTH_TOKEN", "OPENAI_API_KEY",
            "CODEX_API_KEY", "AWS_BEARER_TOKEN_BEDROCK", "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY",
            "AWS_SESSION_TOKEN");

    public AdapterEnvironment {
        variables = Map.copyOf(variables);
        withheldSecrets = Set.copyOf(withheldSecrets);
    }

    /** Whether a variable of this name is passed through when set (names compare without case). */
    public static boolean isPassed(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return !CREDENTIAL_VARIABLES.contains(upper) && (PASSED.contains(upper) || upper.startsWith(LOCALE_PREFIX));
    }

    /** The adapter environment built from the runtime process's own environment. */
    public static AdapterEnvironment ofRuntime() {
        return from(System.getenv());
    }

    /**
     * Picks the allowlisted variables of {@code source}. A name that appears twice in different case
     * (possible in a hand-built map, not in a Windows process environment) is passed once.
     */
    public static AdapterEnvironment from(Map<String, String> source) {
        Map<String, String> variables = new LinkedHashMap<>();
        Set<String> seen = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> withheld = new TreeSet<>();
        source.forEach((name, value) -> {
            if (name == null || value == null) {
                return;
            }
            String upper = name.toUpperCase(Locale.ROOT);
            if (CREDENTIAL_VARIABLES.contains(upper)) {
                withheld.add(upper);
                return;
            }
            if (isPassed(name) && seen.add(name)) {
                variables.put(name, value);
            }
        });
        return new AdapterEnvironment(variables, withheld);
    }

    /**
     * This environment plus variables the launch itself sets ({@link HarnessLaunch.Ready#environment()},
     * e.g. {@code ELECTRON_RUN_AS_NODE=1}). They are not on the allowlist: the runtime's own value of
     * such a variable never passes, only the launch's. An added name replaces a passed one whatever its
     * case.
     */
    public AdapterEnvironment with(Map<String, String> added) {
        if (added.isEmpty()) {
            return this;
        }
        Map<String, String> merged = new LinkedHashMap<>();
        variables.forEach((name, value) -> {
            if (added.keySet().stream().noneMatch(name::equalsIgnoreCase)) {
                merged.put(name, value);
            }
        });
        merged.putAll(added);
        return new AdapterEnvironment(merged, withheldSecrets);
    }

    /** Never prints the values. */
    @Override
    public String toString() {
        return "AdapterEnvironment[names=" + new TreeSet<>(variables.keySet()) + ", withheldSecrets="
                + withheldSecrets + "]";
    }
}

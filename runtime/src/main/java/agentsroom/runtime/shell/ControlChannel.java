package agentsroom.runtime.shell;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * The runtime's stdout. It belongs to the shell↔runtime control path and carries exactly one line,
 * the ready line. Everything else goes to stderr.
 */
public final class ControlChannel {

    private static volatile OutputStream stdout;

    private ControlChannel() {
    }

    /**
     * Takes the process stdout for the control path and points {@link System#out} at stderr, so that
     * a stray {@code System.out.println} in any library cannot corrupt the control path. Called once,
     * first thing in {@code main}.
     */
    public static synchronized void claimStdout() {
        if (stdout != null) {
            return;
        }
        stdout = new FileOutputStream(FileDescriptor.out);
        System.setOut(System.err);
    }

    /**
     * Writes one line as UTF-8 with a bare {@code \n} and flushes. Without {@link #claimStdout()}
     * (tests) it writes to {@link System#out}.
     */
    static synchronized void writeLine(String line) {
        OutputStream out = stdout != null ? stdout : System.out;
        try {
            out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write to stdout", e);
        }
    }
}

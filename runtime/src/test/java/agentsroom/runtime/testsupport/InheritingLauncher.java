package agentsroom.runtime.testsupport;

import java.io.IOException;

/**
 * Runs the command in its arguments with stdin, stdout and stderr inherited, and waits for it.
 *
 * <p>Lets a test kill the runtime's parent while the runtime's stdin stays open (the test, or a
 * process the test started, holds the pipe), so only the parent-exit watch can end the runtime.
 */
public final class InheritingLauncher {

    private InheritingLauncher() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Process child = new ProcessBuilder(args).inheritIO().start();
        System.exit(child.waitFor());
    }
}

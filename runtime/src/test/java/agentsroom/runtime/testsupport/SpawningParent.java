package agentsroom.runtime.testsupport;

import java.io.IOException;
import java.time.Duration;

/**
 * A process with a child: starts a {@link Sleeper}, prints {@value #READY} on stdout, then sleeps.
 * Stands in for an adapter process that started its own children.
 */
public final class SpawningParent {

    public static final String READY = "child started";

    private SpawningParent() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        String java = ProcessHandle.current().info().command().orElseThrow();
        new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), Sleeper.class.getName())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        System.out.println(READY);
        System.out.flush();
        Thread.sleep(Duration.ofMinutes(5));
    }
}

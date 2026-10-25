package agentsroom.runtime.testsupport;

import java.time.Duration;

/** A child process that does nothing for a long time. Started by {@link SpawningParent}. */
public final class Sleeper {

    private Sleeper() {
    }

    public static void main(String[] args) throws InterruptedException {
        Thread.sleep(Duration.ofMinutes(5));
    }
}

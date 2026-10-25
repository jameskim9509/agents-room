package agentsroom.runtime.testsupport;

import agentsroom.runtime.process.ChildProcessContainment;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Stands in for the runtime: enables {@link ChildProcessContainment}, then starts a child with a plain
 * {@link ProcessBuilder}, as a library would. The child has a child of its own. Prints
 * {@value #PREFIX}{@code <own pid> <child pid> <grandchild pid>} once both run, then sleeps until it is
 * killed. Needs native access and, on its class path, the containment class and slf4j-api.
 *
 * <p>The child is a {@link SpawningParent}, or with the argument {@value #NODE_CHILD} a Node process like
 * an adapter: libuv puts its own child into a job of its own that allows silent breakaway.
 */
public final class ContainedParent {

    public static final String PREFIX = "CONTAINED ";
    public static final String NODE_CHILD = "node";

    /** Like {@link SpawningParent}, in Node: starts the command in its arguments, then says so. */
    private static final String NODE_SPAWNING_PARENT = """
            const { spawn } = require('node:child_process');
            const [command, ...args] = process.argv.slice(1);
            spawn(command, args, { stdio: 'ignore' }).on('spawn', () => console.log('%s'));
            setInterval(() => {}, 60000);
            """.formatted(SpawningParent.READY);

    private ContainedParent() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (!ChildProcessContainment.enable()) {
            throw new IllegalStateException("child processes are not contained");
        }
        String java = ProcessHandle.current().info().command().orElseThrow();
        String classpath = System.getProperty("java.class.path");
        List<String> command = args.length > 0 && NODE_CHILD.equals(args[0])
                ? List.of("node", "-e", NODE_SPAWNING_PARENT, java, "-cp", classpath, Sleeper.class.getName())
                : List.of(java, "-cp", classpath, SpawningParent.class.getName());
        Process child = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        // The child prints its line once its own child runs.
        String ready = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))
                .readLine();
        if (!SpawningParent.READY.equals(ready)) {
            throw new IllegalStateException("The child said " + ready);
        }
        long grandchild = child.toHandle().children()
                .filter(process -> process.info().command().map(TestProcesses::isJava).orElse(false))
                .findFirst()
                .orElseThrow()
                .pid();
        System.out.println(PREFIX + ProcessHandle.current().pid() + " " + child.pid() + " " + grandchild);
        System.out.flush();
        Thread.sleep(Duration.ofMinutes(5));
    }
}

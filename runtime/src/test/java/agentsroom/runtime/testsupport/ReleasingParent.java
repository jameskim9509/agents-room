package agentsroom.runtime.testsupport;

import agentsroom.runtime.process.AdapterJob;
import agentsroom.runtime.process.ChildProcessContainment;
import agentsroom.runtime.process.ProcessTreeKiller;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stands in for the runtime while adapters sign in: enables {@link ChildProcessContainment}, then follows
 * commands on stdin, one per line, and answers each with one line on stdout. Workers stand in for
 * adapters: Node processes it starts with a plain {@link ProcessBuilder}, as the ACP SDK does, and
 * adopts at once. Needs native access, node on the PATH, and the process package and slf4j-api on its
 * class path.
 *
 * <pre>
 * start NAME                      starts and adopts a worker          -&gt; its pid
 * release NAME                    opens a release of its job (a sign-in) -&gt; ok
 * end NAME                        closes that release                 -&gt; ok
 * close NAME                      closes the worker's job, as a stop or a kill of its connection does
 *                                 (also mid sign-in)                  -&gt; ok
 * spawn NAME detached|attached    the worker starts a sleeping child  -&gt; the child's pid
 * direct                          the runtime itself starts a sleeping child -&gt; its pid
 * reap                            the runtime's cleanup on exit (ProcessTreeKiller.killDescendants) -&gt; ok
 * </pre>
 *
 * <p>It never exits by itself: the test kills it, as an app that is ended abruptly kills the runtime.
 */
public final class ReleasingParent {

    /** A Node worker: for each line, starts a sleeping child (detached when the line says so) and prints its pid. */
    private static final String WORKER = """
            const { spawn } = require('node:child_process');
            require('node:readline').createInterface({ input: process.stdin }).on('line', (line) => {
              const detached = line.trim() === 'detached';
              const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'],
                { detached, stdio: 'ignore', windowsHide: true });
              if (detached) child.unref();
              child.on('spawn', () => console.log(String(child.pid)));
            });
            setInterval(() => {}, 60000);
            """;

    private static final Duration REAP_WAIT = Duration.ofSeconds(10);

    private ReleasingParent() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        if (!ChildProcessContainment.enable()) {
            throw new IllegalStateException("child processes are not contained");
        }
        Map<String, Worker> workers = new HashMap<>();
        Map<String, AdapterJob.Release> releases = new HashMap<>();
        BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        while ((line = commands.readLine()) != null) {
            List<String> words = List.of(line.strip().split(" "));
            String answer = switch (words.getFirst()) {
                case "start" -> {
                    Worker worker = Worker.start();
                    workers.put(words.get(1), worker);
                    yield String.valueOf(worker.process.pid());
                }
                case "release" -> {
                    releases.put(words.get(1), workers.get(words.get(1)).job.releaseNewProcesses());
                    yield "ok";
                }
                case "end" -> {
                    releases.remove(words.get(1)).close();
                    yield "ok";
                }
                case "close" -> {
                    workers.get(words.get(1)).job.close();
                    yield "ok";
                }
                case "spawn" -> workers.get(words.get(1)).spawn(words.get(2));
                case "direct" -> String.valueOf(new ProcessBuilder("node", "-e", "setInterval(() => {}, 1000)")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                        .pid());
                case "reap" -> {
                    ProcessTreeKiller.killDescendants(ProcessHandle.current(), REAP_WAIT);
                    yield "ok";
                }
                default -> throw new IllegalArgumentException("unknown command " + line);
            };
            System.out.println(answer);
            System.out.flush();
        }
        Thread.sleep(Duration.ofMinutes(5));
    }

    private record Worker(Process process, AdapterJob job, BufferedWriter in, BufferedReader out) {

        static Worker start() throws IOException {
            Process process = new ProcessBuilder("node", "-e", WORKER)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            AdapterJob job = ChildProcessContainment.adopt(process.toHandle());
            return new Worker(process, job,
                    new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8)),
                    new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)));
        }

        String spawn(String how) throws IOException {
            in.write(how);
            in.newLine();
            in.flush();
            return out.readLine();
        }
    }
}

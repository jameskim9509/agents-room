package agentsroom.runtime.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A renderer on the JDK WebSocket client (no Origin header), queueing every frame it gets. */
public final class TestRenderer implements WebSocket.Listener {

    public static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final JsonMapper json = JsonMapper.builder().build();
    private final BlockingQueue<JsonNode> frames = new LinkedBlockingQueue<>();
    private final List<String> texts = new CopyOnWriteArrayList<>();
    private final StringBuilder partial = new StringBuilder();
    private final AtomicInteger received = new AtomicInteger();
    private WebSocket socket;

    public static TestRenderer connect(URI uri) throws Exception {
        TestRenderer renderer = new TestRenderer();
        renderer.socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(uri, renderer)
                .get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        return renderer;
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        partial.append(data);
        if (last) {
            texts.add(partial.toString());
            frames.add(json.readTree(partial.toString()));
            partial.setLength(0);
            received.incrementAndGet();
        }
        webSocket.request(1);
        return null;
    }

    /** The next frame; fails when none comes within {@link #TIMEOUT}. */
    public JsonNode next() throws InterruptedException {
        return next(TIMEOUT);
    }

    /** The next frame; fails when none comes within {@code wait}. */
    public JsonNode next(Duration wait) throws InterruptedException {
        JsonNode frame = poll(wait);
        assertThat(frame).as("a frame within %s", wait).isNotNull();
        return frame;
    }

    /** The next frame, or {@code null} when none comes within {@code wait}. */
    public JsonNode poll(Duration wait) throws InterruptedException {
        return frames.poll(wait.toMillis(), TimeUnit.MILLISECONDS);
    }

    public int received() {
        return received.get();
    }

    /** Every frame received so far, as sent. */
    public List<String> texts() {
        return List.copyOf(texts);
    }

    public void send(String text) {
        socket.sendText(text, true).join();
    }

    public void close() {
        socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
    }
}

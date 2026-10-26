package agentsroom.runtime.logging;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Runs everything written to it through {@link SecretMasker}, one line at a time, before passing it
 * on. Installed under {@link System#err} and {@link System#out}, it covers what bypasses logback: a
 * stray {@code System.out.println}, or a stack trace the JVM prints for an uncaught exception.
 *
 * <ul>
 *   <li>A line goes out when its {@code \n} arrives, so a secret is never split across two writes.
 *       {@link #flush()} does not send a partial line; a line longer than {@value #MAX_LINE_BYTES}
 *       bytes goes out in pieces of that size.
 *   <li>Bytes are read as ISO-8859-1, which maps every byte to one character and back. The masked
 *       names and values are ASCII, so masking works for any ASCII-compatible encoding (UTF-8 above
 *       all) and leaves every other byte as it was.
 * </ul>
 */
public final class MaskingOutputStream extends OutputStream {

    static final int MAX_LINE_BYTES = 64 * 1024;

    private final OutputStream out;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    public MaskingOutputStream(OutputStream out) {
        this.out = out;
    }

    /** A print stream onto the process's stderr that masks every line. */
    public static PrintStream stderr(Charset charset) {
        return new PrintStream(new MaskingOutputStream(new FileOutputStream(FileDescriptor.err)), true, charset);
    }

    @Override
    public synchronized void write(int b) throws IOException {
        line.write(b);
        if (b == '\n' || line.size() >= MAX_LINE_BYTES) {
            emitLine();
            out.flush();
        }
    }

    @Override
    public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        int end = offset + length;
        int start = offset;
        boolean emitted = false;
        for (int i = offset; i < end; i++) {
            if (bytes[i] == '\n' || line.size() + (i - start) + 1 >= MAX_LINE_BYTES) {
                line.write(bytes, start, i - start + 1);
                emitLine();
                emitted = true;
                start = i + 1;
            }
        }
        line.write(bytes, start, end - start);
        if (emitted) {
            out.flush();
        }
    }

    /** Flushes the stream below; a partial line stays buffered until its {@code \n}. */
    @Override
    public synchronized void flush() throws IOException {
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        if (line.size() > 0) {
            emitLine();
        }
        out.close();
    }

    private void emitLine() throws IOException {
        String masked = SecretMasker.mask(line.toString(StandardCharsets.ISO_8859_1));
        line.reset();
        out.write(masked.getBytes(StandardCharsets.ISO_8859_1));
    }
}

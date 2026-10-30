package agentsroom.runtime.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

/**
 * The script the shell's Electron loads first ({@code --require}) when it runs an adapter as Node (ADR
 * 0003 decision 12). It keeps {@code ELECTRON_RUN_AS_NODE} to the adapter's own Node processes: the
 * variable leaves the adapter's environment before its code runs, and only a child the adapter starts on
 * its own executable (codex-acp starting Codex, a Node fork) gets it back, together with the script. So
 * the commands an agent runs never inherit it, and an Electron app one of them starts runs as an app.
 *
 * <p>The runtime carries the script as a resource and writes it into its data directory
 * ({@code <AGENTS_ROOM_DATA_DIR>/runtime/}{@value #FILE_NAME}), so a development run and an installed app
 * load it from the same place, outside any archive.
 */
public final class ElectronAsNodePreload {

    static final String FILE_NAME = "electron-as-node.cjs";

    private static final byte[] CONTENT = readResource();

    private final Path directory;

    /** @param directory where the script is written: the runtime's data directory */
    public ElectronAsNodePreload(Path directory) {
        this.directory = directory;
    }

    /**
     * The script's file, written when it is missing or differs from the runtime's own copy (atomically,
     * so an adapter starting meanwhile never loads half of it). It is read on every launch, so a copy
     * changed on disk is never loaded.
     */
    synchronized Path file() throws IOException {
        Path target = directory.resolve(FILE_NAME);
        if (!Files.isRegularFile(target) || !Arrays.equals(Files.readAllBytes(target), CONTENT)) {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, FILE_NAME, ".tmp");
            try {
                Files.write(temporary, CONTENT);
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
        return target;
    }

    private static byte[] readResource() {
        try (InputStream in = ElectronAsNodePreload.class.getResourceAsStream(FILE_NAME)) {
            if (in == null) {
                throw new IllegalStateException(FILE_NAME + " is missing from the runtime's resources");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

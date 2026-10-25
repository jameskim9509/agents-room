package agentsroom.runtime.process;

import static java.lang.foreign.MemoryLayout.PathElement.groupElement;
import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;

/**
 * Puts this process into a new Windows job object with {@code JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE} that
 * only this process holds. Calls kernel32 through the FFM API, so the JVM needs native access
 * ({@code Enable-Native-Access: ALL-UNNAMED} in the boot jar manifest).
 */
final class WindowsJobObject {

    /** {@code JobObjectExtendedLimitInformation} of {@code JOBOBJECTINFOCLASS}. */
    private static final int EXTENDED_LIMIT_INFORMATION_CLASS = 9;
    private static final int JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE = 0x2000;
    private static final int FALSE = 0;

    /** {@code JOBOBJECT_BASIC_LIMIT_INFORMATION} on 64-bit Windows. */
    private static final StructLayout BASIC_LIMIT_INFORMATION = MemoryLayout.structLayout(
            JAVA_LONG.withName("PerProcessUserTimeLimit"),
            JAVA_LONG.withName("PerJobUserTimeLimit"),
            JAVA_INT.withName("LimitFlags"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("MinimumWorkingSetSize"),
            JAVA_LONG.withName("MaximumWorkingSetSize"),
            JAVA_INT.withName("ActiveProcessLimit"),
            MemoryLayout.paddingLayout(4),
            JAVA_LONG.withName("Affinity"),
            JAVA_INT.withName("PriorityClass"),
            JAVA_INT.withName("SchedulingClass"));

    /** {@code JOBOBJECT_EXTENDED_LIMIT_INFORMATION} on 64-bit Windows (144 bytes). */
    private static final StructLayout EXTENDED_LIMIT_INFORMATION = MemoryLayout.structLayout(
            BASIC_LIMIT_INFORMATION.withName("BasicLimitInformation"),
            MemoryLayout.sequenceLayout(6, JAVA_LONG).withName("IoInfo"),
            JAVA_LONG.withName("ProcessMemoryLimit"),
            JAVA_LONG.withName("JobMemoryLimit"),
            JAVA_LONG.withName("PeakProcessMemoryUsed"),
            JAVA_LONG.withName("PeakJobMemoryUsed"));

    private static final VarHandle LIMIT_FLAGS =
            EXTENDED_LIMIT_INFORMATION.varHandle(groupElement("BasicLimitInformation"), groupElement("LimitFlags"));

    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final VarHandle LAST_ERROR = CALL_STATE.varHandle(groupElement("GetLastError"));

    private WindowsJobObject() {
    }

    /**
     * Creates the job and puts this process into it. From then on every process this process starts is
     * created inside the job, and so are the processes those start: none of them can be missed, not even
     * one that a library starts or one whose parent exits early. The job handle is not inheritable and
     * never closed, so the job lives exactly as long as this process; when the process ends, however it
     * ends, Windows closes the handle and kills every process left in the job.
     *
     * <p>When this process already runs in a job (Electron's libuv job, for example), the new job nests
     * inside it (Windows 8 and later).
     *
     * @throws IOException when Windows refuses
     */
    @SuppressWarnings("restricted") // The layouts and descriptors above match the kernel32 declarations.
    static void containCurrentProcess() throws IOException {
        if (ADDRESS.byteSize() != Long.BYTES) {
            throw new IOException("Only 64-bit Windows is supported");
        }
        Linker linker = Linker.nativeLinker();
        SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
        Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
        MethodHandle createJobObject = linker.downcallHandle(kernel32.findOrThrow("CreateJobObjectW"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), lastError);
        MethodHandle setInformationJobObject = linker.downcallHandle(kernel32.findOrThrow("SetInformationJobObject"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), lastError);
        MethodHandle getCurrentProcess =
                linker.downcallHandle(kernel32.findOrThrow("GetCurrentProcess"), FunctionDescriptor.of(ADDRESS));
        MethodHandle assignProcessToJobObject = linker.downcallHandle(
                kernel32.findOrThrow("AssignProcessToJobObject"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), lastError);
        MethodHandle closeHandle =
                linker.downcallHandle(kernel32.findOrThrow("CloseHandle"), FunctionDescriptor.of(JAVA_INT, ADDRESS));

        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            // No security attributes: the handle is not inheritable, so no child keeps the job open.
            MemorySegment job = (MemorySegment) createJobObject.invokeExact(state, MemorySegment.NULL,
                    MemorySegment.NULL);
            if (job.address() == 0) {
                throw failure("CreateJobObjectW", state);
            }
            MemorySegment limits = arena.allocate(EXTENDED_LIMIT_INFORMATION);
            limits.fill((byte) 0);
            LIMIT_FLAGS.set(limits, 0L, JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE);
            int set = (int) setInformationJobObject.invokeExact(state, job, EXTENDED_LIMIT_INFORMATION_CLASS, limits,
                    (int) EXTENDED_LIMIT_INFORMATION.byteSize());
            if (set == FALSE) {
                IOException failure = failure("SetInformationJobObject", state);
                close(closeHandle, job);
                throw failure;
            }
            // A pseudo handle with full access; it needs no closing.
            MemorySegment currentProcess = (MemorySegment) getCurrentProcess.invokeExact();
            int assigned = (int) assignProcessToJobObject.invokeExact(state, job, currentProcess);
            if (assigned == FALSE) {
                IOException failure = failure("AssignProcessToJobObject", state);
                close(closeHandle, job);
                throw failure;
            }
            // The job handle stays open on purpose, for the rest of this process's life.
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not call kernel32", e);
        }
    }

    /** {@code CloseHandle}; a failure to close leaks one handle and is not worth more. */
    private static void close(MethodHandle closeHandle, MemorySegment handle) throws Throwable {
        int ignored = (int) closeHandle.invokeExact(handle);
    }

    private static IOException failure(String function, MemorySegment state) {
        return new IOException(function + " failed with Windows error " + (int) LAST_ERROR.get(state, 0L));
    }
}

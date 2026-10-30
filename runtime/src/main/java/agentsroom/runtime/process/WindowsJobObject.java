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
 * A Windows job object that only this process holds. Calls kernel32 through the FFM API, so the JVM
 * needs native access ({@code Enable-Native-Access: ALL-UNNAMED} in the boot jar manifest).
 *
 * <p>The handle is not inheritable, so no child keeps a job open. Jobs nest (Windows 8 and later): a
 * job whose first process already runs in a job becomes a child of that job, and a process in a nested
 * job is in all the jobs above it too.
 */
final class WindowsJobObject implements AutoCloseable {

    /** When the last handle closes, every process in the job (and in the jobs nested in it) is killed. */
    static final int KILL_ON_JOB_CLOSE = 0x2000;

    /**
     * A process a process of the job creates is not put into the job, nor into the jobs above it that
     * allow this too, up to the first one that does not.
     */
    static final int SILENT_BREAKAWAY_OK = 0x1000;

    /** {@code JobObjectExtendedLimitInformation} of {@code JOBOBJECTINFOCLASS}. */
    private static final int EXTENDED_LIMIT_INFORMATION_CLASS = 9;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;
    private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
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

    /** Guarded by this; the handle is closed once and never used afterwards. */
    private final MemorySegment handle;
    private boolean closed;

    private WindowsJobObject(MemorySegment handle) {
        this.handle = handle;
    }

    /**
     * Creates a job with these limit flags ({@link #KILL_ON_JOB_CLOSE}, {@link #SILENT_BREAKAWAY_OK}).
     *
     * @throws IOException when Windows refuses
     */
    static WindowsJobObject create(int limitFlags) throws IOException {
        if (ADDRESS.byteSize() != Long.BYTES) {
            throw new IOException("Only 64-bit Windows is supported");
        }
        Kernel32 kernel32;
        try {
            kernel32 = Kernel32.INSTANCE;
        } catch (ExceptionInInitializerError | NoClassDefFoundError e) {
            throw new IOException("Could not bind kernel32", e);
        }
        MemorySegment job;
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            // No security attributes: the handle is not inheritable.
            job = (MemorySegment) kernel32.createJobObject.invokeExact(state, MemorySegment.NULL, MemorySegment.NULL);
            if (job.address() == 0) {
                throw failure("CreateJobObjectW", state);
            }
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not call kernel32", e);
        }
        WindowsJobObject created = new WindowsJobObject(job);
        if (limitFlags != 0) {
            try {
                created.setLimits(limitFlags);
            } catch (IOException e) {
                created.close();
                throw e;
            }
        }
        return created;
    }

    /** Replaces the job's limit flags; the job has no other limits. */
    synchronized void setLimits(int limitFlags) throws IOException {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            MemorySegment limits = arena.allocate(EXTENDED_LIMIT_INFORMATION);
            limits.fill((byte) 0);
            LIMIT_FLAGS.set(limits, 0L, limitFlags);
            int set = (int) Kernel32.INSTANCE.setInformationJobObject.invokeExact(state, handle,
                    EXTENDED_LIMIT_INFORMATION_CLASS, limits, (int) EXTENDED_LIMIT_INFORMATION.byteSize());
            if (set == FALSE) {
                throw failure("SetInformationJobObject", state);
            }
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not call kernel32", e);
        }
    }

    /** Puts this process into the job. Every process it creates from then on starts inside the job. */
    synchronized void assignCurrentProcess() throws IOException {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            // A pseudo handle with full access; it needs no closing.
            MemorySegment current = (MemorySegment) Kernel32.INSTANCE.getCurrentProcess.invokeExact();
            assign(state, current);
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not call kernel32", e);
        }
    }

    /**
     * Puts another process into the job. Windows refuses when the process is in a job that this job is
     * not part of (a sibling's) and this job already has processes.
     */
    synchronized void assign(long pid) throws IOException {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            MemorySegment process = open(state, pid, PROCESS_SET_QUOTA | PROCESS_TERMINATE);
            try {
                assign(state, process);
            } finally {
                closeHandle(process);
            }
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not call kernel32", e);
        }
    }

    /** Whether the process is in this job, also through a job nested in it. */
    synchronized boolean contains(long pid) throws IOException {
        checkOpen();
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment state = arena.allocate(CALL_STATE);
            MemorySegment process = open(state, pid, PROCESS_QUERY_LIMITED_INFORMATION);
            try {
                MemorySegment result = arena.allocate(JAVA_INT);
                int checked = (int) Kernel32.INSTANCE.isProcessInJob.invokeExact(state, process, handle, result);
                if (checked == FALSE) {
                    throw failure("IsProcessInJob", state);
                }
                return result.get(JAVA_INT, 0) != FALSE;
            } finally {
                closeHandle(process);
            }
        } catch (IOException | RuntimeException | Error e) {
            throw e;
        } catch (Throwable e) {
            throw new IOException("Could not call kernel32", e);
        }
    }

    /**
     * Closes this process's handle. Without {@link #KILL_ON_JOB_CLOSE} the processes in the job keep
     * running, and the job lives on while they do.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeHandle(handle);
    }

    private void checkOpen() throws IOException {
        if (closed) {
            throw new IOException("The job object is closed");
        }
    }

    private void assign(MemorySegment state, MemorySegment process) throws Throwable {
        int assigned = (int) Kernel32.INSTANCE.assignProcessToJobObject.invokeExact(state, handle, process);
        if (assigned == FALSE) {
            throw failure("AssignProcessToJobObject", state);
        }
    }

    private static MemorySegment open(MemorySegment state, long pid, int access) throws Throwable {
        MemorySegment process = (MemorySegment) Kernel32.INSTANCE.openProcess.invokeExact(state, access, FALSE,
                (int) pid);
        if (process.address() == 0) {
            throw failure("OpenProcess(" + pid + ")", state);
        }
        return process;
    }

    /** {@code CloseHandle}; a failure to close leaks one handle and is not worth more. */
    private static void closeHandle(MemorySegment handle) {
        try {
            int ignored = (int) Kernel32.INSTANCE.closeHandle.invokeExact(handle);
        } catch (Throwable e) {
            // Nothing to do: the handle stays open until the process ends.
        }
    }

    private static IOException failure(String function, MemorySegment state) {
        return new IOException(function + " failed with Windows error " + (int) LAST_ERROR.get(state, 0L));
    }

    /** The kernel32 functions, looked up once, on the first {@link #create} (on Windows only). */
    private static final class Kernel32 {

        static final Kernel32 INSTANCE = new Kernel32();

        final MethodHandle createJobObject;
        final MethodHandle setInformationJobObject;
        final MethodHandle getCurrentProcess;
        final MethodHandle assignProcessToJobObject;
        final MethodHandle openProcess;
        final MethodHandle isProcessInJob;
        final MethodHandle closeHandle;

        @SuppressWarnings("restricted") // The descriptors match the kernel32 declarations.
        private Kernel32() {
            Linker linker = Linker.nativeLinker();
            SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
            Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
            createJobObject = linker.downcallHandle(kernel32.findOrThrow("CreateJobObjectW"),
                    FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS), lastError);
            setInformationJobObject = linker.downcallHandle(kernel32.findOrThrow("SetInformationJobObject"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT), lastError);
            getCurrentProcess =
                    linker.downcallHandle(kernel32.findOrThrow("GetCurrentProcess"), FunctionDescriptor.of(ADDRESS));
            assignProcessToJobObject = linker.downcallHandle(kernel32.findOrThrow("AssignProcessToJobObject"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), lastError);
            openProcess = linker.downcallHandle(kernel32.findOrThrow("OpenProcess"),
                    FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT), lastError);
            isProcessInJob = linker.downcallHandle(kernel32.findOrThrow("IsProcessInJob"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS), lastError);
            closeHandle = linker.downcallHandle(kernel32.findOrThrow("CloseHandle"),
                    FunctionDescriptor.of(JAVA_INT, ADDRESS));
        }
    }
}

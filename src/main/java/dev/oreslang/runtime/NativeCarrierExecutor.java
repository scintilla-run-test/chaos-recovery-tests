package dev.oreslang.runtime;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Bounded actor-carrier executor backed by native OS threads.
 *
 * <p>The OS carriers are created by liboresthread (pthread on Linux/macOS),
 * attached to the host VM exactly once with JNI AttachCurrentThreadAsDaemon,
 * and then repeatedly execute ordinary Runnable actor turns from this bounded
 * ready queue. Logical actors are therefore multiplexed over a much smaller
 * native carrier set; no actor owns a carrier and carrier identity is never
 * actor identity.</p>
 *
 * <p>All max carriers are created up front so watchdog compensation never has
 * to allocate an OS thread while the runtime is already under pressure. Only
 * {@code corePoolSize} carrier slots are enabled at a time. Extra carriers are
 * parked in native code and are enabled/disabled by the bounded compensation
 * policy in {@link ActorRuntime}.</p>
 */
public final class NativeCarrierExecutor {
    private static final String LIBRARY = "oresthread";
    private static final ThreadLocal<NativeCarrierExecutor> CURRENT_EXECUTOR = new ThreadLocal<>();
    private static final ThreadLocal<Integer> CURRENT_SLOT = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_NATIVE_THREAD_ID = new ThreadLocal<>();

    static {
        loadNativeLibrary();
    }

    private final ArrayBlockingQueue<Runnable> queue;
    private final int maximumPoolSize;
    private final AtomicInteger corePoolSize;
    private final AtomicInteger largestPoolSize;
    private final AtomicInteger activeCount = new AtomicInteger();
    private final AtomicLong completedTaskCount = new AtomicLong();
    private final AtomicReferenceArray<Thread> carrierThreads;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final long nativeHandle;

    public NativeCarrierExecutor(
            int corePoolSize,
            int maximumPoolSize,
            int queueCapacity,
            String threadPrefix) {
        if (corePoolSize <= 0) throw new IllegalArgumentException("corePoolSize must be > 0");
        if (maximumPoolSize < corePoolSize) {
            throw new IllegalArgumentException("maximumPoolSize must be >= corePoolSize");
        }
        if (queueCapacity <= 0) throw new IllegalArgumentException("queueCapacity must be > 0");
        Objects.requireNonNull(threadPrefix, "threadPrefix");

        this.queue = new ArrayBlockingQueue<>(queueCapacity, true);
        this.maximumPoolSize = maximumPoolSize;
        this.carrierThreads = new AtomicReferenceArray<>(maximumPoolSize);
        this.corePoolSize = new AtomicInteger(corePoolSize);
        this.largestPoolSize = new AtomicInteger(corePoolSize);

        long handle = nativeCreate(this, maximumPoolSize, corePoolSize, threadPrefix);
        if (handle == 0L) throw new IllegalStateException("native carrier pool returned a null handle");
        this.nativeHandle = handle;

        boolean started = false;
        try {
            nativeStart(handle);
            started = true;
        } finally {
            if (!started) nativeShutdown(handle);
        }
    }

    static void ensureNativeLibraryLoaded() {
        // Class initialization performs the one-time load.
    }

    private static void loadNativeLibrary() {
        String explicit = System.getProperty("ores.thread.native.path");
        if (explicit != null && !explicit.isBlank()) {
            System.load(Path.of(explicit).toAbsolutePath().normalize().toString());
            return;
        }

        Path local = Path.of("target", "native", System.mapLibraryName(LIBRARY))
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(local)) {
            System.load(local.toString());
            return;
        }

        System.loadLibrary(LIBRARY);
    }

    /**
     * Native pthread entry callback. One invocation lives for one physical
     * carrier's lifetime; many unrelated actor/root Runnables pass through it.
     */
    @SuppressWarnings("unused") // invoked from JNI
    private void nativeCarrierLoop(int slot) {
        CURRENT_EXECUTOR.set(this);
        CURRENT_SLOT.set(slot);
        CURRENT_NATIVE_THREAD_ID.set(nativeCurrentThreadId());
        carrierThreads.set(slot, Thread.currentThread());
        try {
            while (!shutdown.get()) {
                nativeAwaitEnabled(nativeHandle, slot);
                if (shutdown.get()) break;

                Runnable task;
                try {
                    // A finite wait lets a carrier observe a requested shrink
                    // after it becomes idle without an extra JVM helper thread.
                    task = queue.poll(50L, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    // Watchdog interruption belongs to the actor/root turn that
                    // was active on this carrier. Never let the interrupt bit
                    // poison the next unrelated actor turn.
                    Thread.interrupted();
                    if (shutdown.get()) break;
                    continue;
                }
                if (task == null) continue;

                activeCount.incrementAndGet();
                try {
                    task.run();
                } catch (Throwable failure) {
                    // ActorRuntime catches ordinary turn failures itself. This
                    // is the executor's final containment boundary, equivalent
                    // to a ThreadPoolExecutor worker's uncaught-exception path.
                    dispatchUncaught(failure);
                } finally {
                    activeCount.decrementAndGet();
                    completedTaskCount.incrementAndGet();
                    Thread.interrupted();
                }
            }
        } finally {
            carrierThreads.set(slot, null);
            CURRENT_NATIVE_THREAD_ID.remove();
            CURRENT_SLOT.remove();
            CURRENT_EXECUTOR.remove();
        }
    }

    private static void dispatchUncaught(Throwable failure) {
        Thread current = Thread.currentThread();
        Thread.UncaughtExceptionHandler handler = current.getUncaughtExceptionHandler();
        if (handler == null) handler = Thread.getDefaultUncaughtExceptionHandler();
        if (handler != null) {
            try {
                handler.uncaughtException(current, failure);
            } catch (Throwable ignored) {
                // Keep the native carrier alive; ActorRuntime's own watchdog
                // and fail-stop state are the authoritative control plane.
            }
        }
    }

    public void execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        if (shutdown.get() || !queue.offer(task)) {
            throw new RejectedExecutionException(
                    shutdown.get() ? "native carrier executor is shut down"
                            : "native carrier ready queue is full");
        }
    }

    public boolean remove(Runnable task) {
        return queue.remove(task);
    }

    public List<Runnable> shutdownNow() {
        if (CURRENT_EXECUTOR.get() == this) {
            throw new IllegalStateException(
                    "native carrier executor cannot be synchronously shut down from one of its own carriers");
        }
        if (!shutdown.compareAndSet(false, true)) return List.of();
        ArrayList<Runnable> abandoned = new ArrayList<>();
        queue.drainTo(abandoned);

        // Match ThreadPoolExecutor.shutdownNow(): signal any active carrier
        // before the native side joins pthreads. This is cooperative (Java
        // interruption), not unsafe pthread cancellation; uncooperative actor
        // containment remains the runtime watchdog's responsibility.
        for (int slot = 0; slot < carrierThreads.length(); slot++) {
            Thread carrier = carrierThreads.get(slot);
            if (carrier != null) carrier.interrupt();
        }

        nativeShutdown(nativeHandle);
        return List.copyOf(abandoned);
    }

    public int getActiveCount() { return activeCount.get(); }
    public int getQueueSize() { return queue.size(); }
    public long getCompletedTaskCount() { return completedTaskCount.get(); }
    public int getLargestPoolSize() { return largestPoolSize.get(); }
    public int getMaximumPoolSize() { return maximumPoolSize; }
    public int getCorePoolSize() { return corePoolSize.get(); }
    public boolean isShutdown() { return shutdown.get(); }

    public void setCorePoolSize(int value) {
        if (value <= 0 || value > maximumPoolSize) {
            throw new IllegalArgumentException(
                    "corePoolSize must be in [1," + maximumPoolSize + "]");
        }
        if (shutdown.get()) throw new RejectedExecutionException("native carrier executor is shut down");
        corePoolSize.set(value);
        largestPoolSize.accumulateAndGet(value, Math::max);
        nativeSetDesired(nativeHandle, value);
    }

    /**
     * All bounded compensation carriers are physically created at pool
     * construction and parked natively, so there is nothing to allocate here.
     */
    public boolean prestartCoreThread() {
        return false;
    }

    public static boolean isNativeCarrierThread() {
        return CURRENT_EXECUTOR.get() != null;
    }

    /** Diagnostic-only native pthread identity; never an actor identity. */
    public static long currentNativeThreadId() {
        Long id = CURRENT_NATIVE_THREAD_ID.get();
        return id == null ? 0L : id;
    }

    /** Diagnostic-only carrier slot within its pool. */
    public static int currentCarrierSlot() {
        Integer slot = CURRENT_SLOT.get();
        return slot == null ? -1 : slot;
    }

    private static native long nativeCreate(
            NativeCarrierExecutor executor,
            int maxThreads,
            int desiredThreads,
            String threadPrefix);
    private static native void nativeStart(long handle);
    private static native void nativeSetDesired(long handle, int desiredThreads);
    private static native void nativeAwaitEnabled(long handle, int slot);
    private static native void nativeShutdown(long handle);
    private static native long nativeCurrentThreadId();
}

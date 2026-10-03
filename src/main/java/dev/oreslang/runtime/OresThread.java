package dev.oreslang.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Oreslang's privileged java.lang.Thread-shaped native thread.
 *
 * <p>This is deliberately distinct from an actor. Each started OresThread owns
 * one native OS thread created by liboresthread; ordinary actors never allocate
 * one of these and instead remain multiplexed over NativeCarrierExecutor.
 * Guest actor turns are forbidden from starting or synchronously joining an
 * OresThread so THREAD_CREATE cannot bypass actor CPU/memory bulkheads.</p>
 */
public final class OresThread {
    public enum State { NEW, RUNNABLE, TERMINATED }

    private static final AtomicLong NEXT_ID = new AtomicLong(1L);
    private static final int MAX_PLATFORM_THREADS = Integer.getInteger(
            "ores.thread.max-platform-threads",
            Math.max(4, Runtime.getRuntime().availableProcessors() * 4));
    private static final Semaphore PLATFORM_THREAD_SLOTS =
            new Semaphore(MAX_PLATFORM_THREADS, true);
    private static final ThreadLocal<OresThread> CURRENT = new ThreadLocal<>();

    static {
        NativeCarrierExecutor.ensureNativeLibraryLoaded();
    }

    private final long threadId = NEXT_ID.getAndIncrement();
    private final Runnable target;
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final AtomicBoolean interrupted = new AtomicBoolean();
    private final AtomicBoolean slotHeld = new AtomicBoolean();
    private final CompletableFuture<Void> terminated = new CompletableFuture<>();
    private volatile String name;
    private volatile Thread attachedJvmThread;
    private volatile long nativeThreadId;
    private volatile Throwable failure;

    public OresThread(Runnable target) {
        this(target, null);
    }

    public OresThread(Runnable target, String name) {
        this.target = Objects.requireNonNull(target, "target");
        this.name = normalizeName(name == null ? "ores-thread-" + threadId : name);
    }

    public void start() {
        if (ActorRuntime.inActorExecution()) {
            throw new SecurityException(
                    "actors cannot create dedicated OS threads; use actor spawning/mailboxes instead");
        }
        if (!state.compareAndSet(State.NEW, State.RUNNABLE)) {
            throw new IllegalThreadStateException("OresThread may only be started once");
        }
        if (!PLATFORM_THREAD_SLOTS.tryAcquire()) {
            state.set(State.TERMINATED);
            RejectedExecutionException rejected = new RejectedExecutionException(
                    "OresThread platform-thread ceiling reached: " + MAX_PLATFORM_THREADS);
            failure = rejected;
            terminated.completeExceptionally(rejected);
            throw rejected;
        }
        slotHeld.set(true);
        try {
            nativeStart(this, name);
        } catch (Throwable startFailure) {
            if (slotHeld.compareAndSet(true, false)) PLATFORM_THREAD_SLOTS.release();
            failure = startFailure;
            state.set(State.TERMINATED);
            terminated.completeExceptionally(startFailure);
            throw startFailure;
        }
    }

    /**
     * Called once by the JNI-created pthread after it has attached to the VM.
     */
    @SuppressWarnings("unused") // JNI callback
    private void nativeRun() {
        CURRENT.set(this);
        attachedJvmThread = Thread.currentThread();
        nativeThreadId = nativeCurrentThreadId();
        try {
            target.run();
            terminated.complete(null);
        } catch (Throwable targetFailure) {
            failure = targetFailure;
            terminated.completeExceptionally(targetFailure);
        } finally {
            attachedJvmThread = null;
            state.set(State.TERMINATED);
            CURRENT.remove();
            if (slotHeld.compareAndSet(true, false)) PLATFORM_THREAD_SLOTS.release();
        }
    }

    /**
     * Java-compatible interrupt intent. This is safe because an OresThread has
     * a dedicated pthread; actor carriers never flow through this class.
     */
    public void interrupt() {
        interrupted.set(true);
        Thread carrier = attachedJvmThread;
        if (carrier != null) carrier.interrupt();
    }

    public boolean isInterrupted() {
        Thread carrier = attachedJvmThread;
        return interrupted.get() || (carrier != null && carrier.isInterrupted());
    }

    /**
     * Mirrors java.lang.Thread.interrupted(): inspect and clear the current
     * explicit OresThread's interrupt status.
     */
    public static boolean interrupted() {
        OresThread current = CURRENT.get();
        if (current == null) {
            throw new IllegalStateException(
                    "Thread.interrupted() cannot expose a scheduler-owned actor/root carrier");
        }
        boolean flagged = current.interrupted.getAndSet(false);
        return Thread.interrupted() || flagged;
    }

    public static OresThread currentThread() {
        OresThread current = CURRENT.get();
        if (current == null) {
            throw new IllegalStateException(
                    "Thread.currentThread() cannot expose a scheduler-owned actor/root carrier");
        }
        return current;
    }

    public void join() throws InterruptedException {
        rejectActorBlocking("Thread.join()");
        try {
            terminated.get();
        } catch (ExecutionException ignored) {
            // java.lang.Thread.join waits for termination; target failure is
            // observed separately through failure().
        }
    }

    public boolean join(long millis) throws InterruptedException {
        rejectActorBlocking("Thread.join(timeout)");
        if (millis < 0L) throw new IllegalArgumentException("timeout must be >= 0");
        if (millis == 0L) {
            join();
            return true;
        }
        try {
            terminated.get(millis, TimeUnit.MILLISECONDS);
            return true;
        } catch (ExecutionException ignored) {
            return true;
        } catch (TimeoutException timeout) {
            return false;
        }
    }

    public static void sleep(long millis) throws InterruptedException {
        OresThread current = CURRENT.get();
        if (current == null) {
            throw new IllegalStateException(
                    "Thread.sleep() may not block a scheduler-owned actor/root carrier");
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            // Match java.lang.Thread: throwing InterruptedException consumes
            // the interrupted status.
            current.interrupted.set(false);
            throw interrupted;
        }
    }

    public static void yield() {
        if (CURRENT.get() == null) {
            throw new IllegalStateException(
                    "Thread.yield() may not yield a scheduler-owned actor/root carrier; actor yielding is mailbox/continuation scheduling");
        }
        Thread.yield();
    }

    private static void rejectActorBlocking(String operation) {
        if (ActorRuntime.inActorExecution()) {
            throw new IllegalStateException(
                    operation + " would block an actor carrier; suspend/resume or mailbox composition is required");
        }
    }

    public boolean isAlive() { return state.get() == State.RUNNABLE; }
    public State getState() { return state.get(); }
    public long threadId() { return threadId; }
    public boolean isVirtual() { return false; }
    public String getName() { return name; }

    public void setName(String name) {
        this.name = normalizeName(name);
        Thread carrier = attachedJvmThread;
        if (carrier != null) carrier.setName(this.name);
    }

    public Optional<Throwable> failure() { return Optional.ofNullable(failure); }
    public long nativeThreadId() { return nativeThreadId; }

    public static boolean isCurrentOresThread() {
        return CURRENT.get() != null;
    }

    private static String normalizeName(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) throw new IllegalArgumentException("thread name cannot be blank");
        if (name.length() > 256) throw new IllegalArgumentException("thread name cannot exceed 256 characters");
        return name;
    }

    private static native void nativeStart(OresThread thread, String name);
    private static native long nativeCurrentThreadId();
}

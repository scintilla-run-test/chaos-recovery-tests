package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresThread;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresThreadTest {

    @Test
    void nativeThreadStartsJoinsAndHasDedicatedPthreadIdentity() throws Exception {
        AtomicLong nativeId = new AtomicLong();
        AtomicReference<OresThread> current = new AtomicReference<>();
        AtomicBoolean actorCarrier = new AtomicBoolean(true);

        OresThread thread = new OresThread(() -> {
            current.set(OresThread.currentThread());
            nativeId.set(OresThread.currentThread().nativeThreadId());
            actorCarrier.set(ActorRuntime.isActorCarrierThread());
            OresThread.yield();
        }, "ores-test-platform-thread");

        assertFalse(thread.isAlive());
        assertEquals(OresThread.State.NEW, thread.getState());
        thread.start();
        thread.join();

        assertSame(thread, current.get());
        assertTrue(nativeId.get() != 0L);
        assertFalse(actorCarrier.get(), "explicit Thread must not masquerade as an actor carrier");
        assertEquals(OresThread.State.TERMINATED, thread.getState());
        assertFalse(thread.isVirtual());
        assertEquals("ores-test-platform-thread", thread.getName());
    }

    @Test
    void interruptWakesSleepOnDedicatedThreadWithoutTouchingActorCarrier() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        AtomicBoolean observed = new AtomicBoolean();

        OresThread thread = new OresThread(() -> {
            sleeping.countDown();
            try {
                OresThread.sleep(5_000);
            } catch (InterruptedException expected) {
                observed.set(true);
            }
        });

        thread.start();
        assertTrue(sleeping.await(2, TimeUnit.SECONDS));
        thread.interrupt();
        thread.join();

        assertTrue(observed.get());
        assertTrue(thread.isInterrupted(),
                "logical interrupt intent remains observable after termination");
    }

    @Test
    void actorCannotEscapeDispatcherByStartingDedicatedThread() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 1, Long.MAX_VALUE, 64);
        IsolatePolicy policy = IsolatePolicy.developer();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(policy, config)) {
            var ref = runtime.<Integer>spawnSharedTrusted(() -> (message, context) -> {
                try {
                    new OresThread(() -> { }).start();
                } catch (Throwable expected) {
                    failure.set(expected);
                } finally {
                    done.countDown();
                }
            });
            ref.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }

        assertInstanceOf(SecurityException.class, failure.get());
    }

    @Test
    void typeCheckerAcceptsJavaShapedThreadSurface() {
        OresCompiler.parseAndTypeCheck("""
                pub routine main() => void {
                  val Thread worker = new Thread(nlex () -> {
                    return;
                  }, "worker");
                  worker.start();
                  worker.interrupt();
                  val bool alive = worker.isAlive();
                  val bool interrupted = worker.isInterrupted();
                  val int id = worker.threadId();
                  val string name = worker.getName();
                  worker.setName("renamed");
                  worker.join();
                  return;
                }
                """);
    }

    @Test
    void typeCheckerRejectsLexicallyCapturedThreadTarget() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main() => void {
                          val int captured = 7;
                          val Thread worker = new Thread(() -> {
                            stdio.stdout.write(captured);
                            return;
                          });
                          worker.start();
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("nlex"));
    }

    @Test
    void typeCheckerRejectsThreadCreationInsideActor() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        shared actor Worker {
                          pub fnc run() => void {
                            val Thread worker = new Thread(nlex () -> { return; });
                            worker.start();
                            return;
                          }
                        }
                        """));
        assertTrue(error.getMessage().contains("Thread")
                || error.getMessage().contains("thread"));
    }
}

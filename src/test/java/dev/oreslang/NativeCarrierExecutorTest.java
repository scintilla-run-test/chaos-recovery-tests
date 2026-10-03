package dev.oreslang;

import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.NativeCarrierExecutor;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

final class NativeCarrierExecutorTest {

    @Test
    void manyLogicalTasksMultiplexOntoOneNativeCarrier() throws Exception {
        NativeCarrierExecutor executor = new NativeCarrierExecutor(
                1, 2, 64, "ores-native-test-");
        try {
            CountDownLatch done = new CountDownLatch(32);
            Set<Long> nativeThreads = ConcurrentHashMap.newKeySet();
            Set<Integer> slots = ConcurrentHashMap.newKeySet();
            Set<String> javaThreadNames = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < 32; i++) {
                executor.execute(() -> {
                    assertTrue(NativeCarrierExecutor.isNativeCarrierThread());
                    nativeThreads.add(NativeCarrierExecutor.currentNativeThreadId());
                    slots.add(NativeCarrierExecutor.currentCarrierSlot());
                    javaThreadNames.add(Thread.currentThread().getName());
                    done.countDown();
                });
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(1, nativeThreads.size(),
                    "logical tasks should multiplex over the one enabled pthread carrier");
            assertEquals(Set.of(0), slots);
            assertEquals(1, javaThreadNames.size());
            assertTrue(javaThreadNames.iterator().next().startsWith("ores-native-test-"));
            assertEquals(32L, executor.getCompletedTaskCount());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void boundedCompensationActivatesAPrecreatedParkedCarrier() throws Exception {
        NativeCarrierExecutor executor = new NativeCarrierExecutor(
                1, 2, 16, "ores-native-compensation-");
        try {
            executor.setCorePoolSize(2);
            CountDownLatch bothEntered = new CountDownLatch(2);
            CountDownLatch release = new CountDownLatch(1);
            Set<Long> nativeThreads = ConcurrentHashMap.newKeySet();

            Runnable blocking = () -> {
                nativeThreads.add(NativeCarrierExecutor.currentNativeThreadId());
                bothEntered.countDown();
                try {
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    fail(interrupted);
                }
            };

            executor.execute(blocking);
            executor.execute(blocking);
            assertTrue(bothEntered.await(5, TimeUnit.SECONDS));
            assertEquals(2, nativeThreads.size(),
                    "compensation must activate the second pre-created pthread");
            assertEquals(2, executor.getLargestPoolSize());
            release.countDown();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void multipleActorsMultiplexOntoOnePrivateNativeCarrier() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 1, Long.MAX_VALUE, 64);
        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            int actors = 12;
            CountDownLatch done = new CountDownLatch(actors);
            Set<Long> nativeThreads = ConcurrentHashMap.newKeySet();
            Set<String> actorIds = ConcurrentHashMap.newKeySet();

            for (int i = 0; i < actors; i++) {
                var ref = runtime.<Integer>spawnPrivate(() -> (message, context) -> {
                    assertTrue(NativeCarrierExecutor.isNativeCarrierThread());
                    nativeThreads.add(NativeCarrierExecutor.currentNativeThreadId());
                    actorIds.add(context.self().id().toString());
                    done.countDown();
                });
                ref.send(i);
            }

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals(actors, actorIds.size());
            assertEquals(1, nativeThreads.size(),
                    "actor identity must not imply one pthread per actor");
        }
    }
}

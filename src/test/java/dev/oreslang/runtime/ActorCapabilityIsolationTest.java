package dev.oreslang.runtime;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class ActorCapabilityIsolationTest {

    @Test
    void privateActorStaticallyDeniesReadonlySharingEvenUnderDeveloperPolicy() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                actor PrivateWorker {
                  pub fnc attempt_share() => void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }

    @Test
    void privateActorCannotHideSharedMutexBehindTypeAlias() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                type SharedInt = SharedMutex<int>;

                actor PrivateWorker {
                  let SharedInt hidden;
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotHideSharedMutexInsideOrdinaryStoredClass() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class SharedBox
                  let SharedMutex<int> value;
                end

                actor PrivateWorker {
                  let SharedBox hidden;
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotLaunderSharedMemoryThroughOrdinaryHelperFunction() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                fnc build_shared() => void {
                  val shared = SharedMutex.new(1);
                  stdio.println(shared);
                  return;
                }

                actor PrivateWorker {
                  pub fnc run() => void {
                    build_shared();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotLaunderSharedMemoryThroughStaticClassHelper() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Helpers
                  pub static fnc build_shared() => void {
                    val shared = SharedMutex.new(1);
                    stdio.println(shared);
                    return;
                  }
                end

                actor PrivateWorker {
                  pub fnc run() => void {
                    Helpers.build_shared();
                    return;
                  }
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("SHARED_MEMORY"));
    }

    @Test
    void privateActorCannotCarryObjectWhoseInstanceMethodUsesSharedAuthority() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Helper
                  pub use_shared() => void {
                    val shared = process.share_readonly(arr[1, 2, 3]);
                    stdio.println(shared);
                    return;
                  }
                end

                actor PrivateWorker {
                  let Helper helper;
                }
                """));

        SecurityException error = assertThrows(
                SecurityException.class,
                () -> CapabilityChecker.check(program, IsolatePolicy.developer()));

        assertTrue(error.getMessage().contains("ACTOR_SHARE_READONLY"));
    }

    @Test
    void sharedActorMayUseTransitiveSharedStateWhenParentPolicyAllowsIt() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                type SharedInt = SharedMutex<int>;

                define class SharedBox
                  let SharedInt value;
                end

                shared actor SharedWorker {
                  let SharedBox state;
                }
                """));

        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void transitiveCapabilityScanHandlesSelfReferentialStoredTypes() {
        Ast.Program program = TypeChecker.check(Parser.parse("""
                define class Node
                  let Node next;

                  pub identity(Node other) => Node {
                    return other;
                  }
                end

                actor PrivateWorker {
                  let Node root;
                }
                """));

        assertDoesNotThrow(() ->
                CapabilityChecker.check(program, IsolatePolicy.developer()));
    }

    @Test
    void actorLocalRuntimePolicyCannotBeBypassedByParentContextCapability() throws Exception {
        IsolatePolicy developer = IsolatePolicy.developer();

        try (ActorRuntime runtime = new ActorRuntime(developer)) {
            AtomicReference<Throwable> privateSharedMemory = new AtomicReference<>();
            AtomicReference<Throwable> privateReadonlyShare = new AtomicReference<>();
            AtomicReference<Throwable> sharedFailure = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(2);

            var isolated = runtime.<String>spawnPrivateTrusted(factoryContext -> (message, context) -> {
                try {
                    OresContext.requireEffectiveCapability(
                            developer,
                            IsolatePolicy.Capability.SHARED_MEMORY,
                            "indirect-helper-shared-memory");
                } catch (Throwable failure) {
                    privateSharedMemory.set(failure);
                }

                try {
                    OresContext.requireEffectiveCapability(
                            developer,
                            IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                            "indirect-helper-readonly-share");
                } catch (Throwable failure) {
                    privateReadonlyShare.set(failure);
                }

                done.countDown();
                context.self().stop();
            });

            var shared = runtime.<String>spawnShared(factoryContext -> (message, context) -> {
                try {
                    OresContext.requireEffectiveCapability(
                            developer,
                            IsolatePolicy.Capability.SHARED_MEMORY,
                            "shared-actor-shared-memory");
                    OresContext.requireEffectiveCapability(
                            developer,
                            IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                            "shared-actor-readonly-share");
                } catch (Throwable failure) {
                    sharedFailure.set(failure);
                }

                done.countDown();
                context.self().stop();
            });

            isolated.send("check");
            shared.send("check");

            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertInstanceOf(SecurityException.class, privateSharedMemory.get());
            assertInstanceOf(SecurityException.class, privateReadonlyShare.get());
            assertNull(sharedFailure.get());

            assertTrue(isolated.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(shared.awaitTermination(2, TimeUnit.SECONDS));
            assertTrue(isolated.failure().isEmpty());
            assertTrue(shared.failure().isEmpty());
        }
    }
}

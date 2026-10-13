/*
 * Copyright 2026 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.util.internal;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CleanerJava24LinkerTest {
    private static final String LIBRARY_PROPERTY = "io.netty.allocator.ffmLibrary";
    private static final String FIXTURE_PROPERTY = "io.netty.test.ffmAllocatorLibrary";

    @Test
    void defaultLookupStillAllocatesWithoutUnsafe() throws Exception {
        run(true, null, "allocate", false);
    }

    @Test
    void missingLibraryDoesNotSilentlyFallBack() throws Exception {
        File missing = new File(System.getProperty("java.io.tmpdir"), "netty-missing-allocator-" + System.nanoTime());
        run(true, missing.getAbsolutePath(), "configurationFailure", false);
    }

    @Test
    void emptyLibraryDoesNotSelectDefaultLookup() throws Exception {
        run(true, "", "configurationFailure", false);
    }

    @Test
    void relativeLibraryIsRejected() throws Exception {
        run(true, "allocator.so", "configurationFailure", false);
    }

    @Test
    void unavailableNativeAccessPreservesCapabilityFallback() throws Exception {
        run(false, "", "unsupported", false);
    }

    @Test
    void settingDoesNotOverrideUnsafeCleanerSelection() throws Exception {
        run(true, "", "unsafe", false);
    }

    @Test
    void nativeImageInitializationRetainsDefaultLookupWithoutLogging() throws Exception {
        assumeTrue(PlatformDependent.javaVersion() >= 25);
        run(true, "", "nativeImage", false);
    }

    @Test
    void configuredLibraryOwnsAllocationAndCrossThreadRelease() throws Exception {
        String fixture = System.getProperty(FIXTURE_PROPERTY);
        assumeTrue(fixture != null, "Set " + FIXTURE_PROPERTY + " to the compiled native test fixture");
        run(true, fixture, "allocate", true);
    }

    @Test
    void configuredAllocationFailureRestoresMemoryCounter() throws Exception {
        String fixture = System.getProperty(FIXTURE_PROPERTY);
        assumeTrue(fixture != null, "Set " + FIXTURE_PROPERTY + " to the compiled native test fixture");
        run(true, fixture, "allocationFailure", true);
    }

    private static void run(boolean nativeAccess, String library, String mode, boolean fixture) throws Exception {
        assumeTrue(PlatformDependent.javaVersion() >= 24 || "unsupported".equals(mode));
        assumeTrue(PlatformDependent.bitMode() == 64);
        File output = File.createTempFile("netty-ffm-probe", ".log");
        File events = File.createTempFile("netty-ffm-allocator", ".log");
        Process process = null;
        try {
            List<String> command = new ArrayList<String>();
            command.add(new File(new File(System.getProperty("java.home"), "bin"), "java").getPath());
            if (PlatformDependent.javaVersion() >= 24) {
                command.add("--sun-misc-unsafe-memory-access=" + ("unsafe".equals(mode) ? "allow" : "deny"));
            }
            command.add("-Dio.netty.noUnsafe=" + !"unsafe".equals(mode));
            command.add("-Dio.netty.maxDirectMemory=8388608");
            if (nativeAccess) {
                command.add("--enable-native-access=ALL-UNNAMED");
            }
            if (library != null) {
                command.add("-D" + LIBRARY_PROPERTY + '=' + library);
            }
            if ("nativeImage".equals(mode)) {
                command.add("-Dorg.graalvm.nativeimage.imagecode=buildtime");
                command.add("-Xlog:class+init=info");
            }
            command.add("-cp");
            command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
            command.add(Probe.class.getName());
            command.add(mode);
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output);
            // Keep the capability test independent of launcher options inherited from the test environment.
            builder.environment().remove("JAVA_TOOL_OPTIONS");
            builder.environment().remove("JDK_JAVA_OPTIONS");
            builder.environment().remove("_JAVA_OPTIONS");
            if (fixture) {
                builder.environment().put("NETTY_FFM_TEST_EVENTS", events.getAbsolutePath());
            }
            process = builder.start();
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "FFM subprocess timed out");
            String log = new String(Files.readAllBytes(output.toPath()), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), log);
            assertTrue(log.contains("FFM_PROBE_OK"), log);
            if ("nativeImage".equals(mode)) {
                // This checks initialization dependencies on a JVM, not a complete native-image build.
                assertTrue(log.contains("Initializing 'io/netty/util/internal/CleanerJava24Linker'"), log);
                assertFalse(log.contains("Initializing 'io/netty/util/internal/SystemPropertyUtil'"), log);
                assertFalse(log.contains("Initializing 'io/netty/util/internal/logging/InternalLoggerFactory'"), log);
            }
            if (fixture) {
                List<String> actual = Files.readAllLines(events.toPath(), StandardCharsets.UTF_8);
                assertEquals("allocationFailure".equals(mode) ? Arrays.asList("OOM") :
                        Arrays.asList("malloc", "free", "malloc", "free", "malloc", "free"), actual);
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly().waitFor(10, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(output.toPath());
            Files.deleteIfExists(events.toPath());
        }
    }

    public static final class Probe {
        public static void main(String[] args) throws Exception {
            if ("configurationFailure".equals(args[0])) {
                ExceptionInInitializerError error = assertThrows(ExceptionInInitializerError.class,
                        () -> Class.forName(PlatformDependent.class.getName()));
                assertTrue(error.getCause() instanceof IllegalStateException, error.toString());
                assertTrue(error.getCause().getMessage().contains(LIBRARY_PROPERTY), error.toString());
            } else if ("unsafe".equals(args[0])) {
                assertTrue(PlatformDependent.hasUnsafe());
                CleanableDirectBuffer buffer = PlatformDependent.allocateDirect(16);
                buffer.buffer().putLong(0, 42);
                assertEquals(42, buffer.buffer().getLong(0));
                buffer.clean();
            } else if ("nativeImage".equals(args[0])) {
                assertTrue(CleanerJava24Linker.isSupported());
            } else if ("unsupported".equals(args[0])) {
                assertFalse(CleanerJava24Linker.isSupported());
                // A bad FFM-only setting must not break PlatformDependent when FFM cannot be used.
                assertFalse(PlatformDependent.hasUnsafe());
            } else {
                assertFalse(PlatformDependent.hasUnsafe());
                assertTrue(CleanerJava24Linker.isSupported());
                long initial = PlatformDependent.usedDirectMemory();
                assertTrue(initial >= 0);
                if ("allocationFailure".equals(args[0])) {
                    // The fixture deliberately refuses this size.
                    assertThrows(OutOfMemoryError.class, () -> PlatformDependent.allocateDirect(65535));
                } else {
                    for (int capacity : new int[] { 0, 1, 4096 }) {
                        final CleanableDirectBuffer allocation = PlatformDependent.allocateDirect(capacity);
                        ByteBuffer buffer = allocation.buffer();
                        assertTrue(buffer.isDirect());
                        assertEquals(capacity, buffer.capacity());
                        assertEquals(initial + capacity, PlatformDependent.usedDirectMemory());
                        for (int i = 0; i < capacity; i++) {
                            buffer.put(i, (byte) i);
                        }
                        for (int i = 0; i < capacity; i++) {
                            assertEquals((byte) i, buffer.get(i));
                        }
                        FutureTask<Void> release = new FutureTask<Void>(() -> {
                            allocation.clean();
                            return null;
                        });
                        new Thread(release, "ffm-cross-thread-release").start();
                        release.get(10, TimeUnit.SECONDS);
                        assertEquals(initial, PlatformDependent.usedDirectMemory());
                    }
                    assertThrows(OutOfMemoryError.class, () -> PlatformDependent.allocateDirect(8388609));
                }
                assertEquals(initial, PlatformDependent.usedDirectMemory());
            }
            System.out.println("FFM_PROBE_OK");
        }
    }
}

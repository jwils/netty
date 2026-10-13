# Draft: allow an explicit native allocator for the FFM cleaner

This branch is a review draft based on upstream `4.2` at `014ef8d7031f9f1015020095421a659ced36cc8a`. The API and compatibility work below need review before an upstream submission. This file is a working proposal and should be removed from the eventual upstream patch.

## Planned PR description

### Motivation

The Foreign Function and Memory (FFM) API's default symbol lookup can select libc even when an application preloads jemalloc. Netty can therefore change native allocators when it moves away from Unsafe. That can change native memory retention without a buffer reference-count leak.

### Modification

Add an opt-in `io.netty.allocator.ffmLibrary` property for an absolute library path. The FFM cleaner resolves `malloc` and `free` through that library and keeps it loaded for the process lifetime. Existing cleaner selection and the unset-property behavior remain unchanged.

Read the property only after FFM capability checks and outside native-image initialization. If the FFM cleaner is eligible, invalid explicit configuration fails initialization. If FFM is unavailable or another cleaner is selected, the setting is inactive. Native images retain the default lookup and ignore this JVM-only setting.

### Result

Applications can select a compatible allocator without enabling Unsafe. Fresh-JVM tests cover allocator routing, cross-thread release, memory accounting, allocation failure, invalid configuration, and cleaner selection. A small native fixture owns its allocations with `mmap`/`munmap`, making routing observable without relying on RSS or installing jemalloc.

## Proposed contract

- The property customizes the FFM cleaner; it does not force that cleaner or grant native access.
- Accept an absolute path only. Empty values, relative paths, missing libraries, and resolution failures are errors once FFM is eligible.
- Use the selected lookup for both symbols, with no per-symbol default fallback. Native dependency lookup may still resolve symbols from dependencies. The operator must supply a trusted, ABI-compatible `malloc`/`free` pair with matching ownership and cross-thread free support.
- Keep the library loaded with `Arena.global()`. It will not unload when a Netty class loader is discarded.
- Do not promise support for loading every allocator into a running process. Some jemalloc builds require startup preloading because of native TLS constraints.
- Native-image allocator customization is outside this draft. Its default path must remain compatible; a JVM simulation is not sufficient to establish actual native-image support.

Example for a JVM whose selected cleaner is FFM:

```text
--enable-native-access=ALL-UNNAMED
-Dio.netty.allocator.ffmLibrary=/absolute/path/to/libjemalloc.so.2
```

Use the appropriate named module instead of `ALL-UNNAMED` on the module path. Set the relocated property name when a shading tool also relocates Netty's property strings. Actual shaded-artifact coverage remains required.

## Reproduce the tests

Run from the repository root with JDK 25 selected through `JAVA_HOME`. These commands run only the new cleaner tests and the existing `PlatformDependentTest`, plus the required build modules. The native fixture is test-only: never preload it or use it in an application.

macOS fixture:

```sh
mkdir -p common/target/ffm-test
cc -std=c11 -Wall -Wextra -Werror -dynamiclib -fPIC \
  common/src/test/c/ffm_allocator.c \
  -o common/target/ffm-test/libnetty_ffm_allocator.dylib
mvn -B -ntp -pl common -am \
  -Dtest=CleanerJava24LinkerTest,PlatformDependentTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DargLine.printGC=-D_ \
  -Dio.netty.test.ffmAllocatorLibrary="$PWD/common/target/ffm-test/libnetty_ffm_allocator.dylib" test
```

Linux fixture (build recipe supplied; this branch has not been tested on Linux yet):

```sh
mkdir -p common/target/ffm-test
cc -std=c11 -D_DEFAULT_SOURCE -Wall -Wextra -Werror -shared -fPIC \
  common/src/test/c/ffm_allocator.c \
  -o common/target/ffm-test/libnetty_ffm_allocator.so
mvn -B -ntp -pl common -am \
  -Dtest=CleanerJava24LinkerTest,PlatformDependentTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DargLine.printGC=-D_ \
  -Dio.netty.test.ffmAllocatorLibrary="$PWD/common/target/ffm-test/libnetty_ffm_allocator.so" test
```

Without `io.netty.test.ffmAllocatorLibrary`, the two native-fixture tests are skipped. Use the same Maven command without that property on JDK 21 for the capability fallback and existing platform tests; FFM-only tests are skipped. Do not pass a production allocator as the test fixture: the tests require this fixture's event log and deliberate allocation-failure behavior.

## Validation recorded for this draft

- Before the implementation change, the initial seven tests on macOS AArch64/JDK 25.0.3 had five expected failures and two passes. The failures exposed ignored configuration, missing allocator routing, and the absent fixture-specific allocation failure.
- After the change, all nine cleaner tests passed with the native fixture. The existing platform tests had six passes and one capability-based skip. Checkstyle passed as part of the Maven lifecycle.
- JDK 21.0.11 also passed: the capability-fallback test and four existing platform tests ran; eleven tests were skipped by their capability/JDK assumptions.
- The native-image test simulates the image-code property and inspects class-initialization logging on a JVM. It proves the absence of the added logging dependency in that path, not successful compilation of a native image.

## Remaining review work

1. Agree on the property name and the inactive-setting/failure contract, especially the explicit native-image exclusion.
2. Coordinate with [PR 17791](https://github.com/netty/netty/pull/17791), which refactors the same cleaner to direct FFM calls and lazy linking. This draft intentionally uses the current upstream branch, without taking an unmerged PR as a dependency.
3. Agree on native fixture packaging and CI coverage. Run actual native-image build/run, real shading, module-path, old-JDK/Android, Linux x86-64/AArch64, and other supported OS checks before calling the change merge-ready.
4. Verify real jemalloc symbol ownership and run a sustained workload using this exact patch. The test fixture proves routing and ownership; it does not prove allocator performance or retention behavior under production load.
5. Decide whether provider diagnostics or stricter symbol-provider validation belong in the initial feature. One library lookup alone is not a proof of compatible ownership.

Related prior work: [Netty allocator discussion](https://github.com/netty/netty/discussions/17485), [experimental native FFM allocator](https://github.com/franz1981/netty-ffm-allocator), [Wave's allocator mismatch report](https://github.com/seqeralabs/wave/pull/1149), and [OpenJDK's default-lookup discussion](https://github.com/openjdk/jdk/pull/30811#issuecomment-4290996367).

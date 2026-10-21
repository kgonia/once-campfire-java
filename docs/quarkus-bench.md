# campfire-quarkus-bench

[campfire-quarkus-bench](https://github.com/franz1981/campfire-quarkus-bench), by
[franz1981](https://github.com/franz1981), measures one page, `GET /rooms/486777696`, in a Quarkus app built for
that page, the [Rust port](https://github.com/basecamp/once-campfire-rust) and the
[Kotlin port](https://github.com/mysticcoders/once-campfire-kotlin). We added this port as a fourth app and ran it
against Rust and Kotlin, on October 21, 2026. The Quarkus app wasn't run.

All the numbers here are from one laptop, so compare apps with each other, not with other machines. The
summary tables are in the [README](../README.md#on-campfire-quarkus-bench); this page is how they were made and
what they mean.

## What the benchmark does

It is not the verification harness that the [main results](benchmarks.md) come from:

| | verification harness (`bin/benchmark`) | campfire-quarkus-bench (`bench.sh`) |
|---|---|---|
| routes | eight, posting included | the room page only |
| session | a fresh login before each round | a fixed cookie |
| load | 16 clients, 8 s | Hyperfoil, closed loop: 16 and 1,024 connections, 15 s each |
| apps | Docker images | processes pinned to CPUs with `taskset`, on loopback |
| checking | every response, against a route contract | 100 requests after each start, compared with Rust's page; then status codes only |
| result | median of 3 rounds | mean, standard deviation and median of the reps |

The room page comes from each app's response cache almost every time, so this measures the request path around
the cache: the HTTP server, the cookie, the session, the cache lookup and the write. Nothing writes to the
database during the measurement.

## The run

**Harness:** campfire-quarkus-bench at `b59e6a4` with the changes in
[`tools/quarkus-bench/campfire-quarkus-bench.patch`](../tools/quarkus-bench/campfire-quarkus-bench.patch):

- `java` as an app, run from this checkout's `target/quarkus-app`;
- its smoke test compares pages as markup (below);
- `JDK_JAVA` and `JDK_DRIVER`, so the apps' JDKs can change without the load driver's;
- `bench.sh profile` accepts `--app java`;
- a 1 s connect timeout on the `/up` probes, which otherwise hang under WSL's mirrored networking.

**Apps:**

| app | commit | runtime |
|---|---|---|
| Rust | `6dae2fd`, `cargo build --release` | |
| Kotlin | `87436f3` | Temurin 25.0.4.1 |
| Java | `6244f8d`, `mvn package` | Temurin 25.0.4.1 |

Both JVMs got the harness's options, and nothing more: `-XX:+UseParallelGC -XX:+UseCompactObjectHeaders -Xmx512m
--enable-native-access=ALL-UNNAMED`. This port's own image uses ZGC instead.

**Machine:**

- A Lenovo ThinkPad T14 Gen 5 with an Intel Core Ultra 5 135U, running Ubuntu 26.04.1 (kernel 7.0.0-38).
- The CPU has 2 performance cores with 2 threads each (CPUs 0–3), 8 efficiency cores (4–11) and 2 low-power
  cores (12–13).
- Power profile `performance`, CPU governor `performance`, turbo on.
- Each app ran on CPUs 4–7: one cluster of four efficiency cores, which share a 2 MB L2 cache. They ran at
  3.2 GHz when sampled during a step.
- Hyperfoil ran on CPUs 0–3 and 8–11. CPUs 12–13 were left to the OS.

**Steps:**

- The `quick` matrix: 16 and 1,024 connections, closed loop.
- After each start: the smoke test, then 20 s at 16 connections.
- Each step: 5 s of Hyperfoil warmup, then 15 s measured.
- 5 reps, with the apps' order reversed every other rep.

The laptop has no C compiler and no libvips, and installing them needs root. So the Rust binary was built under
WSL from the same commit and copied over, with the 8 libvips libraries the laptop lacked, behind a 3-line wrapper
that sets `LD_LIBRARY_PATH`. It passed every smoke test.

## Same content, not the same bytes

The harness's smoke test compares every page with Rust's, byte for byte. This port renders the same elements
through jte with other spelling, so its pages are compared as markup instead. That's `tools/parity/markup.py`:

- one token per tag or text run;
- attributes decoded and sorted;
- whitespace collapsed.

On the seed both pages have 7,497 tokens, all equal (7,557 on the desktop-browser branch), and their bytes differ
by 0.3% (417,243 against 416,116). The check rejects a one-word change.

Two differences are by design, and count in this port's favor:

- **A smaller gzipped page.** It is 20,678 bytes against Rust's 24,229 and Kotlin's 24,227, 15% fewer. Rust and
  Kotlin compress each message fragment separately and splice the pieces together. This port splices only the
  fragments every page shares, and compresses the rest of the page as one stream.
- **Smaller headers.** There's no `X-Request-Id`, `X-Runtime` or `X-Cache`: a cached page's headers are 1,544
  bytes against 1,657.

## Results

Mean ± standard deviation over the 5 reps; JDK 25.

| conns | app | req/s | CV | CPU µs/req | mean latency ms | latency std ms | p99 ms | peak RSS MB |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 16 | Java | 45,069 ± 530 | 1.2% | 82.8 ± 1.0 | 0.35 | 0.18 | 0.89 ± 0.02 | 442 ± 12 |
| 16 | Kotlin | 43,632 ± 321 | 0.7% | 81.2 ± 0.9 | 0.37 | 0.13 | 0.81 ± 0.02 | 331 ± 8 |
| 16 | Rust | 37,196 ± 211 | 0.6% | 105 ± 0.5 | 0.43 | 0.15 | 0.83 ± 0.01 | 55 ± 1 |
| 1,024 | Kotlin | 57,163 ± 723 | 1.3% | 64.7 ± 0.8 | 17.9 | 2.3 | 24.1 ± 0.3 | 348 ± 6 |
| 1,024 | Java | 46,583 ± 579 | 1.2% | 82.1 ± 1.2 | 22.0 | 17.8 | 46.2 ± 0.6 | 509 ± 11 |
| 1,024 | Rust | 29,122 ± 329 | 1.1% | 135 ± 1.1 | 35.1 | 6.3 | 52.7 ± 0.4 | 108 ± 1 |

- **CV** is the standard deviation between reps as a share of the mean.
- **Latency std** is the standard deviation over all the responses of a step, averaged over the reps. Between
  reps it varied by 0.6–2.1%.
- **Throughput within a step:** second by second, it varied by 0.3–1.6% (the standard deviation of the 15
  one-second intervals, as a share of their mean).

**What the numbers say:**

- **At 16 connections, Java is ahead of Kotlin by 3%.** Its slowest rep (44,316) beat Kotlin's fastest (43,985),
  so the gap is small but real.
- **At 1,024 connections, Kotlin is ahead by 23%.** Its CPU per request falls from 81 to 65 µs as connections
  go up, while this port's stays at 82 µs.
- **This port serves connections unevenly at 1,024.** Its latency std is 17.8 ms against Kotlin's 2.3 ms, and
  its median (33 ms) is above its mean (22 ms): a share of requests is answered fast, most wait about 33 ms.
- **Rust at 1,024 is limited by its listen backlog of 128.** It overflowed in 4 of the 5 reps (24–114 times).
  The Kotlin article's runs patched it; this harness doesn't.
- **Memory:** Rust peaks at 55–108 MB, the JVMs at 331–509 MB.
- **Nothing was saturated.** Each app used 2.9–3.3 of its 4 CPUs, and Hyperfoil 0.8–1.2 of its 8. No step
  had an error, a missing connection or an invalid response.

On a desktop machine under WSL2, the same matrix varied by up to 70% between reps: Windows decides which
physical cores run WSL's CPUs, and the clock can't be fixed there. Use a Linux machine for numbers.

## Where the CPU goes

From `bench.sh profile --app java`: async-profiler, CPU samples over 10 s of the measured load, on the same
CPUs. The categories don't overlap: each sample is counted in the first one its stack matches.

| | 16 conns | 1,024 conns |
|---|---:|---:|
| Quarkus REST dispatch and filters | 30.6% | 29.1% |
| Netty and Vert.x I/O, and the kernel | 27.2% | 30.1% |
| SQLite | 19.7% | 17.0% |
| cookie and session | 8.9% | 10.4% |
| response cache lookup and key | 7.1% | 6.6% |
| other | 5.9% | 4.8% |
| GC and JIT | 0.4% | 2.1% |

## JDBC

campfire-quarkus-bench's README explains why its Quarkus app calls SQLite through FFM rather than JDBC:

- sqlite-jdbc crosses JNI for every value and every step, under a lock per connection;
- it has no statement cache;
- its native calls pin virtual threads;
- the Quarkiverse SQLite extension has no Quarkus 4 release.

That README also says: "A Quarkus-idiomatic way back to JDBC that keeps the speed is part of the challenge."

This port uses sqlite-jdbc 3.53.4.0 through two Agroal datasources. JDBC's cost is there, but it doesn't set the
ceiling:

- **The cost per request.** SQLite takes 17–20% of the CPU, about 14–16 µs of the 82 µs a request costs. Most
  of it (12.6–14.5% of the CPU) is sqlite-jdbc's native calls, SQLite's own C code included.
- **Few calls.** A cached room page makes two:
  - `PRAGMA data_version`, for the cache's version (6–7% of the CPU);
  - the room access check, `RoomService.findForUser` (11–12%).

  Sessions, users and the account are kept in memory until the next commit; the page comes from the response
  cache.
- **No single SQLite thread.** In the bench's Quarkus app, one thread does all SQLite work. With JDBC, its
  9.1 µs per request capped the app at about 100k req/s. Here, each request runs its own call on its virtual
  thread, against a pool of up to 16 readers. The per-call cost adds CPU, and nothing queues behind it.
- **Little lock contention.** Threads spend 1.1–1.4% of the CPU on SQLite's native locks, and the port's own
  `observerLock` has no waits in the lock profile.
- **Pinning.** A virtual thread is pinned only for the length of one short native call.
- **Quarkus 3.** The port is on Quarkus 3.40.1 with plain Agroal (`db-kind=other`), so it never needed the
  extension.

What isn't measured: pages that miss the cache. Under frequent commits every miss runs the full query path, and
there JDBC's per-call cost multiplies.

The two calls could also be cached by the data version, as sessions are. Then a cache hit would make no SQLite
call besides the version read, and that one could be shared among concurrent requests.

## JDK 28 and Valhalla

The same run twice more, with only the JVM apps on JDK 28 early access (`28-ea+20`):

- the first time as is;
- the second time with `--enable-preview`, which turns on Valhalla's value objects (JEP 401).

Rust and Hyperfoil didn't change. Hyperfoil stayed on JDK 25.

| conns | app | JDK 25 | JDK 28 | JDK 28 `--enable-preview` |
|---:|---|---:|---:|---:|
| 16 | Java | 45,069 ± 530 | 47,146 ± 367 (**+4.6%**) | 45,774 ± 1,288 (+1.6%) |
| 16 | Kotlin | 43,632 ± 321 | 44,095 ± 437 (+1.1%) | 44,348 ± 233 (**+1.6%**) |
| 16 | Rust | 37,196 ± 211 | 37,398 ± 145 (+0.5%) | 37,361 ± 238 (+0.4%) |
| 1,024 | Kotlin | 57,163 ± 723 | 57,466 ± 452 (+0.5%) | 57,598 ± 376 (+0.8%) |
| 1,024 | Java | 46,583 ± 579 | 48,232 ± 516 (**+3.5%**) | 47,361 ± 841 (+1.7%) |
| 1,024 | Rust | 29,122 ± 329 | 29,236 ± 179 (+0.4%) | 29,339 ± 191 (+0.7%) |

Changes in bold pass Welch's t-test at 95%; the others are within the reps' spread. CPU per request, p99, latency
std and memory for each JDK are in the [README's JDK table](../README.md#on-campfire-quarkus-bench).

**JDK 28:**

- **Java's throughput:** +3.5–4.6%, with 3.3–4.4% less CPU per request.
- **Java's tail latency:** p99 is 6.7–7.6% lower.
- **Kotlin:** no faster, but p99 at 16 connections is 9.5% lower.
- **Memory** (MB, mean of the reps):

  | conns | app | measure | JDK 25 | JDK 28 | JDK 28 `--enable-preview` |
  |---:|---|---|---:|---:|---:|
  | 16 | Kotlin | peak since start | 331 ± 8 | 270 ± 5 | 275 ± 5 |
  | 16 | Kotlin | RSS after the step | 245 ± 7 | 262 ± 8 | 270 ± 7 |
  | 1,024 | Kotlin | RSS | 348 ± 6 | 342 ± 8 | 349 ± 7 |
  | 16 | Java | peak since start | 442 ± 12 | 399 ± 16 | 417 ± 36 |
  | 16 | Java | RSS after the step | 412 ± 20 | 397 ± 17 | 414 ± 33 |
  | 1,024 | Java | RSS | 509 ± 11 | 489 ± 21 | 508 ± 30 |

  - **Kotlin:** its 18% lower peak at 16 connections is a spike during startup and warmup that JDK 28 doesn't
    have. After the step it holds 7% more, a significant difference, and at 1,024 connections the JDKs are level.
  - **Java:** 4–10% less on JDK 28, significant only for the 16-connection peak.
  - **Java with preview:** back to JDK 25's levels. One rep (470 MB after the 16-connection step, 561 MB at
    1,024) makes its spread twice as wide.
  - **What RSS measures:** with ParallelGC and a 512 MB cap, it's mostly how far the GC grew the heap, not
    live data. The runs don't record the live heap; a GC log (`APP_JVM_OPTS=-Xlog:gc`) would.
- **Rust:** within 0.5%, so the machine itself didn't drift between the runs.

**`--enable-preview`:**

- **It adds nothing.** Against plain JDK 28, Java's throughput is 1.8–2.9% lower, which is within noise, and
  its CPU per request is 3.1% higher at 16 connections (significant). Kotlin is within noise of plain JDK 28;
  its +1.6% at 16 connections passes the test only against JDK 25.
- **What the flag turns on in this build:**
  - 31 JDK classes become value classes: the boxes (`Integer`, `Long`, …), `java.time`'s dates, times and
    durations, `Optional` and its primitive variants, and `Record`. `Record` becoming one lets a record be
    declared a value record; ordinary records stay ordinary.
  - The JVM's flattening and scalarization of value objects (`UseFieldFlattening`, `UseArrayFlattening`,
    `ValueTypePassFieldsAsArgs`, …).
  - Checks that every `==` on references and every `Object[]` access must now make (`UseAcmpFastPath`,
    `UseArrayLoadStoreProfile`).
- **Why there's no gain:** neither app declares a value class, and an allocation profile of this port's room
  page explains the rest:
  - the JDK's new value classes are about 1% of what it allocates;
  - its own types are 1.6%, mostly request-scoped CDI beans, which need identity;
  - everything else is byte arrays, strings, header maps and Vert.x objects, none of which can be a value class.

  So with nothing to flatten, what's left is the cost of the new checks. A fork that declares value classes
  wouldn't change that for this page either.

## Reproducing

The commands below run on Linux, with the harness checked out next to this repository, the patch applied, and
SDKMAN, jbang, cargo and libvips (`libvips-dev`) installed. `JAVA_PORT` points `bench.sh` elsewhere.

```sh
cd campfire-quarkus-bench
git apply ../once-campfire-java/tools/quarkus-bench/campfire-quarkus-bench.patch
export JDK_KOTLIN=25.0.4.1+1-tem JDK_JAVA=25.0.4.1+1-tem JDK_DRIVER=25.0.4.1+1-tem
./bench.sh setup     # builds Rust, Kotlin, the Quarkus app (on JDK_QUARKUS, default 27) and this port
./bench.sh run --matrix quick --apps rust,kotlin,java --reps 5 --app-cpus 4-7 --driver-cpus 0-3,8-11
# JDK 28, then Valhalla: the apps' JDK only
JDK_KOTLIN=28.0.0.0+ea.20-open JDK_JAVA=28.0.0.0+ea.20-open ./bench.sh run ...
JDK_KOTLIN=28.0.0.0+ea.20-open JDK_JAVA=28.0.0.0+ea.20-open APP_JVM_OPTS=--enable-preview ./bench.sh run ...
./bench.sh profile --app java --event cpu --format collapsed --conns 16 --matrix quick --app-cpus 4-7 --driver-cpus 0-3,8-11
```

Nothing was built on the laptop: the apps came from a WSL build of the same commits. So `JDK_QUARKUS` was
25.0.4.1 there too, and ran only the report.

Pick the CPU lists for your machine: the app on 4 cores that are alike, and Hyperfoil on others.

The scripts in [`tools/quarkus-bench`](../tools/quarkus-bench) read a run directory, `results/runs/<run>`:

- `stats.py RUN`: every metric's mean, standard deviation between reps, CV, median and range, and the
  second-by-second spread within each step;
- `compare.py LABEL=RUN LABEL=RUN...`: the runs side by side, with Welch's t-test against the first;
- `shares.py PROFILE.collapsed`: a CPU profile's shares, by the categories above.

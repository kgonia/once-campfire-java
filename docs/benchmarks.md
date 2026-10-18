# Benchmarks

All results are from one AWS c7a.4xlarge instance in eu-west-1, on October 18, 2026. The instance has 16 AMD EPYC
9R14 cores (no SMT) and 30 GiB of RAM, and runs Amazon Linux 2023. The data is on EBS gp3.

Each app ran in Docker on 4 cores (8–11), and the load generator ran on 4 other cores (12–15). Each app had
gzip and its default caches. Every run started a fresh container on a fresh copy of the
[verification](https://github.com/basecamp/once-campfire-verification) `default` fixture. The values are requests
per second.

Builds, all from commit `1f1aeb9`:

| build | image | what it is |
|---|---|---|
| Rust | `campfire-rust:app` | [once-campfire-rust](https://github.com/basecamp/once-campfire-rust), its default branch |
| JDK 25 | `Dockerfile` | the JVM image (ZGC) |
| JDK 26 AOT | `Dockerfile.aot` | JDK 26 with an AOT cache trained during the build |
| JDK 28 AOT | `Dockerfile.aot --build-arg JDK=28` | JDK 28 EA with AOT code (JEP 544), trained the same way |
| JDK 28 AOT, benchmark-trained | `tools/bench/train.sh` | the JDK 28 image with its cache retrained on the benchmark workload |
| native | `Dockerfile.native` | GraalVM Community native image |

## Benchmark routes

This is `bin/benchmark` from once-campfire-verification:

- 16 clients and 8 s per route, after 2 s at 4 clients.
- 3 rounds, with the order of the apps reversed each round.
- The value is the median of the 3 rounds.
- The load generator validates every response and audits every acknowledged post.

All of the default settings were kept: response caches on, and no mixed reads and writes.

| route | Rust | JDK 25 | JDK 26 AOT | JDK 28 AOT | JDK 28 AOT, benchmark-trained | native | JDK 25 vs Rust |
|---|---:|---:|---:|---:|---:|---:|---:|
| room_show | 34,635 | 50,880 | 48,563 | 50,671 | 51,156 | 28,392 | 1.47× |
| messages_page | 32,792 | 53,881 | 52,064 | 54,950 | 56,312 | 29,768 | 1.64× |
| sidebar | 40,117 | 91,434 | 85,430 | 91,939 | 92,972 | 39,757 | 2.28× |
| search | 40,076 | 86,407 | 81,331 | 84,826 | 86,353 | 38,469 | 2.16× |
| avatar | 84,857 | 258,501 | 256,112 | 257,846 | 259,946 | 113,674 | 3.05× |
| static_css | 90,818 | 287,643 | 284,142 | 287,574 | 290,807 | 128,740 | 3.17× |
| up | 72,267 | 136,529 | 133,446 | 136,862 | 137,735 | 65,271 | 1.89× |
| post_message | 845 | 1,932 | 1,957 | 1,944 | 1,949 | 1,834 | see below |

The highest and lowest round differ by at most 5% for 42 of the 48 values. The exceptions are posting, the JVM
build's search (12%), and a few page values at 6–7%.

Both ports keep whole pages for room_show, messages_page, sidebar and search in a response cache: 64 MB with a
15 s TTL in both ports, invalidated by any commit. Both ports also gzip once and serve the compressed copy. The page
rows therefore measure the request path around the cache: routing, the session cookie, the access checks, the
cache lookup and the write to the socket. avatar, static_css and up are answered from memory. Java serves them on
the Vert.x event loop, before Quarkus REST runs.

A first full run on the same instance, three commits earlier, gave the same order and values within 5% of these.

## Posting

In the full run, Rust posts at 845 req/s, against 1,750 when its posting is measured alone. Java doesn't slow
down this way. That is why the harness's posting value above isn't used as a comparison. The cause wasn't
investigated.

| posting measured | Rust | JDK 25 | Java vs Rust |
|---|---:|---:|---:|
| `bin/benchmark --routes post_message`, 3 rounds | 1,750 | 1,960 | +12% |
| 20 s at 16 clients on a fresh container (`tools/bench/post.sh`), data on EBS | 1,735 | 1,811 | +4% |
| the same, data on tmpfs | 3,687 | 3,477 | −6% |

The first two rows come from the build before `fe69965`. Its posting path is the same as in `1f1aeb9`.

On EBS, `fsync` limits both ports. With the disk taken out (tmpfs), Rust is 6% ahead.

## Pages the benchmark doesn't request

`tools/bench/unseen.sh` drives other pages the same way as `bin/benchmark`:

- the same fixture, signed in as the same user;
- 2 s at 4 clients, then 8 s at 16;
- 3 rounds, the order reversed each round, with medians reported.

It checks only that each status is 200.

`/account/users` and `/webmanifest` answer the load generator's `Accept` header with 406 in both ports, so they
aren't in the table.

| page | Rust | JDK 25 | JDK 26 AOT | JDK 28 AOT | JDK 28 AOT, benchmark-trained | native | JDK 25 vs Rust |
|---|---:|---:|---:|---:|---:|---:|---:|
| another room (`pets`) | 34,962 | 53,536 | 50,510 | 50,807 | 53,068 | 29,016 | 1.53× |
| a direct room | 35,209 | 58,737 | 55,417 | 56,313 | 57,912 | 28,844 | 1.67× |
| another search (`lunch`) | 39,705 | 89,976 | 79,700 | 83,858 | 85,179 | 38,591 | 2.27× |
| a message permalink | 34,299 | 55,690 | 53,356 | 55,299 | 57,386 | 27,902 | 1.62× |
| a user's profile | 18,094 | 15,977 | 15,810 | 15,894 | 15,752 | 9,427 | 0.88× |
| account settings | 14,752 | 9,834 | 9,773 | 10,091 | 9,734 | 6,317 | 0.67× |
| room settings | 17,491 | 12,281 | 12,214 | 12,312 | 12,280 | 7,788 | 0.70× |
| new direct room | 26,025 | 23,410 | 23,773 | 24,222 | 23,210 | 12,908 | 0.90× |
| room involvement | 22,268 | 23,864 | 22,762 | 23,847 | 22,938 | 12,378 | 1.07× |
| user autocomplete | 17,891 | 23,953 | 23,496 | 23,863 | 23,671 | 14,661 | 1.34× |
| bots | 16,143 | 10,477 | 10,475 | 10,695 | 10,686 | 6,512 | 0.65× |

The first four pages are served from the response cache, like the benchmark's own pages, because the cache keys
on the URL.

Neither port caches the other seven pages. Each request renders them and gzips them again. Java is 10–35% slower
on five of them.

Two commits raised Java's speed on these pages by 57–101%:

- **`fe69965`:** writes the layout's importmap (15 KB, more than half of a profile page) and stylesheet tags as
  constant bytes, instead of copying them character by character and hashing them for every page's ETag.
- **`1f1aeb9`:** compresses those tags once and splices the compressed block into each response, as Rust does. The
  compressed pages are 0.6–2.8% larger, and the decompressed bodies are unchanged.

| page | before `fe69965` | after `1f1aeb9` |
|---|---:|---:|
| profile | 8,737 | 15,977 |
| account settings | 5,892 | 9,834 |
| room settings | 7,149 | 12,281 |
| new direct room | 11,667 | 23,410 |
| room involvement | 11,881 | 23,864 |
| bots | 6,677 | 10,477 |

A JFR profile of the profile page between those two commits split its CPU as follows:

- gzip: 21%
- templates: 17%
- JDK code: 14%
- ETags: 10%
- Quarkus REST: 8%
- Vert.x: 8%
- SQLite: 4%
- the session cookie: 3%

JFR undercounts time spent in native code, which includes the JDK's zlib. Rust compresses with zlib-rs.

## Startup and warmup

`tools/bench/warmup.sh` measures two things on a fresh container for each image (2 rounds, averaged):

- The time from `docker run` until `/up` first answers, including the container's own start.
- The req/s in each of the first 20 one-second windows after that, with 16 clients. It measures a room page, which
  the benchmark requests, and a profile page, which it doesn't.

| room page | `/up` after | 1st s | 2nd s | 3rd s | 4th s | 5th s | seconds 10–20 |
|---|---:|---:|---:|---:|---:|---:|---:|
| Rust | 240 ms | 34,232 | 34,270 | 34,342 | 34,250 | 34,170 | 34,234 |
| JDK 25 | 1,980 ms | 4,728 | 9,948 | 16,096 | 37,886 | 54,778 | 56,611 |
| JDK 26 AOT | 1,070 ms | 5,090 | 14,115 | 19,224 | 35,118 | 51,626 | 54,312 |
| JDK 28 AOT | 1,040 ms | 4,962 | 10,572 | 14,774 | 33,286 | 53,244 | 56,516 |
| JDK 28 AOT, benchmark-trained | 1,050 ms | 5,564 | 10,627 | 16,337 | 37,444 | 55,530 | 56,880 |
| native | 340 ms | 27,799 | 28,670 | 28,355 | 28,535 | 28,369 | 28,294 |

| profile page | 1st s | 2nd s | 3rd s | 4th s | 5th s | seconds 10–20 |
|---|---:|---:|---:|---:|---:|---:|
| Rust | 17,908 | 17,972 | 17,960 | 17,999 | 18,012 | 18,026 |
| JDK 25 | 2,564 | 4,126 | 7,474 | 9,490 | 15,350 | 16,059 |
| JDK 26 AOT | 2,387 | 5,036 | 7,254 | 8,530 | 13,831 | 15,861 |
| JDK 28 AOT | 2,786 | 4,045 | 5,843 | 7,652 | 13,316 | 16,254 |
| JDK 28 AOT, benchmark-trained | 2,678 | 4,112 | 6,404 | 8,465 | 13,246 | 16,400 |
| native | 9,796 | 10,010 | 10,013 | 9,988 | 10,036 | 10,071 |

- **AOT:** halves the JVM's startup. All of the JVM builds reach full speed in about the 5th second, because the
  JIT compiles on the same 4 cores that serve the requests. JDK 28's AOT code doesn't change that here.
- **Native image:** at full speed at once, but about half the JVM's speed. It has no JIT, and the Community
  Edition has neither profile-guided optimization nor G1.

## AOT training on the benchmark

`Dockerfile.aot` trains its cache during the build, on a fresh, empty install. `tools/bench/train.sh` retrains
the JDK 28 image's cache on the benchmark itself, then bakes the cache into an image built `FROM` the generic
one:

- the benchmark's fixture and routes;
- the load generator, at 4 clients and then 16;
- three passes.

This is the best case for training: production runs the same code on the same pages.

| benchmark-trained vs generic (JDK 28) | |
|---|---|
| the benchmark's routes | +0.3% to +2.5% |
| other cached pages (rooms, search, permalink) | +1.6% to +4.5% |
| uncached pages | −4.2% to −0.1% |
| first 4 s of the room page | 1–13% more requests per second |
| startup | the same (about 1.05 s) |

Training on the measured workload buys little: about as much as the rounds vary. The generic cache is the one
shipped.

## Memory

`tools/bench/memory.sh` measured the JVM build at `fe69965`:

- idle: `docker stats` 10 s after `/up`;
- peak: sampled every 0.5 s through 10 s each of the room page, the profile page and posting, at 16 clients;
- live heap: the largest heap ZGC reported after a collection.

| heap | idle | peak | largest live heap | room | profile | post |
|---|---:|---:|---:|---:|---:|---:|
| default (`MaxRAMPercentage=50`, `SoftMaxHeapSize=1g`) | 658 MiB | 1.29 GiB | 462 MB | 43,100 | 8,600 | 1,850 |
| `-Xmx512m` | 658 MiB | 780 MiB | | 41,900 | 8,200 | 1,830 |
| `-Xmx256m` | 433 MiB | 538 MiB | 196 MB | 40,700 | 7,400 | 1,810 |
| `-Xmx128m` | 292 MiB | 398 MiB | | 25,600 | 5,500 | 970 |

The same measurement for the other builds:

| build | idle | peak |
|---|---:|---:|
| Rust | 19 MiB | 132 MiB |
| native | 69 MiB | 249 MiB |

The req/s here are from 10 s runs right after startup, so they are lower than the benchmark's. With the heap
capped at 256 MB, Java's peak falls to 538 MiB. The room page loses 6% of its speed, and the profile page 14%.

## Reproducing

The scripts are in `tools/bench`. They run inside the harness's runner container (`tools/bench/Dockerfile`,
started by `run.sh`). The runner runs with the host's network and docker socket, and with the workspace volume
`campfire-bench`.

The workspace holds:

- a once-campfire-verification checkout, with its load generator built (`cargo build --release` in `loadgen`)
  and its `default` fixture seeded;
- the Rust port's image, `campfire-rust:app`.

The harness takes only the app names it knows. For this run, `bench/compare.rb` was changed locally to take
`jv-<tag>` as `once-campfire-java:<tag>`, with `java` as the runtime.

```sh
docker build -t campfire-bench-runner:local tools/bench
tools/bench/build.sh > build.log
tools/bench/all.sh          # train, bin/benchmark, unseen pages, warmup: about 80 minutes
tools/bench/run.sh "$(cat tools/bench/lib.sh tools/bench/post.sh)"
tools/bench/run.sh "IMAGE=once-campfire-java:jvm; $(cat tools/bench/lib.sh tools/bench/memory.sh)"
```

`lib.sh` starts an image the way `bin/benchmark` does, and measures a route the way the harness's per-route step
does.

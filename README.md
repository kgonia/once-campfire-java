# Campfire for Java

A port of [Campfire](https://github.com/basecamp/once-campfire) to Java 25 and Quarkus. It is a drop-in
replacement for the Rails app, as the [Rust port](https://github.com/basecamp/once-campfire-rust) defines one: it
keeps the Rails database, Active Storage files, signed and encrypted cookie formats, Action Cable protocol and Web
Push keys, so an existing install can switch to it with everyone staying signed in. Campfire behaves as it does
in Rails; responses are not byte-identical to Rails or Rust.

The code is meant to read as an ordinary Quarkus application, not as a transliteration of Rails or Rust. Rails
internals are kept only where drop-in compatibility needs them.

## Layout

Packages are organized by feature. Inside a feature:

- **`*Resource`**: Quarkus REST (`@Path`) classes on virtual threads. They do HTTP only: read parameters, check
  access, call a service, render a template or redirect. They never touch the database.
- **`*Service`**: `@ApplicationScoped` beans that own the transactions and the side effects, such as cable
  broadcasts, disconnecting sockets, webhooks and push.
- **Records and static queries** for each table (`Room`, `Message`, `User`, …), plus view models and presenters
  that build what the templates render. Their SQL is built with jOOQ (see below).

| package | what's in it |
|---|---|
| `rooms` | room pages, open/closed/direct rooms, involvement, refresh |
| `messages` | posting, editing, boosts, message rendering and its fragment cache |
| `sidebar`, `search` | the sidebar frame and full-text search |
| `users`, `sessions`, `accounts`, `bots`, `push` | people, sign-in and transfers, account settings, bot API and webhooks, Web Push |
| `storage` | the Active Storage HTTP surface, analysis and variants (libvips, ffmpeg) |
| `cable` | the Action Cable server and channels |
| `richtext`, `rails`, `useragent`, `integrations` | Action Text HTML, Rails-compatible crypto and serialization, browser detection, OpenGraph/QR/Web Push clients |
| `web` | request context, access filter, the front layer (public cache, static files), gzip, error pages |
| `view` | the templates' helpers, the fragment cache and page digests |

Access rules are annotations (`@Access.Public`, `@GuestOnly`, `@BotsAllowed`, `@NoForgeryProtection`) checked by
`web.AccessFilter`. Classes are `@NullMarked`, and NullAway (via Error Prone) enforces nullability at compile time.

### Templates

The templates are [jte](https://jte.gg) in `src/main/jte`, compiled ahead of time with `trimControlStructures` off,
so output is exactly the template text. Rails' Erubi removes a line that holds only a `<% %>` tag. To get the same
result, a control tag (`@if`, `@else`, `@endfor`, `!{…}`) goes at the end of the previous line instead of on a line
of its own:

```
      <div class="quick-boosts">@for(var reaction : BoostView.QUICK_REACTIONS)
          <form …>
</button></form>@endfor

        <a …>
```

These are the same positions the Rust port's askama `{%-` tags take. Partials end with as many newlines as their
Rust counterpart renders, and pages end at their layout call.

## Idiomatic approach and exceptions

The default is the framework's own way of doing things. A deviation needs one of two reasons: matching Rails
byte for byte, or a measured performance loss. Each deviation is listed below with its reason.

**What's standard:**
- Quarkus REST resources on virtual threads, CDI beans, `@ConfigMapping`, and request/response filters.
- Actions that validate their own responses (`fresh_when`) use JAX-RS `EntityTag`, `Request#evaluatePreconditions`
  and `CacheControl`.
- Rails' static security headers as `quarkus.http.header.*`. Only HSTS, which depends on the scheme, is set in
  code.
- Two Quarkus-managed Agroal datasources, `writer` and `readers`, configured in `application.properties`.
- jOOQ code generation from the database schema, with queries built on the generated tables and columns. This is
  jOOQ's ["SQL builder with code generation"](https://www.jooq.org/doc/latest/manual/getting-started/use-cases/jooq-as-a-sql-builder-with-code-generation/)
  mode, so a renamed column breaks the build instead of a request.
- Caffeine for caches, jte for templates, jsoup for HTML, and JSpecify with NullAway for nullability.

**Exceptions:**

| what | instead of | why |
|---|---|---|
| jOOQ only renders SQL, once per query, into a constant (`campfire.db.Sql`); `Conn` runs it on a cached `PreparedStatement` | executing through jOOQ | Measured on Linux: a primary-key lookup takes 3 µs this way, 13 µs through jOOQ, and 9 µs with jOOQ's `keepStatement`. On cached pages that cost room_show 23% and messages_page 43%. |
| A prepared-statement cache per connection | the driver's or pool's cache | sqlite-jdbc and Agroal have none. Without it room_show loses 21%. The pools never retire a connection, so the cache stays valid. |
| `Database.write`: one writer, `BEGIN IMMEDIATE`, after-commit hooks | `@Transactional`/JTA, or jOOQ's `transaction()` | A plain `BEGIN` in SQLite is deferred, so concurrent writers fail with `SQLITE_BUSY` when they upgrade to a write lock. This is what Rails' adapter does. |
| The `data_version` observer and the WAL checkpointer, on connections of their own | pooled connections | `PRAGMA data_version` only means something asked of the same connection every time. |
| The full-text index (`message_search_index`) in plain SQL: its writes are strings, and searches name it with `table(name(...))` and a `MATCH` template | generated classes | It's an FTS5 virtual table, which the code generator doesn't read. |
| jOOQ classes generated from `schema.sql` loaded into SQLite at build time (`src/build/jooq`) | running migrations, e.g. Flyway, then generating | Rails owns the migrations. `schema.sql` is exactly what `db:prepare` creates. |
| One gzip step in `web.ResponseFinisher`, with pooled `Deflater`s (`web.Gzip`) | Quarkus/Vert.x HTTP compression | Speed: a page is gzipped once and served compressed from the caches, where Vert.x would recompress every response on the event loop (69 µs for a 9 KB page, against about 105 µs of CPU for a whole cached room page). |
| Its own JSON writer (`rails.Json`) | Jackson | Historical: it matched Rails byte for byte (`<` escapes, Ruby number formats) for parity with the Rust port, which is no longer a goal. Being replaced. |

What the datasources need for SQLite (in `application.properties`):
- `db-kind=other` with the SQLite driver.
- The pragmas in the JDBC URL.
- A one-connection writer pool.
- Transactions disabled.
- No idle removal or background validation.

Agroal can't tell whether a connection survived an error for `db-kind=other`. Its startup warning about this is
silenced, because a local file's connections don't break.

## Build and run

Requires JDK 25 and Maven 3.9.

```sh
mvn package                       # tests included; -DskipTests to skip them
CAMPFIRE_STORAGE=./storage SECRET_KEY_BASE=… HTTP_PORT=3000 java -jar target/quarkus-app/quarkus-run.jar
```

Configuration is the Rails app's environment: `SECRET_KEY_BASE`, `DISABLE_SSL`, `VAPID_PUBLIC_KEY`/`VAPID_PRIVATE_KEY`,
`APP_VERSION`, `GIT_REVISION`, `HTTP_PORT`, plus `CAMPFIRE_STORAGE` (default `/rails/storage`, which holds `db/` and
`files/`) and `DB_READERS` (the most read connections, opened as needed; default 16).

Images, with the same user, layout and port contract as the Rails image:

```sh
docker build -t once-campfire-java:app .                              # JVM (ZGC)
docker build -f Dockerfile.native -t once-campfire-java:native .      # GraalVM native image
docker build -f Dockerfile.aot -t once-campfire-java:aot .            # JDK 26, jlink runtime, AOT cache
```

The AOT image trains its cache during the build on a fresh, empty install, never on production data. It runs on
a 99 MB jlink runtime. The Dockerfile explains the module list and a JDK 26.0.2.1 bug it has to work around.

## Verifying parity

`tools/parity` runs Rust and Java side by side on fresh copies of a [verification](https://github.com/basecamp/once-campfire-verification)
seed. It requests 125 cases from both and diffs normalized headers and bodies: every room, every message variant,
the sidebar, search, the account and user pages, Turbo-Frame requests, and anonymous pages. Rust stands in for
Rails' behaviour here; request ids, runtimes and cookie attribute formatting are ignored. It is a regression
check, not a goal: deliberate differences from Rust are allowed. The verification harness's route contracts and
browser flows (`browser/smoke.mjs`) are the acceptance checks.

```sh
export CAMPFIRE_SEED=/path/to/fixtures/default JAVA_HOME=…
tools/parity/up.sh                                         # Rust on :4100 (campfire-rust:app), Java on :4101
python tools/parity/parity.py [--gzip] [--host campfire.test] [--ua chrome-win] [--only /rooms]
```

`python tools/parity/writes.py` checks writes the same way: on freshly started servers it submits 38 browser
writes to both, in order (messages, boosts, rooms, involvement, profile, account, bots, the bot API, push
subscriptions, searches, bans, joining, signing out and in). It compares statuses, redirects, cookie names, Turbo
Stream and JSON bodies, and above all each write's database effect: rows added, removed or changed per table,
with times and random tokens compared only as changed or not. It reads both databases with `docker cp`, from the
containers named by `RUST_CONTAINER` and `JAVA_CONTAINER` (default `cf-rust-parity`, `cf-java-parity`).

Sign a session cookie for `parity.py` with `SignCookie.java` (see its header). The script writes it to
`tools/parity/work/cookie.txt`.

At the time of writing, every case matches except for timing:

- Cookie `expires` values that land in different seconds.
- Session-transfer tokens, which embed a millisecond expiry, on the user and profile pages.

The verification harness's benchmark preflight returns identical body hashes for Rust, the JVM build and the
native build.

## Benchmarks

`bin/benchmark` from once-campfire-verification:

- 16 clients, 8 s per route, 3 interleaved rounds.
- Server pinned to 4 vCPUs (`--cpus 8-11`), load generator on 4 others.
- Intel Core Ultra 9 275HX, Docker Desktop on Windows 11.
- Every response was contract-validated and every acknowledged write audited.

Median req/s:

| route | Rust | Java (JVM) | Java (JDK 26 + AOT cache) | Java (native) |
|---|---:|---:|---:|---:|
| room_show | 27,994 | 36,466 | 32,164 | 23,071 |
| messages_page | 26,801 | 37,071 | 37,213 | 26,318 |
| sidebar | 31,526 | 52,701 | 46,476 | 29,264 |
| search | 33,572 | 45,997 | 46,156 | 29,514 |
| post_message | 3,126 | 2,657 | 2,679 | 2,146 |
| avatar | 108,541 | 190,859 | 175,417 | 123,126 |
| static_css | 140,961 | 194,644 | 195,437 | 137,337 |
| up | 61,773 | 77,443 | 75,295 | 44,214 |

Expect about ±20% between rounds, because Hyper-V schedules vCPUs onto both P- and E-cores. Compare results only
from the same interleaved run.

- **Native build:** starts in about 30 ms, against about 0.7 s for the JVM. It's slower under sustained load,
  because it has no JIT and the Community Edition has neither PGO nor G1.
- **AOT cache:** cuts JVM startup to about 0.5 s. It barely helps the first seconds under load, because the JIT
  still has to compile on the same 4 CPUs.
- **room_show:** the harness measures it first, about 5 s after the container starts, so it carries most of the
  JVM's warmup.

## Known differences from the Rust port

None of these show up in the parity suite or the harness:

- Gzipped responses carry a `Content-Length`; Rust sends them chunked.
- For clients that prefer zstd, Rust's proxy layer zstd-compresses bodies the app left unencoded. Java leaves them
  uncompressed. This only affects bodies the app's own gzip skipped.
- Rust's proxy sniffs a `Content-Type` for responses that lack one. Java doesn't; no app response lacks one.
- Header order and the case of header names on HTTP/1.1 can differ.
- Thruster's per-request log line and its `X-Cache` header aren't reproduced.
- Only text, JSON, JavaScript, XML and SVG are gzipped. A client that refuses identity encoding gets an
  uncompressed body, not a 406.
- Error pages carry the security and version headers. Rails renders them outside the controller, without
  them. As in Rails they leave the session and cookies untouched.
- Static files carry the security headers too. Rails serves them before the app, without them.

Deliberate:

- No `X-Runtime`, `X-Request-Id` or `X-Request-Start` headers.
- Active Storage direct uploads (`POST /rails/active_storage/direct_uploads`) get the app's Fetch Metadata check
  instead of Rails' CSRF token. The app's pages carry no token, so in Rust only sessions Rails created can use
  them; here any signed-in page can.

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
| `web` | request origin, cookies and the Rails session, access filter, the front layer (public cache, static files), gzip, error pages |
| `tls` | HTTPS for `TLS_DOMAIN`: ACME certificates, the HTTP port's challenges and redirect |
| `view` | the templates' helpers, the fragment cache and page digests |

Access rules are annotations (`@Access.Public`, `@GuestOnly`, `@BotsAllowed`, `@NoForgeryProtection`) checked by
`web.AccessFilter`. Classes are `@NullMarked`, and NullAway (via Error Prone) enforces nullability at compile time.

### Templates

The templates are [jte](https://jte.gg) in `src/main/jte`, compiled ahead of time in jte's HTML mode with
`trimControlStructures` on, so control structures sit on lines of their own. They're plain HTML: no Rails tag
helpers, and jte's smart attributes drop an attribute whose value is `null` or `false` (`value="${room.name()}"`,
`checked="${selected}"`). Markup more than one page shares is a partial: the Rails helpers that render it
(`link_back`, `translation_button`, `avatar_tag`, …) are in `helpers/` and next to the views that use them.

Templates render straight into `view.HtmlOut`, which escapes for text and quoted attributes alike, and static text
is precompiled to bytes (`binaryStaticContent`). Messages and sidebar rooms are rendered once per version into the
fragment cache and written into pages as bytes.

## Idiomatic approach and exceptions

The default is the framework's own way of doing things. A deviation needs one of two reasons: matching Rails
byte for byte, or a measured performance loss. Each deviation is listed below with its reason.

**What's standard:**
- Quarkus REST resources on virtual threads, CDI beans, `@ConfigMapping`, and request/response filters.
- Actions that validate their own responses (`fresh_when`) use JAX-RS `EntityTag`, `Request#evaluatePreconditions`
  and `CacheControl`.
- The scheme and host behind a reverse proxy from `quarkus.http.proxy.*`, trusting the same private ranges Rails
  does.
- Rails' static security headers as `quarkus.http.header.*`. Only HSTS, which depends on the scheme, is set in
  code.
- HTTPS through the Quarkus TLS registry: `tls.Certificates` is the `KeyStoreProvider` of the configuration the
  HTTP server uses, and a `CertificateUpdatedEvent` after `reload()` swaps a new certificate in. ACME is
  acme4j's.
- Two Quarkus-managed Agroal datasources, `writer` and `readers`, configured in `application.properties`.
- jOOQ code generation from the database schema, with queries built on the generated tables and columns. This is
  jOOQ's ["SQL builder with code generation"](https://www.jooq.org/doc/latest/manual/getting-started/use-cases/jooq-as-a-sql-builder-with-code-generation/)
  mode, so a renamed column breaks the build instead of a request.
- Caffeine for caches, jte for templates, jsoup for HTML, Vert.x's JSON (Jackson underneath) for JSON, and JSpecify
  with NullAway for nullability.

**Exceptions:**

| what | instead of | why |
|---|---|---|
| jOOQ only renders SQL, once per query, into a constant (`campfire.db.Sql`); `Conn` runs it on a cached `PreparedStatement` | executing through jOOQ | Measured on Linux: a primary-key lookup takes 3 µs this way, 13 µs through jOOQ, and 9 µs with jOOQ's `keepStatement`. On cached pages that cost room_show 23% and messages_page 43%. |
| A prepared-statement cache per connection | the driver's or pool's cache | sqlite-jdbc and Agroal have none. Without it room_show loses 21%. The pools never retire a connection, so the cache stays valid. |
| `sessions.Authentication` reads the Rails-signed `session_token` cookie or the bot API's `bot_key` itself, once per request | a Quarkus Security `HttpAuthenticationMechanism` and identity providers | Measured: Quarkus' authentication handler, authorizer and deferred `SecurityIdentity` (Mutiny, context propagation) took about 35% of a signed-in sidebar request's CPU samples and cost room_show, sidebar and search about 10% of their throughput. |
| `Database.write`: one writer, `BEGIN IMMEDIATE`, after-commit hooks | `@Transactional`/JTA, or jOOQ's `transaction()` | A plain `BEGIN` in SQLite is deferred, so concurrent writers fail with `SQLITE_BUSY` when they upgrade to a write lock. This is what Rails' adapter does. |
| The `data_version` observer and the WAL checkpointer, on connections of their own | pooled connections | `PRAGMA data_version` only means something asked of the same connection every time. |
| The full-text index (`message_search_index`) in plain SQL: its writes are strings, and searches name it with `table(name(...))` and a `MATCH` template | generated classes | It's an FTS5 virtual table, which the code generator doesn't read. |
| jOOQ classes generated from `schema.sql` loaded into SQLite at build time (`src/build/jooq`) | running migrations, e.g. Flyway, then generating | Rails owns the migrations. `schema.sql` is exactly what `db:prepare` creates. |
| One gzip step in `web.ResponseFinisher`, with pooled `Deflater`s (`web.Gzip`) | Quarkus/Vert.x HTTP compression | Speed: a page is gzipped once and served compressed from the caches, where Vert.x would recompress every response on the event loop (69 µs for a 9 KB page, against about 105 µs of CPU for a whole cached room page). |
| Public responses (static files, avatars, logos, QR codes, Active Storage files) kept by URI in `web.FrontCache`, plain and gzipped, and answered from a Vert.x route on the event loop | a caching proxy in front, or none | Thruster does this in front of Rails. Answering from memory before Quarkus REST is what serves avatars and static files at about 190k req/s. |
| The client's address for bans: the last untrusted `X-Forwarded-For` hop (`web.RequestOrigin`) | Quarkus' forwarded remote address | Quarkus takes the first hop, which the client writes. Rails takes the last one a trusted proxy didn't add, and Quarkus 3.40 has no hook to choose. |
| Response cookies as Vert.x `Cookie`s (`web.Cookies`), the Rails session decrypted and written back by `web.RailsSession` | JAX-RS `NewCookie` | Quarkus REST writes a `NewCookie` with RFC 2109's `Version=1`, and Java's cookie jar then sends it back as `$Version="1"; session_token="…"`. Values stay Rack-escaped and the session stays Rails' encrypted JSON, so a Rails install's cookies keep working. |
| Rails' nested form fields (`user[name]`, `user_ids[]`) and JSON bodies read into `web.Params`, from Quarkus REST's parsed form (`MultipartFormDataInput`) or the body; flat values are `@RestQuery`/`@RestHeader` parameters | `@RestForm` and `@BeanParam` throughout | The services take Rails' parameter hashes, with Rack's merge of query and form and its type checks (a string where a hash belongs is a 500, an empty hash a 400). Field names stay the frontend's. |
| `_method` read by a pre-matching filter (`AccessFilter.methodOverride`) that reads a form POST's body off the event loop and hands it on | a filter on parsed form params | Quarkus REST reads the form only after it has matched the method, and reading the body in a Vert.x handler first breaks HTTP/2: Quarkus REST then pauses a request that has already ended. |
| `web.Formats.respondTo` for endpoints that offer several types, a Turbo Stream, or JavaScript or JSON at a plain URL | `@Produces` | Rails ignores a browser's `Accept` once it lists `*/*`, and answers those 406. `@Produces` would match the `*/*` and serve the stream. HTML-only pages use `@Produces`, and the `.json` suffix and `format` parameter replace `Accept` before matching. |

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

HTTPS takes Thruster's settings, `THRUSTER_`-prefixed forms included. `TLS_DOMAIN` (comma-separated) turns it on:
HTTPS on `HTTPS_PORT` (443) with a Let's Encrypt certificate per name, obtained over HTTP-01 and renewed 30 days
before it expires, while `HTTP_PORT` only answers ACME challenges and redirects to HTTPS. `ACME_DIRECTORY` names
another CA, `EAB_KID`/`EAB_HMAC_KEY` bind an external account, and certificates and the account key live in
`STORAGE_PATH` (default `storage/thruster`), in autocert's layout, so an install that ran Thruster keeps both.

Images, with the same user, layout, port contract and ONCE backup hooks as the Rails image (`/hooks/pre-backup` runs
`campfire backup`, an online copy of the database into `storage/backups`):

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

`python tools/parity/writes.py` checks writes the same way: on freshly started servers it submits 39 browser
writes to both, in order (messages, boosts, rooms, involvement, profile, account, bots, the bot API with text and
an attachment, push
subscriptions, searches, bans, joining, signing out and in). It compares statuses, redirects, cookie names, Turbo
Stream and JSON bodies, and above all each write's database effect: rows added, removed or changed per table,
with times and random tokens compared only as changed or not. It reads both databases with `docker cp`, from the
containers named by `RUST_CONTAINER` and `JAVA_CONTAINER` (default `cf-rust-parity`, `cf-java-parity`).

Sign a session cookie for `parity.py` with `SignCookie.java` (see its header). The script writes it to
`tools/parity/work/cookie.txt`.

HTML and Turbo Stream bodies are compared as markup (`tools/parity/markup.py`): tags with sorted, decoded
attributes and collapsed whitespace, so ERB's and jte's spellings of the same element agree. JSON bodies are
compared by value. Where only the spelling differs, the ETag over those bytes is ignored too.

At the time of writing, every case matches except the message edit form. There Java closes the delete `<form>`
that Rails' template leaves open; the browser closes it at the same point, so the DOM is the same.

The verification harness's benchmark preflight returns identical body hashes for Rust, the JVM build and the
native build.

`python tools/outbound/outbound.py BASE VAPID_PUBLIC_KEY` covers what neither compares: the outbound HTTP client.
On a fresh install it has a bot webhook answer with text and another with a PNG, and sends a Web Push through
Mozilla's push service to a subscription it registered there, then decrypts what arrives. See its header for
what it needs.

## Benchmarks

`bin/benchmark` from once-campfire-verification:

- 16 clients, 8 s per route, 3 interleaved rounds.
- Server pinned to 4 vCPUs (`--cpus 8-11`), load generator on 4 others.
- Intel Core Ultra 9 275HX, Docker Desktop on Windows 11.
- Every response was contract-validated and every acknowledged write audited.

Median req/s, from one interleaved run (2026-10-17, after a change of the host's power mode, so not comparable
with earlier tables):

| route | Rust | Java (JVM, JDK 25) | Java (JDK 26 + AOT cache) | Java (JDK 28 EA + AOT code) | Java (native) |
|---|---:|---:|---:|---:|---:|
| room_show | 34,700 | 62,300 | 61,100 | 61,200 | 40,400 |
| messages_page | 32,500 | 62,700 | 62,100 | 63,000 | 40,900 |
| sidebar | 39,700 | 85,200 | 82,800 | 83,500 | 52,700 |
| search | 39,600 | 82,600 | 80,200 | 81,200 | 50,400 |
| post_message | 3,600 | 3,300 | 3,400 | 3,200 | 2,600 |
| avatar | 153,000 | 230,900 | 226,600 | 228,600 | 156,600 |
| static_css | 162,900 | 245,400 | 246,700 | 246,000 | 170,800 |
| up | 87,100 | 115,500 | 112,500 | 112,300 | 74,500 |

Rounds differed by 0–6% (post_message on the native build: 34%). Hyper-V schedules vCPUs onto both P- and E-cores,
so compare results only from the same interleaved run.

Startup and warmup, from a separate run: milliseconds until `/up` answers after the process starts, then the
signed-in room page's req/s (gzip, 16 connections) in each of the first seconds.

| image | `/up` after | 1st s | 2nd s | 3rd s | 4th s | 5th s | steady |
|---|---:|---:|---:|---:|---:|---:|---:|
| Rust | 80 ms | 38,700 | 39,600 | 39,700 | 35,000 | 35,400 | ~35,000 |
| Java (JVM, JDK 25) | 1,600 ms | 3,400 | 16,000 | 36,800 | 64,800 | 68,300 | ~69,000 |
| Java (JDK 26 + AOT cache) | 840 ms | 3,900 | 21,300 | 30,400 | 42,300 | 59,900 | ~66,000 |
| Java (JDK 28 EA + AOT code) | 780 ms | 3,600 | 13,700 | 27,500 | 61,200 | 67,000 | ~67,000 |
| Java (native) | 140 ms | 15,500 | 16,600 | 16,600 | 16,000 | 16,100 | ~15,500 |

- **Native build:** starts fastest of the Java builds and is at full speed at once, but that speed is a fraction of
  the JVM's: it has no JIT, and the Community Edition has neither PGO nor G1.
- **AOT cache:** halves JVM startup. JDK 28's AOT code (JEP 544, `--build-arg JDK=28`) adds little to that here,
  and none of the JVM builds is at full speed before about the fourth second, because the JIT still compiles on
  the same 4 CPUs. Steady state is within a few percent of the plain JVM.
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
- The public cache keys on the URI alone, not the host, and doesn't keep responses that vary on anything but
  `Accept-Encoding`. HEAD requests go to the app.
- `_method` counts in the first 16 KB of a multipart form, where Rails' forms put it. Rack reads the whole form.
- Malformed JSON is a 400 when the action reads its parameters, after sign-in. Rails raises it before the action.
- A trusted proxy's `X-Forwarded-Port` and `X-Forwarded-Ssl` count, as they do in Rack.
- Static files carry the security headers too. Rails serves them before the app, without them.
- `Vary: Accept` is sent only by endpoints that offer more than one type. Rails sends it whenever the format came
  from `Accept`.
- An HTML-only page answers an unacceptable `Accept` with 406 before sign-in and its own checks run. Rails
  redirects to sign-in, or answers 404 or 403, first.
- A `Range` with several ranges gets the whole file with a 200, not a `multipart/byteranges` 206. RFC 9110 lets a
  server ignore ranges; single ranges get a 206 as before.
- With `TLS_DOMAIN`, a name without a cached certificate gets one ordered at boot rather than during its first
  handshake, and until it arrives (seconds, normally) HTTPS serves a self-signed placeholder. Only HTTP-01 is
  answered, not TLS-ALPN-01, and a cached certificate for a name no longer in `TLS_DOMAIN` isn't served.

Deliberate:

- No `X-Runtime`, `X-Request-Id` or `X-Request-Start` headers.
- Active Storage direct uploads (`POST /rails/active_storage/direct_uploads`) get the app's Fetch Metadata check
  instead of Rails' CSRF token. The app's pages carry no token, so in Rust only sessions Rails created can use
  them; here any signed-in page can.

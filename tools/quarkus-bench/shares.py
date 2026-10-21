"""Shares of an async-profiler collapsed profile: python3 shares.py FILE [FILE...]

Each stack is counted once, in the first category (in this order) any of its frames matches.
"""
import re
import sys
from collections import Counter

CATEGORIES = [
    ('SQLite, all (driver, pool, native, campfire.db)', r'org/sqlite/|io/agroal/|sqlite3|campfire/db/'),
    ('  of which: data_version (Database.dataVersion)', r'campfire/db/Database\.dataVersion'),
    ('  of which: room lookup (RoomService.findForUser)', r'RoomService\.findForUser'),
]
# disjoint top-level buckets (first match wins)
BUCKETS = [
    ('SQLite (sqlite-jdbc, Agroal, libsqlite, campfire.db)', r'org/sqlite/|io/agroal/|sqlite3|campfire/db/'),
    ('GC and JIT', r'^(GC Thread|G1 |VM Thread|C1 CompilerThre|C2 CompilerThre|ParGC|Parallel)|SpinPause|PSPromotion|PSScavenge|CompileBroker'),
    ('response cache lookup and key (ResponseCache)', r'campfire/web/ResponseCache'),
    ('cookie and session (Authentication, RailsSession, Cookies)', r'campfire/sessions/Authentication|campfire/web/RailsSession|campfire/web/Cookies|campfire/sessions/SessionLookup'),
    ('Quarkus REST dispatch and filters', r'org/jboss/resteasy/reactive|io/quarkus/resteasy|io/quarkus/vertx/http'),
    ('Netty/Vert.x I/O and kernel', r'io/netty/|io/vertx/|\[k\]|epoll|write|send|recv'),
]

for path in sys.argv[1:]:
    total = 0
    cat = Counter()
    bucket = Counter()
    for line in open(path, encoding='utf-8', errors='replace'):
        stack, _, n = line.rstrip('\n').rpartition(' ')
        n = int(n)
        total += n
        for name, rx in CATEGORIES:
            if re.search(rx, stack):
                cat[name] += n
        for name, rx in BUCKETS:
            if re.search(rx, stack):
                bucket[name] += n
                break
        else:
            bucket['other'] += n
    print(f'\n== {path}: {total:,} samples')
    for name, _ in CATEGORIES:
        print(f'  {100 * cat[name] / total:5.1f}%  {name}')
    print('  -- disjoint buckets')
    for name, n in bucket.most_common():
        print(f'  {100 * n / total:5.1f}%  {name}')

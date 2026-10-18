#!/bin/bash
# Runs inside the bench runner (./run.sh). The app runs on CPUs 8-11, loadgen on 12-15, like bin/benchmark.
set -euo pipefail
W=/var/lib/docker/volumes/campfire-bench/_data
R=$W/once-campfire-verification
F=$R/fixtures/default
LG="taskset -c 12-15 $R/loadgen/target/release/loadgen"
T=$W/aot-train
B=http://127.0.0.1:25130
label() { ruby -rjson -e 'puts JSON.parse(File.read(ARGV[0])).fetch(ARGV[1])' $F/labels.json "$1"; }
json() { ruby -rjson -e 'puts JSON.parse(STDIN.read).fetch(ARGV[0])' "$1"; }
ROOM=$(label rooms.watercooler)
WRITE_ROOM=$(label rooms.hq)
ms() { echo $(( $(date +%s%N) / 1000000 )); }

# start NAME IMAGE-ARGS...: a fresh copy of the fixture, as bin/benchmark prepares it; prints ms until /up.
start() {
  local name=$1; shift
  docker rm -f $name >/dev/null 2>&1 || true
  rm -rf $T/data; mkdir -p $T/data/logs
  cp -r $F/db $T/data/db; cp -r $F/storage $T/data/files
  sqlite3 $T/data/db/production.sqlite3 "UPDATE push_subscriptions SET endpoint = 'https://127.0.0.1:9/push/' || id; UPDATE webhooks SET url = 'http://127.0.0.1:9/hook/' || id;"
  chmod -R a+rwX $T/data
  local t0=$(ms)
  docker run -d --name $name --network host --cpuset-cpus 8-11 --env-file $F/reference.env -e RAILS_LOG_LEVEL=warn -e HTTP_PORT=25130 \
    -v $T/data/db:/rails/storage/db -v $T/data/files:/rails/storage/files -v $T/data/logs:/rails/storage/logs "$@" >/dev/null
  until curl -fs -o /dev/null $B/up; do sleep 0.02; done
  STARTUP_MS=$(( $(ms) - t0 ))
  docker exec --user root $name chmod -R a+rwX /rails/storage/db
  COOKIE=$($LG login --base $B --email "$(label emails.david)" --password "$(label passwords.all)" | json cookie)
  local scrape=$($LG scrape --base $B --cookie "$COOKIE" --room $ROOM)
  CSRF=$(json csrf <<<"$scrape"); CSS=$(json css <<<"$scrape")
}

# hit NAME PATH-OR-post: bin/benchmark's per-route step, 2 s at 4 clients then DUR s at 16; prints req/s.
hit() {
  local args
  if [ "$2" = post ]; then args=(--post-room $WRITE_ROOM --csrf "$CSRF"); else args=(--path "$2"); fi
  $LG http --base $B --cookie "$COOKIE" "${args[@]}" --conc 4 --duration 2 >/dev/null
  $LG http --base $B --cookie "$COOKIE" "${args[@]}" --conc 16 --duration ${DUR:-8} | ruby -rjson -e 'v = JSON.parse(STDIN.read); ok = v["errors"].zero? && v["statuses"].keys == ["200"]; print ok ? format("%.0f", v["rps"]) : "ERR(#{v["statuses"].keys.join("/")})"'
}

bench_routes() {
  ROUTES=(room_show "/rooms/$ROOM" messages_page "/rooms/$ROOM/messages?before=$(label messages.busy_060)" sidebar /users/me/sidebar
    search "/searches?q=coffee" avatar "/users/$(label avatar_tokens.jason)/avatar" static_css "$CSS" up /up post_message post)
}

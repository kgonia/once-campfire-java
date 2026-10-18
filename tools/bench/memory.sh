# Memory of the JVM build under four heap limits: docker stats idle and sampled through room, profile
# and posting runs, and the largest live heap ZGC reports after a collection. Overrides jvm.options
# with explicit flags, since its SoftMaxHeapSize=1g stops a JVM capped below 1 GB from starting.
# Usage: ./run.sh "IMAGE=...; $(cat lib.sh memory.sh)"
mem() { docker stats --no-stream --format "{{.MemUsage}}" $1 | cut -d/ -f1; }
for heap in default 512m 256m 128m; do
  flags="-XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:MaxRAMPercentage=50 --enable-native-access=ALL-UNNAMED -Xlog:gc:stdout"
  [ $heap = default ] && flags+=" -XX:SoftMaxHeapSize=1g" || flags+=" -Xmx$heap"
  start cf-mem --entrypoint java ${IMAGE:-once-campfire-java:jvm} $flags -Dquarkus.http.port=25130 -jar /rails/app/quarkus-run.jar
  sleep 10; idle=$(mem cf-mem)
  ( for i in $(seq 40); do mem cf-mem; sleep 0.5; done ) > /tmp/m &
  room=$($LG http --base $B --cookie "$COOKIE" --path /rooms/$ROOM --conc 16 --duration 10 | ruby -rjson -e "printf %q(%.0f), JSON.parse(STDIN.read)[%q(rps)]")
  profile=$($LG http --base $B --cookie "$COOKIE" --path /users/$(label users.jason) --conc 16 --duration 10 | ruby -rjson -e "printf %q(%.0f), JSON.parse(STDIN.read)[%q(rps)]")
  post=$($LG http --base $B --cookie "$COOKIE" --post-room $WRITE_ROOM --csrf "$CSRF" --conc 16 --duration 10 | ruby -rjson -e "printf %q(%.0f), JSON.parse(STDIN.read)[%q(rps)]")
  wait
  live=$(docker logs cf-mem 2>&1 | grep -oE "[0-9]+M\([0-9]+%\)->[0-9]+M" | sed "s/.*->//" | sort -n | tail -1)
  echo "heap $heap: idle $idle peak $(sort -h /tmp/m | tail -1) | room $room profile $profile post $post | largest heap after GC $live"
  docker rm -f cf-mem >/dev/null
done

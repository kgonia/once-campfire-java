# req/s in each of the first 20 one-second windows after /up first answers, 16 clients, on a page
# the benchmark requests (room_show) and one it doesn't (a user's profile). Fresh container each.
for round in $(seq ${ROUNDS:-2}); do
  for image in $IMAGES; do
    for page in room "profile"; do
      start cf-warm $image
      [ $page = room ] && path="/rooms/$ROOM" || path="/users/$(label users.jason)"
      line="$image round $round $page: startup ${STARTUP_MS}ms;"
      for s in $(seq 20); do
        line+=" $($LG http --base $B --cookie "$COOKIE" --path "$path" --conc 16 --duration 1 | ruby -rjson -e 'printf "%.0f", JSON.parse(STDIN.read)["rps"]')"
      done
      echo "$line"
      docker rm -f cf-warm >/dev/null
    done
  done
done

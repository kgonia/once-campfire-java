# Pages the benchmark (and so the benchmark-trained cache) never requests, driven the way
# bin/benchmark drives its routes: a fresh container per image and round, 3 s idle, then per route
# 2 s at 4 clients and 8 s at 16. Images alternate order each round. Usage: IMAGES="a b c" ROUNDS=3
for round in $(seq ${ROUNDS:-3}); do
  order=($IMAGES); ((round % 2)) || order=($(printf '%s\n' $IMAGES | tac))
  for image in "${order[@]}"; do
    start cf-unseen $image
    sleep 3
    routes=(other_room "/rooms/$(label rooms.pets)" direct_room "/rooms/$(label rooms.david_and_jason)" search_lunch "/searches?q=lunch"
      permalink "/rooms/$ROOM/@$(label messages.bot_in_watercooler)" profile "/users/$(label users.jason)" account_edit /account/edit
      account_users /account/users room_edit "/rooms/opens/$ROOM/edit" new_direct /rooms/directs/new involvement "/rooms/$ROOM/involvement"
      autocomplete "/autocompletable/users?query=ja" bots /account/bots webmanifest /webmanifest)
    line="$image round $round: startup ${STARTUP_MS}ms;"
    for ((i = 0; i < ${#routes[@]}; i += 2)); do line+=" ${routes[i]} $(hit x "${routes[i+1]}");"; done
    echo "$line"
    docker rm -f cf-unseen >/dev/null
  done
done

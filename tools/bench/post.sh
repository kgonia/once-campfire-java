# Posting alone, 20 s at 16 clients on a fresh container, Rust and the JVM build alternating.
# Usage: ./run.sh "$(cat lib.sh post.sh)"
for round in 1 2 3; do for image in campfire-rust:app once-campfire-java:jvm; do
  start cf-post $image; sleep 3
  echo "$image round $round: post_message $(DUR=20 hit x post)"
  docker rm -f cf-post >/dev/null
done; done

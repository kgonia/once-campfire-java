#!/bin/sh
# Runs a command in the benchmark runner (Dockerfile here): host network, the docker socket and the
# workspace volume `campfire-bench`, mounted at its path on the daemon so the harness's bind mounts
# resolve there too.
#   ./run.sh 'cd once-campfire-verification && bin/benchmark --apps rust ...'
# On Linux, CFB_DOCKER_ARGS="--group-add $(stat -c %g /var/run/docker.sock)" lets it use the socket.
WS=/var/lib/docker/volumes/campfire-bench/_data
export MSYS_NO_PATHCONV=1
exec docker run --rm --network host --group-add 0 \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v campfire-bench:$WS \
  ${CFB_DOCKER_ARGS:-} \
  campfire-bench-runner:local bash -c "$*"

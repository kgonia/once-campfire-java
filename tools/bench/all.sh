#!/bin/bash
# The whole run in docs/benchmarks.md, after build.sh: logs land in the current directory.
# About 80 minutes on a c7a.4xlarge.
here=$(dirname "$0")
export CFB_DOCKER_ARGS="--group-add $(stat -c %g /var/run/docker.sock)"
J=once-campfire-java
ALL="campfire-rust:app $J:jvm $J:aot26 $J:aot28g $J:aot28b $J:native"
echo "== train $(date +%T)"
$here/run.sh "IMAGE=$J:aot28g; TO=$J:aot28b; $(cat $here/lib.sh $here/train.sh)" > train.log 2>&1 || { echo "train failed"; exit 1; }
echo "== bench $(date +%T)"
$here/run.sh "cd once-campfire-verification && bin/benchmark --apps rust,jv-jvm,jv-aot26,jv-aot28g,jv-aot28b,jv-native --rounds 3 --duration 8 --cpus 8-11 --client-cpus 12-15" > bench.log 2>&1
echo "== unseen $(date +%T)"
$here/run.sh "IMAGES=\"$ALL\"; ROUNDS=3; $(cat $here/lib.sh $here/unseen.sh)" > unseen.log 2>&1
echo "== warmup $(date +%T)"
$here/run.sh "IMAGES=\"$ALL\"; ROUNDS=2; $(cat $here/lib.sh $here/warmup.sh)" > warmup.log 2>&1
echo "== all done $(date +%T)"

#!/bin/bash
# Builds the five Java images from this checkout; aot28b is trained from aot28g by train.sh.
cd "$(dirname "$0")/../.."
b() { local tag=$1; shift; echo "== $tag $(date +%T)"; docker build -q -t once-campfire-java:$tag --build-arg APP_VERSION=$tag "$@" . || echo "FAILED $tag"; }
b jvm
b aot26 -f Dockerfile.aot
b aot28g -f Dockerfile.aot --build-arg JDK=28
b native -f Dockerfile.native
echo "== done $(date +%T)"

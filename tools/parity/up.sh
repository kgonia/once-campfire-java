# Starts Rust (4100) and Java (4101) on fresh copies of the seed, sharing reference.env.
# Usage: up.sh [rust|java|both]
set -e
P=${PARITY_WORK:-$(cd "$(dirname "$0")" && pwd)/work}
mkdir -p "$P"
REPO=$(cd "$(dirname "$0")/../.." && pwd)
SEED=${CAMPFIRE_SEED:?set CAMPFIRE_SEED to a bin/seed fixture (db/, storage/, reference.env, labels.json)}
which=${1:-both}
fresh() {
  rm -rf "$P/$1"; mkdir -p "$P/$1/db" "$P/$1/files" "$P/$1/logs"
  cp "$SEED"/db/* "$P/$1/db/"; cp -r "$SEED"/storage/* "$P/$1/files/"
}
if [ "$which" != java ]; then
  docker rm -f cf-rust-parity >/dev/null 2>&1 || true
  fresh rust
  W=$(cygpath -m "$P/rust")
  MSYS_NO_PATHCONV=1 docker run -d --name cf-rust-parity -p 4100:4100 --env-file "$(cygpath -m $SEED/reference.env)" -e HTTP_PORT=4100 \
    -v "$W/db:/rails/storage/db" -v "$W/files:/rails/storage/files" -v "$W/logs:/rails/storage/logs" campfire-rust:app >/dev/null
fi
if [ "$which" != rust ]; then
  powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$(dirname "$0")/kill.ps1")" parity-java
  sleep 1
  fresh java
  set -a; . "$SEED/reference.env"; set +a
  export CAMPFIRE_STORAGE="$P/java" HTTP_PORT=4101
  rm -rf "$P/app"; cp -r "$REPO/target/quarkus-app" "$P/app"
  nohup "${JAVA_HOME:?}/bin/java" -Dparity-java=1 -jar "$P/app/quarkus-run.jar" > "$P/java.log" 2>&1 &
fi
for port in 4100 4101; do
  for i in $(seq 1 60); do curl -s -o /dev/null "http://localhost:$port/up" && break; sleep 1; done
done
echo up

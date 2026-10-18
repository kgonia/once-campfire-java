# Usage: ./run.sh "IMAGE=a; TO=b; $(cat lib.sh train.sh)"
# Records an AOT cache under bin/benchmark's own workload (fixture, routes, loadgen, 4 then 16 clients),
# three passes like three rounds, and bakes it into $TO, built FROM $IMAGE (the same runtime and jar).
mkdir -p $T/out; rm -f $T/out/app.aot; chmod a+rwx $T/out
start cf-train -v $T/out:/out --entrypoint java $IMAGE @/rails/bin/jvm.options -XX:AOTCacheOutput=/out/app.aot \
  -Dquarkus.http.port=25130 -jar /rails/app/quarkus-run.jar
bench_routes
for pass in 1 2 3; do
  for ((i = 0; i < ${#ROUTES[@]}; i += 2)); do printf '%s %s; ' ${ROUTES[i]} "$(hit x "${ROUTES[i+1]}")"; done; echo
done
docker stop -t 600 cf-train >/dev/null
docker logs cf-train 2>&1 | grep -i "aot" | tail -5
docker rm cf-train >/dev/null
ls -l $T/out/app.aot
docker build -q -t $TO -f - $T/out <<EOF
FROM $IMAGE
COPY --chown=1000:1000 app.aot /rails/app/app.aot
EOF

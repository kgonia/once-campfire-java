# Runner for once-campfire-verification's harness and the scripts here (see run.sh). It runs with
# --network host and the docker socket, so loadgen and the app under test share a loopback (on Docker
# Desktop the VM's, which Windows can't reach). The workspace is the volume `campfire-bench`, mounted
# at its own path on the daemon, so the harness's `docker run -v <path>:...` bind mounts resolve to
# the same files.
FROM docker.io/library/rust:1.98.1-trixie AS rust
FROM docker.io/library/docker:cli AS dockercli

FROM docker.io/library/ruby:3.4-trixie
RUN apt-get update -qq && \
    apt-get install --no-install-recommends -y sqlite3 ffmpeg git util-linux procps && \
    rm -rf /var/lib/apt/lists/*
COPY --from=dockercli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=dockercli /usr/local/libexec/docker/cli-plugins/docker-buildx /usr/local/libexec/docker/cli-plugins/docker-buildx
COPY --from=rust /usr/local/cargo /usr/local/cargo
COPY --from=rust /usr/local/rustup /usr/local/rustup
ENV CARGO_HOME=/usr/local/cargo RUSTUP_HOME=/usr/local/rustup PATH=/usr/local/cargo/bin:$PATH
# uid/gid 1000 like the app images' user, so seeded files are owned the way bin/seed expects.
RUN groupadd --gid 1000 bench && useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash bench && \
    chmod -R a+rwX /usr/local/cargo && \
    git config --system --add safe.directory '*'
ENV WS=/var/lib/docker/volumes/campfire-bench/_data
ENV TMPDIR=$WS/tmp
USER 1000:1000
WORKDIR $WS

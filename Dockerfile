# syntax = docker/dockerfile:1
#
# Production image for the Java port (JVM). A drop-in for the reference image: same user (uid 1000),
# working directory, storage layout (/rails/storage/{db,files,backups}), environment and port
# (HTTP_PORT, default 80).
#
#   docker build -t once-campfire-java:app --build-arg APP_VERSION=... --build-arg GIT_REVISION=... .
#
# Media: Debian trixie's libvips-tools (8.16.1-1+deb13u1) and ffmpeg (7:7.1.5-0+deb13u1) are the
# versions the reference image ships, so variants and video posters come out byte-identical.

ARG DEBIAN_RELEASE=trixie

FROM docker.io/library/maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,id=campfire-java-m2,target=/root/.m2 mvn -q -B dependency:go-offline
COPY assets assets
COPY reference reference
COPY src src
RUN --mount=type=cache,id=campfire-java-m2,target=/root/.m2 mvn -q -B package -DskipTests && \
    mv target/quarkus-app /out


FROM docker.io/library/debian:${DEBIAN_RELEASE}-slim

RUN apt-get update -qq && \
    apt-get install --no-install-recommends -y ca-certificates libvips-tools ffmpeg && \
    rm -rf /var/lib/apt/lists /var/cache/apt/archives

COPY --from=docker.io/library/eclipse-temurin:25-jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:$PATH

RUN groupadd --system --gid 1000 rails && \
    useradd rails --uid 1000 --gid 1000 --create-home --shell /bin/bash

WORKDIR /rails
COPY --from=build /out /rails/app

COPY --chmod=755 <<'EOF' /rails/bin/boot
#!/bin/sh
exec java -XX:+UseZGC -XX:MaxRAMPercentage=50 -XX:+UseCompactObjectHeaders --enable-native-access=ALL-UNNAMED \
  -Dquarkus.http.port="${HTTP_PORT:-80}" -jar /rails/app/quarkus-run.jar
EOF

RUN mkdir -p /rails/storage/db /rails/storage/files /rails/storage/backups && \
    chown -R 1000:1000 /rails

USER 1000:1000

ENV RAILS_ENV="production"
ARG APP_VERSION
ENV APP_VERSION=$APP_VERSION
ARG GIT_REVISION
ENV GIT_REVISION=$GIT_REVISION

EXPOSE 80
CMD ["bin/boot"]

FROM clojure:temurin-25-tools-deps-1.12.6.1673-noble@sha256:5122e89fc5d78b60ef345cf3712963530e6362e99599ee7aab0aa4aa8a0dd67b AS dependencies
WORKDIR /build
COPY deps.edn .
RUN clojure -P -M:run
COPY src src
COPY resources resources
COPY deployment/production/build-classpath.sh deployment/production/Healthcheck.java ./
RUN bash build-classpath.sh && javac -d /runtime/health Healthcheck.java

FROM eclipse-temurin:25.0.4.1_1-jre-noble@sha256:3f2223fe04b750aa5a8179e29b5c9a1ddf7665fc9bb08bfe11ad01d143bc13a5
WORKDIR /app
RUN groupadd --gid 10001 tover && useradd --uid 10001 --gid 10001 --no-create-home tover \
    && mkdir -p /var/lib/tover/inventory && chown -R tover:tover /var/lib/tover
COPY --from=dependencies /runtime/ /app/
USER 10001:10001
RUN java --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    -cp "$(cat /app/classpath)" clojure.main -e "(require 'tover.app)"
ENV TOVER_PORT=3030 TOVER_INVENTORY_PATH=/var/lib/tover/inventory
EXPOSE 3030
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=4 \
    CMD ["java", "-cp", "/app/health", "Healthcheck"]
ENTRYPOINT ["sh", "-c", "exec java --enable-native-access=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED -cp \"$(cat /app/classpath)\" clojure.main -m tover.app"]

FROM public.ecr.aws/docker/library/eclipse-temurin:25-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies > /dev/null
COPY src/main src/main
RUN ./gradlew --no-daemon --quiet bootJar \
    && java -Djarmode=tools -jar build/libs/app.jar extract --layers --destination extracted

FROM public.ecr.aws/docker/library/alpine:3.22 AS geo
RUN apk add --no-cache bash coreutils curl
COPY scripts/download-geo-db.sh /usr/local/bin/download-geo-db.sh
RUN download-geo-db.sh /geo/dbip-country-lite.mmdb

FROM public.ecr.aws/docker/library/eclipse-temurin:25-jre-alpine
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app
WORKDIR /app
COPY --from=geo /geo/dbip-country-lite.mmdb geo/dbip-country-lite.mmdb
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"
EXPOSE 8080 8081
HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8081/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

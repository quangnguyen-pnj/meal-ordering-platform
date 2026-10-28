# Java API image (Spring Boot). Build context is the repository root:
#   docker build -f docker/api.Dockerfile -t meal-api .
# Flyway applies the database migrations when the API starts.
FROM eclipse-temurin:25-jdk-noble AS build
WORKDIR /build
COPY apps/api/.mvn .mvn
COPY apps/api/mvnw apps/api/pom.xml ./
COPY apps/api/meal-rules/pom.xml meal-rules/
COPY apps/api/meal-api/pom.xml meal-api/
# Dependencies first, so a code change does not download them again (best effort: a miss only
# means the package step downloads them).
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline -DexcludeReactor=true || true
COPY apps/api/meal-rules/src meal-rules/src
COPY apps/api/meal-api/src meal-api/src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar meal-api/target/meal-api.jar extract --layers --launcher --destination /app

FROM eclipse-temurin:25-jre-noble AS runtime
# curl is only for the container health check.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && useradd --system --uid 10001 --home-dir /app meal
WORKDIR /app
COPY --from=build /app/dependencies/ ./
COPY --from=build /app/spring-boot-loader/ ./
COPY --from=build /app/snapshot-dependencies/ ./
COPY --from=build /app/application/ ./
USER meal
ENV TZ=Asia/Ho_Chi_Minh
EXPOSE 3001
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "org.springframework.boot.loader.launch.JarLauncher"]

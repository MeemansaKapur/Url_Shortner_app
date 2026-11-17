# syntax=docker/dockerfile:1
# ---- Build stage: JDK + Maven cache stay here ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -B -DskipTests package

# ---- Runtime stage: JRE + jar only (smaller image, less attack surface) ----
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --create-home app
COPY --from=build /src/target/url-shortener.jar app.jar
USER app
EXPOSE 8080
# Size the heap from the container's memory limit, not the host's RAM
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

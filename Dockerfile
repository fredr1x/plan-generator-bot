# Multi-stage build so the host (e.g. Render) can build straight from the repo.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/quarkus-app/ ./
# The host injects PORT; the app reads it via quarkus.http.port=${PORT:8080}
ENV JAVA_TOOL_OPTIONS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k -XX:MaxRAMPercentage=70 -Dfile.encoding=UTF-8"
EXPOSE 8080
CMD ["java", "-jar", "quarkus-run.jar"]

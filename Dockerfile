# Railway builds this file directly, so the build JDK and the runtime JRE are pinned to 21
# instead of being chosen by the platform's auto-detection from pom.xml.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
# Source only: .env is excluded by .dockerignore and is never meant to be inside an image.
COPY pom.xml .
COPY src ./src
# Tests need a live database, so they run in CI/on the dev box, not during the image build.
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
# A 35 MB jar in a small Railway container: let the JVM read the cgroup limit instead of guessing.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]

# Builds the home-docs-server (VPS) image. Build context = repo root (needs the parent pom + modules).
# syntax=docker/dockerfile:1

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# Parent pom + both module poms are needed so the reactor can be parsed; -pl server -am builds
# only the server (+ the parent it inherits), not the agent.
COPY pom.xml ./
COPY server server
COPY agent agent
RUN mvn -B -pl server -am -DskipTests clean package

FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
# HEIC support: iPhone photos arrive as HEIC, which we convert to JPEG (via heif-convert) before
# sending them to the vision model — Java's ImageIO cannot read HEIC. libheif-plugin-libde265 is the
# HEVC decoder the conversion needs.
RUN apt-get update \
 && apt-get install -y --no-install-recommends libheif-examples libheif-plugin-libde265 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /app/server/target/home-docs-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

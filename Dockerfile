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
# OCR: native Tesseract + Russian language data. Tess4J loads libtesseract through JNA, which needs
# an unversioned libtesseract.so, so symlink it from the versioned lib the package installs.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-rus \
 && lib="$(find /usr/lib -name 'libtesseract.so.*' | head -1)" && ln -sf "$lib" "${lib%.so.*}.so" \
 && rm -rf /var/lib/apt/lists/*
ENV TESSDATA_PREFIX=/usr/share/tesseract-ocr/5/tessdata
COPY --from=build /app/server/target/home-docs-server-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# Build the reactor with JDK 21 before building this image from the repository root.
ARG JAVA_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${JAVA_IMAGE}
WORKDIR /app
COPY services/document-service/target/document-service-0.0.1-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]

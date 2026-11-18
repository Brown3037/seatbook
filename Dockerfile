FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/seatbook-1.0.0.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

# AgentTrail server — multi-stage build
# Build:  docker build -t agenttrail-server ./server
# Run:    docker run -p 8080:8080 \
#           -e AGENTTRAIL_API_KEY=secret \
#           -e SPRING_PROFILES_ACTIVE=prod \
#           -e SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/agenttrail \
#           -e SPRING_DATASOURCE_USERNAME=agenttrail \
#           -e SPRING_DATASOURCE_PASSWORD=secret \
#           agenttrail-server

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies before copying sources
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/agenttrail-server-0.1.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

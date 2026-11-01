# --- build -------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q package -DskipTests

# --- runtime -----------------------------------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 1001 --no-create-home app
COPY --from=build --chown=1001:1001 /src/target/quarkus-app/ /app/
USER 1001
EXPOSE 8080
ENTRYPOINT ["java", "-Dquarkus.http.host=0.0.0.0", "-jar", "/app/quarkus-run.jar"]

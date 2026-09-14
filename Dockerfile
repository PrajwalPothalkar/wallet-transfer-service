FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline
COPY src ./src
RUN mvn -q -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system wallet \
    && useradd --system --gid wallet --home-dir /app --create-home wallet
WORKDIR /app
COPY --from=build /workspace/target/wallet-transfer-service-1.0.0.jar app.jar
USER wallet
ENV PORT=8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=25"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 CMD ["curl", "--fail", "http://127.0.0.1:8080/actuator/health"]
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

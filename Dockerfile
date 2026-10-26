# Build: the jar, unpacked so the JVM starts from plain classpath entries instead of nested jars.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
# Dependencies get their own layer, so a code-only change doesn't download them again.
RUN ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests \
    && mv target/insight-giver-backend-*.jar app.jar \
    && java -Djarmode=tools -jar app.jar extract --destination /app

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 insight
WORKDIR /app
COPY --from=build /app/ ./
USER insight
EXPOSE 8080
# Sized for a small instance; override or extend with JAVA_TOOL_OPTIONS.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-XX:+ExitOnOutOfMemoryError", "-jar", "app.jar"]

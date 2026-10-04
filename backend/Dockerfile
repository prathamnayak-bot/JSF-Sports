# Trusts any extra root certificates from docker/certs (antivirus HTTPS scanning / company proxies)
# in both the OS store (wget in mvnw) and Java's store (Maven, and the app's HTTPS calls).

# --- build: compile and package with the Maven wrapper (no local Java/Maven needed) ---
FROM eclipse-temurin:21-jdk-alpine AS build
COPY --from=certs . /tmp/certs/
RUN for c in /tmp/certs/*.crt; do [ -f "$c" ] || continue; \
      cp "$c" /usr/local/share/ca-certificates/; \
      keytool -importcert -noprompt -cacerts -storepass changeit -alias "$(basename "$c")" -file "$c"; \
    done; update-ca-certificates >/dev/null 2>&1 || true
WORKDIR /src
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY src/ src/
RUN ./mvnw -B -q package -DskipTests && cp target/*.jar app.jar

# --- run: small JRE image ---
FROM eclipse-temurin:21-jre-alpine
COPY --from=certs . /tmp/certs/
RUN for c in /tmp/certs/*.crt; do [ -f "$c" ] || continue; \
      cp "$c" /usr/local/share/ca-certificates/; \
      keytool -importcert -noprompt -cacerts -storepass changeit -alias "$(basename "$c")" -file "$c"; \
    done; update-ca-certificates >/dev/null 2>&1 || true; rm -rf /tmp/certs
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /src/app.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD wget -qO- http://localhost:8080/actuator/health | grep -q UP || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]

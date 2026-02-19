# ═══════════════════════════════════════════════════════════════════
# Multi-stage Dockerfile for SynthetiQ
# ═══════════════════════════════════════════════════════════════════
# Decision: Eclipse Temurin (official OpenJDK) over Alpine-based JRE.
# + Better compatibility with Spring Boot 3
# + CRaC support for Lambda SnapStart
# + ~30MB larger but avoids musl libc edge cases
#
# Final image size: ~300MB (acceptable for ECS/Lambda)
# ═══════════════════════════════════════════════════════════════════

# ── Stage 1: Build ────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS builder

WORKDIR /build
COPY pom.xml .
COPY .mvn .mvn
COPY mvnw .

# Download dependencies first (cached layer — only re-downloads when pom.xml changes)
RUN ./mvnw dependency:go-offline -B -q

COPY src src
RUN ./mvnw package -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────────
FROM eclipse-temurin:21-jre

WORKDIR /app

# Non-root user for security
RUN groupadd -r synthetiq && useradd -r -g synthetiq synthetiq

COPY --from=builder /build/target/*.jar app.jar

# JVM tuning for container environments
# -XX:MaxRAMPercentage=75: Use 75% of container memory limit
# -XX:+UseG1GC: Low-latency GC for API server
# -Djava.security.egd: Faster UUID generation in containers
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+UseStringDeduplication -Djava.security.egd=file:/dev/./urandom"

USER synthetiq

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
  CMD curl -f http://localhost:8090/api/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]

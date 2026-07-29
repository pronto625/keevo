# ─── Stage 1 : Build ──────────────────────────────────────────────────────────
# Uses the official Maven image with Temurin JDK 21 (Alpine) for a lean build layer.
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /app

# 1. Copy the POM first so Maven can download dependencies into the layer cache.
#    This layer is only invalidated when pom.xml changes — not on every source edit.
COPY pom.xml .
RUN mvn dependency:go-offline --batch-mode --quiet

# 2. Copy sources and build the fat JAR (tests are run in CI, not here).
COPY src ./src
RUN mvn package -DskipTests --batch-mode --quiet


# ─── Stage 2 : Runtime ────────────────────────────────────────────────────────
# Only the JRE — no compiler, no Maven, no sources in the final image.
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Non-root user — principle of least privilege (OWASP A05).
RUN addgroup -S -g 2001 keevo && adduser -S -u 2001 -G keevo keevo

COPY --from=builder /app/target/*.jar app.jar
RUN chown keevo:keevo app.jar

USER keevo

# ── Runtime environment variables ─────────────────────────────────────────────
# All sensitive values MUST be injected at runtime via --env-file or -e flags.
# See backend/.env.example for the full reference list.
#
# Mandatory vars for production:
#   SPRING_PROFILES_ACTIVE=prod
#   POSTGRES_HOST, POSTGRES_PORT, POSTGRES_DB, POSTGRES_USER, POSTGRES_PASSWORD
#   ADMIN_PHONE, ADMIN_PASSWORD
#   APP_PORT (default 4500)
#   GOOGLE_APPLICATION_CREDENTIALS (path to mounted Firebase JSON)
#   KEEVO_FCM_ENABLED, KEEVO_WHATSAPP_PROVIDER, WASSENDER_API_TOKEN, ...
# ──────────────────────────────────────────────────────────────────────────────
ENV SPRING_PROFILES_ACTIVE=prod
ENV APP_PORT=4500

EXPOSE 4500

ENTRYPOINT ["java", \
            "-XX:+UseContainerSupport", \
            "-XX:MaxRAMPercentage=75.0", \
            "-Djava.security.egd=file:/dev/./urandom", \
            "-jar", "app.jar"]

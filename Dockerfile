# Athena: one image with the React app inside the Spring Boot jar, so the
# browser talks to a single origin (no CORS) and gets the backend's security
# headers on every page.
#
#   docker build -t athena .
#   docker compose up            (app + PostgreSQL, see docker-compose.yml)

# 1. Build the React app ---------------------------------------------------
FROM node:26-alpine AS frontend
WORKDIR /frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2. Build the Spring Boot jar with the React app in classpath:/static ------
FROM eclipse-temurin:21-jdk AS backend
WORKDIR /backend
COPY backend/athena/.mvn .mvn
COPY backend/athena/mvnw backend/athena/pom.xml ./
RUN chmod +x mvnw && ./mvnw -B -q dependency:go-offline
COPY backend/athena/src src
COPY --from=frontend /frontend/dist src/main/resources/static
RUN ./mvnw -B -q package -DskipTests

# 3. Run on a JRE-only image as an unprivileged user -----------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system athena && useradd --system --gid athena --uid 10001 athena
WORKDIR /app
COPY --from=backend /backend/target/athena-0.0.1-SNAPSHOT.jar app.jar
USER athena

# Behind a hosting platform's TLS proxy, take the client IP and https scheme
# from X-Forwarded-* (Tomcat trusts those only from private-network proxies).
ENV FORWARD_HEADERS_STRATEGY=native
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

# syntax=docker/dockerfile:1
# Named Dockerfile.java in some Airflow trees — first non-blank line starts with '#'.
FROM eclipse-temurin:17
WORKDIR /app
COPY . .
CMD ["java", "-jar", "app.jar"]

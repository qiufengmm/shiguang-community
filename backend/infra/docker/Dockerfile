FROM eclipse-temurin:17-jre

WORKDIR /app
ARG JAR_FILE
COPY ${JAR_FILE} /app/app.jar

# External classpath configuration also works for Leaf's properties loader.
ENTRYPOINT ["java", "-cp", "/app/config:/app/app.jar", "org.springframework.boot.loader.JarLauncher"]

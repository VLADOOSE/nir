# ---- build stage (собирается на GitHub-раннере, НЕ на сервере) ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace
# сперва только gradle-файлы → слой с зависимостями кешируется отдельно от src
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x ./gradlew && ./gradlew --no-daemon --version
COPY src ./src
# тесты в образе НЕ гоняем (им нужен Postgres) — гейт компиляции = сам bootJar
RUN ./gradlew --no-daemon clean bootJar -x test

# ---- runtime stage (тонкий JRE) ----
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/build/libs/app.jar app.jar
EXPOSE 8080
# кучу задаём в docker-compose (JAVA_OPTS) — на 3.8 ГБ сервере -Xmx1g; дефолт-фолбэк ниже
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

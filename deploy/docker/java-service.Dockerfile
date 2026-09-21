# Shared image for the Java services. Build the jars first: (cd risk-platform && mvn -B package -DskipTests)
#
# JDK base (not JRE) on purpose for this portfolio setup: jcmd/jfr are needed for the Java performance and
# troubleshooting lab (thread dumps, heap histograms, JFR). Production: JRE image + ephemeral debug container
# (kubectl debug) for diagnostics — smaller attack surface.
FROM eclipse-temurin:21-jdk
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/* \
    && groupadd --system app && useradd --system --gid app --home /app app && mkdir -p /app /dumps && chown app:app /app /dumps
WORKDIR /app
ARG JAR_FILE
COPY --chown=app:app ${JAR_FILE} /app/app.jar
USER app
# Container-aware heap sizing; fail fast on OOM (the orchestrator restarts) and keep a heap dump for analysis.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:InitialRAMPercentage=50 -XX:+UseG1GC -XX:MaxGCPauseMillis=50 \
 -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/dumps \
 -Xlog:gc*:file=/dumps/gc.log:time,uptime,level,tags:filecount=5,filesize=10m"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]

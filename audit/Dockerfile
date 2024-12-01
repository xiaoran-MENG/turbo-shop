FROM eclipse-temurin:21-jdk-alpine
VOLUME /tmp
ARG DEPENDENCY=build/libs
COPY ${DEPENDENCY}/BOOT-INF/lib /app/lib
COPY ${DEPENDENCY}/META-INF /app/META-INF
COPY ${DEPENDENCY}/BOOT-INF/classes /app
EXPOSE 9090
ENTRYPOINT ["java", "-cp", "app:app/lib/*", "com.shop.audit.AuditApplication"]
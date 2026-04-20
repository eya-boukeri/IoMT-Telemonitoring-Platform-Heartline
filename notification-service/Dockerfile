FROM eclipse-temurin:17-jre

WORKDIR /app

COPY target/notification-service-1.0.0.jar app.jar

EXPOSE 9095

ENTRYPOINT ["java", "-jar", "app.jar"]
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/delivery-lab-*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

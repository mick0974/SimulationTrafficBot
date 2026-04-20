# Build stage
FROM maven:3.8.8-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Run stage
FROM eclipse-temurin:21-jdk-alpine
WORKDIR /App
COPY --from=build /build/target/*.jar BackgroundTrafficBot.jar
ENTRYPOINT ["java","-jar","-Dspring.profiles.active=prod","BackgroundTrafficBot.jar"]
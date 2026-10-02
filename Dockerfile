# Java services (gateway + load generator). Build context: repository root.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY iso20022-model/pom.xml iso20022-model/
COPY clearing-gateway/pom.xml clearing-gateway/
COPY load-generator/pom.xml load-generator/
RUN ./mvnw -B -q -pl clearing-gateway,load-generator -am dependency:go-offline -DexcludeReactor=true || true
COPY iso20022-model iso20022-model
COPY clearing-gateway clearing-gateway
COPY load-generator load-generator
RUN ./mvnw -B -q -pl clearing-gateway,load-generator -am package -DskipTests

FROM eclipse-temurin:21-jre AS gateway
WORKDIR /app
COPY --from=build /src/clearing-gateway/target/clearing-gateway-*.jar app.jar
USER 65534
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]

FROM eclipse-temurin:21-jre AS loadgen
WORKDIR /app
COPY --from=build /src/load-generator/target/load-generator-*.jar loadgen.jar
ENTRYPOINT ["java", "-jar", "/app/loadgen.jar"]

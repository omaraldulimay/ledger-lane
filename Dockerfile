FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN ./mvnw -B -ntp dependency:go-offline
COPY src src
RUN ./mvnw -B -ntp -DskipTests package && mv target/ledger-lane-*.jar /src/app.jar

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home ledger
WORKDIR /app
COPY --from=build /src/app.jar app.jar
USER ledger
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

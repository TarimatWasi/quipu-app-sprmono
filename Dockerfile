# Imagen de despliegue (Render no tiene runtime nativo de Java). Etapa 1 compila con Maven; etapa 2
# corre el jar con un JRE mínimo. Tags fijados por digest: Dependabot (ecosistema docker) los
# actualiza. La calidad (Spotless, Checkstyle, JaCoCo, pruebas) la verifica el CI, no esta imagen.
FROM maven:3.9-eclipse-temurin-26@sha256:b2c1ad85954592f9928e84327c65201f308ad9b5d8ed7d5b823717c97bf23fbb AS build
WORKDIR /build
COPY .mvn .mvn
COPY pom.xml ./
COPY src src
# La imagen no corre pruebas: no necesita bajar el spec del contrato (TAR-23).
RUN mvn -B -Dmaven.test.skip=true -Ddownload.plugin.skip=true package

FROM eclipse-temurin:25-jre-alpine@sha256:3c0a9084927a221ccd1d007fcaf614465672c0af37aaa834c5184483afe56d61
RUN addgroup -S app && adduser -S -G app app
USER app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/quipu-app-sprmono-*.jar app.jar

# Valor por defecto para Render gratis (512 MB); sobrescribible desde la variable del servicio.
ENV JAVA_TOOL_OPTIONS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:TieredStopAtLevel=1"

# El puerto lo fija la variable PORT (application-dev.yml y application-prod.yml).
ENTRYPOINT ["java", "-jar", "app.jar"]

# 多阶段构建:克隆即跑(docker build 从源码到镜像)
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY yingzao-core yingzao-core
COPY addons addons
COPY yingzao-app yingzao-app
RUN mvn -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/yingzao-app/target/yingzao-app-*.jar /app/yingzao.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/yingzao.jar"]

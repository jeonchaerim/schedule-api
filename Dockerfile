# 1단계: 빌드 (Gradle Wrapper로 프로젝트가 지정한 Gradle 버전을 그대로 사용)
FROM eclipse-temurin:17-jdk-jammy AS build
WORKDIR /app

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# 2단계: 실행 (빌드 도구 없이 jar만 실행)
# jre-alpine은 patch 버전에 따라 arm64 매니페스트가 빠질 때가 있어, 멀티 아키텍처가 안정적인 jammy 사용
FROM eclipse-temurin:17-jre-jammy
WORKDIR /app

# 헬스체크(docker-compose)용 — jammy 기본 이미지에는 curl이 없어 별도 설치
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]

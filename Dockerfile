# syntax=docker/dockerfile:1
# Build: docker build -t aiv-gate:local .
# Run:  docker run --rm -v "%cd%:/workspace" -w /workspace aiv-gate:local --diff origin/main
FROM eclipse-temurin:17-jre-jammy

ARG AIV_VERSION=1.0.4
ENV AIV_VERSION=${AIV_VERSION}

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates git \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/aiv \
    && curl -fsSL -o /opt/aiv/aiv-cli.jar \
      "https://repo1.maven.org/maven2/io/github/vaquarkhan/aiv/aiv-cli/${AIV_VERSION}/aiv-cli-${AIV_VERSION}.jar"

WORKDIR /workspace
ENTRYPOINT ["java", "-jar", "/opt/aiv/aiv-cli.jar"]
CMD ["--diff", "HEAD~1", "--head", "HEAD"]

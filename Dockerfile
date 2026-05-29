
FROM registry.access.redhat.com/ubi8/openjdk-21 AS builder

# Install required packages for build
USER root
RUN microdnf install -y gzip tar curl && microdnf clean all

# Set SBT version
ARG SBT_VERSION=1.10.11
ARG SCALA_VERSION=3.8.2
ENV SBT_VERSION=${SBT_VERSION}
ENV SCALA_VERSION=${SCALA_VERSION}
# Install SBT
RUN curl -fsL https://github.com/sbt/sbt/releases/download/v$SBT_VERSION/sbt-$SBT_VERSION.tgz | tar xz -C /opt/

# Add SBT to PATH
ENV PATH="/opt/sbt/bin:$PATH"

# Set working directory
WORKDIR /app

# Copy build files first (for better caching)
COPY build.sbt ./
COPY project/ ./project/
COPY entrypoint.sh entrypoint.sh
COPY src/ ./src/
COPY .jvmopts ./

RUN sbt 'set assembly / test := {}' clean assembly

# Runtime stage
FROM registry.access.redhat.com/ubi8/openjdk-21-runtime

# Set working directory
WORKDIR /app

# Copy the assembled JAR from builder stage
COPY --from=builder /app/target/scala-3.7.1/wxo-embedded-chat-assembly-1.0.0.jar ./app.jar
COPY --from=builder /app/entrypoint.sh ./entrypoint.sh
USER root
RUN groupadd -r appuser && useradd -r -g appuser appuser
RUN chown -R appuser:appuser /app
RUN chown -R appuser:appuser /app/entrypoint.sh
RUN chmod +x /app/entrypoint.sh

# Switch to non-root user
USER appuser

# Expose port
EXPOSE 8080

# Run the application
ENTRYPOINT ["./entrypoint.sh"]

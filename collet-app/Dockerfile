ARG COLLET_VERSION=""
ARG COLLET_REVISION="docker-build"

FROM clojure:temurin-21-tools-deps AS builder

ARG COLLET_VERSION
ARG COLLET_REVISION

WORKDIR /build

COPY LICENSE /build/LICENSE
COPY deps.edn /build/deps.edn
COPY build /build/build
COPY collet-core /build/collet-core
COPY collet-app /build/collet-app

RUN if [ -n "${COLLET_VERSION}" ]; then \
      clojure -T:build install :module :collet-core \
        :expected-version "\"${COLLET_VERSION}\"" \
        :source-revision "\"${COLLET_REVISION}\""; \
    else \
      clojure -T:build install :module :collet-core \
        :source-revision "\"${COLLET_REVISION}\""; \
    fi
RUN if [ -n "${COLLET_VERSION}" ]; then \
      clojure -T:build build :module :collet-app \
        :expected-version "\"${COLLET_VERSION}\"" \
        :source-revision "\"${COLLET_REVISION}\""; \
    else \
      clojure -T:build build :module :collet-app \
        :source-revision "\"${COLLET_REVISION}\""; \
    fi


FROM eclipse-temurin:21.0.5_11-jre

ARG COLLET_VERSION
ARG COLLET_REVISION

LABEL org.opencontainers.image.version="${COLLET_VERSION}" \
      org.opencontainers.image.revision="${COLLET_REVISION}"

ENV JMX_PORT=8080
ENV PIPELINE_SPEC="{}"
ENV PIPELINE_CONFIG="{}"
ENV TINI_VERSION=v0.19.0

# for local builds
#RUN mkdir -p /root/.m2/repository/io
#ADD velio /root/.m2/repository/io/velio

RUN mkdir -p /app
COPY --from=builder /build/collet-app/target/collet.jar /app/collet.jar

RUN mkdir /app/jmx
COPY collet-app/resources/jmx_prometheus_javaagent-0.20.0.jar /app/jmx/jmx_prometheus_javaagent-0.20.0.jar
COPY collet-app/resources/jmx.yaml /app/jmx/jmx.yaml


ARG TARGETPLATFORM
ARG TINI_ARCH

RUN echo "Building for ${TARGETPLATFORM}" && \
    case ${TARGETPLATFORM} in \
         "linux/amd64")  TINI_ARCH=amd64  ;; \
         "linux/arm64")  TINI_ARCH=arm64  ;; \
         "linux/arm/v7") TINI_ARCH=armhf  ;; \
         "linux/arm/v6") TINI_ARCH=armel  ;; \
         "linux/386")    TINI_ARCH=i386   ;; \
    esac && \
    wget -O /tini https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini-${TINI_ARCH}

RUN chmod +x /tini

WORKDIR /app

ENTRYPOINT ["/tini", "--"]

CMD java \
-XX:+UseContainerSupport -XX:InitialRAMPercentage=40.0 -XX:MaxRAMPercentage=80.0 \
--add-exports=java.base/jdk.internal.ref=ALL-UNNAMED \
--add-exports=java.base/sun.nio.ch=ALL-UNNAMED \
--add-exports=jdk.unsupported/sun.misc=ALL-UNNAMED \
--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED \
--add-opens=jdk.compiler/com.sun.tools.javac=ALL-UNNAMED \
--add-opens=java.base/java.lang=ALL-UNNAMED \
--add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
--add-opens=java.base/java.io=ALL-UNNAMED \
--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.nio=org.apache.arrow.memory.core,ALL-UNNAMED \
--enable-native-access=ALL-UNNAMED \
-javaagent:/app/jmx/jmx_prometheus_javaagent-0.20.0.jar=$JMX_PORT:/app/jmx/jmx.yaml \
-jar collet.jar -s "${PIPELINE_SPEC}" -c "${PIPELINE_CONFIG}"

FROM clojure:temurin-21-lein AS builder

RUN mkdir -p /build
COPY project.clj /build/project.clj
COPY src /build/src

# for local builds
#RUN mkdir -p /root/.m2/repository/io
#ADD  velio /root/.m2/repository/io/velio

RUN cd /build && lein uberjar


FROM eclipse-temurin:21.0.4_7-jdk

ENV JMX_PORT=8080
ENV PIPELINE_SPEC="{}"
ENV PIPELINE_CONFIG="{}"
ENV TINI_VERSION=v0.19.0

# for local builds
#RUN mkdir -p /root/.m2/repository/io
#ADD  velio /root/.m2/repository/io/velio

RUN mkdir -p /app
COPY --from=builder /build/target/collet.jar /app/collet.jar

RUN mkdir /app/jmx
COPY resources/jmx_prometheus_javaagent-0.20.0.jar /app/jmx/jmx_prometheus_javaagent-0.20.0.jar
COPY resources/jmx.yaml /app/jmx/jmx.yaml

ADD https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini /tini
RUN chmod +x /tini

WORKDIR /app

ENTRYPOINT ["/tini", "--"]

CMD java \
-XX:+UseContainerSupport -XX:InitialRAMPercentage=40.0 -XX:MaxRAMPercentage=80.0 \
-javaagent:/app/jmx/jmx_prometheus_javaagent-0.20.0.jar=$JMX_PORT:/app/jmx/jmx.yaml \
-jar collet.jar -s "${PIPELINE_SPEC}" -c "${PIPELINE_CONFIG}"
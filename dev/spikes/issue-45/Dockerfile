FROM clojure:temurin-25-tools-deps@sha256:35cf29b5b187adf44175337ea1657cd9561578b53d06c53b9d0e7c2e083dd82c

WORKDIR /workspace

# The build context is the repository root.  Nothing from this image is a
# Collet artifact: it only resolves the isolated spike project below.
COPY . .

WORKDIR /workspace/dev/spikes/issue-45

# Resolve the Arrow 19 main process and immutable DuckTape comparison while
# the image build has network access.
RUN clojure -P -M:run:ducktape

# Lance Java declares Arrow 18.3.0. Resolve its isolated helper runtime without
# merging it into the Arrow 19 process. The forced-19 alias is diagnostic only.
RUN clojure -P -M:lance-java-aligned
RUN clojure -P -M:lance-java-forced-arrow19

# Fetch DuckDB core extensions and DuckTape's matching native DuckDB library.
# The runtime check is deliberately executed with --network none by
# `bb spike:45 docker-check`.
RUN clojure -M:run:ducktape prepare-native --output-dir target/spike-45

ENTRYPOINT ["clojure", "-J-Dclojure.main.report=stderr", "-M:run:ducktape"]
CMD ["check", "--rows", "32", "--embedding-width", "8", "--batch-rows", "8"]

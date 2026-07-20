### Install Collet CLI

Download the tar file from the latest release and extract it.

```shell
# Using curl:
curl -L -o collet-cli.tar.gz https://github.com/velio-io/collet/releases/latest/download/collet-cli.tar.gz

# Or using wget:
wget -O collet-cli.tar.gz https://github.com/velio-io/collet/releases/latest/download/collet-cli.tar.gz
```

```shell
tar -xzvf collet-cli.tar.gz
```

Add the executable file to your PATH environment variable.

```shell
# If you are using bash shell
export PATH=$PATH:/path/to/collet-cli

# If you are using fish shell
fish_add_path /path/to/collet-cli
```

Now you can use the Collet as a CLI tool.

```shell
collet.bb -s "path/to/pipeline-spec.edn" -c "path/to/config.edn" -x "path/to/context.edn"
```

This command will show the prompt to select the action you want to perform.

`collet.bb` options:

- `-s` or `--spec`: Path to the pipeline spec file.
- `-c` or `--config`: Path to the config file.
- `-x` or `--context`: Path to the context file. You can put some values here to represent the state that is required in
  order to execute specific action or task.

You can modify all three files and re-run actions from the prompt (files will be reloaded).

Available prompt actions are:

- run action: run a single action from your pipeline spec
- run task: run the single pipeline task
- run pipeline: run the whole pipeline
- show spec: show the pipeline spec
- open portal view: opens a standalone portal view where you can track and debug all tasks and actions

### Deploy the script

From the repository root, build the pod uberjar and distribution archive:

```shell
bb build collet-cli
```

This produces `collet-cli/target/collet.pod.jar` and
`collet-cli/target/collet-cli.tar.gz`. The archive contains the `collet-cli/`
directory with `bb.edn`, executable `collet.bb`, `collet.pod.jar`, and executable
`gum`. The pod main namespace remains `pod.collet.core`.
After a coordinated Maven release creates `v<version>`, create the GitHub release
from that same tag and upload this file manually. `bb release` publishes Maven
artifacts only; it neither creates the CLI GitHub release nor pushes the Docker
image.

### Development

Building requires JDK 21 or newer, Clojure CLI, and Babashka. Run the pod artifact
startup test with:

```shell
bb test:module collet-cli
```

When you are developing the collet-cli, you can use the following command to run the CLI.

```shell
bb --config ./collet-bb-deps.edn -f collet.bb -s /path/to/spec.edn -c /path/to/config.edn
```

In case you need a REPL to the babashka process

```shell
bb --config "./collet-bb-deps.edn" nrepl-server 1667
```

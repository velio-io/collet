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

Build the uberjar file for collet pod and pack it as a tar file.

```shell
bb build
```

This will produce a `target/collet-cli.tar.gz` file.
You can upload this file to GitHub releases.
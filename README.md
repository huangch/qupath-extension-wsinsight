# qupath-extension-wsinsight

QuPath 0.7 extension that exposes the [WSInsight](https://github.com/huangch/wsinsight) CLI as a graphical tool. Each menu entry is a small form that collects arguments for one WSInsight subcommand and launches the `huangchtw/wsinsight:latest` Docker container with the right bind mounts, GPU flags, and environment variables. Slides to process are picked automatically from the currently-open image or the active QuPath project; results land in a scratch directory and are imported back into the project on success.

The forms themselves are **auto-generated** from `src/main/resources/wsinsight-cli-schema.json`, which is produced by `wsinsight describe` and merged with QuPath-only GUI hints (command groups, dialog buttons, column breaks). When the WSInsight CLI gains, drops, or renames an option, regenerate this schema (see [Developer notes](#developer-notes)) and the QuPath dialog updates without any Java changes.

## Requirements

- QuPath **0.7.0**.
- A working `docker` CLI on the host (Linux or macOS recommended).
- The NVIDIA Container Toolkit, if you want GPU acceleration.
- The WSInsight image pulled once:
  ```bash
  docker pull huangchtw/wsinsight:latest
  ```

## Build

```bash
cd qupath-extension-wsinsight
./gradlew clean jar
```

The jar lands in `build/libs/qupath-extension-wsinsight-0.1.0.jar`. Drop it into QuPath's `extensions/` directory and restart QuPath. All runtime dependencies (QuPath, JavaFX, slf4j) are provided by the host application.

## Configure

`Edit > Preferences > WSInsight` exposes:

| Preference | Purpose |
| --- | --- |
| Docker binary / image | `docker` executable and `huangchtw/wsinsight:latest` tag |
| GPUs | Value for `docker --gpus` (`all`, `none`, `device=0,1`, …) |
| Shared memory size | `docker --shm-size` (default `32g`) |
| Extra mounts | Additional `host:container` bind-mount pairs, separated by commas, semicolons, or newlines |
| WSInsight zoo registry path | Sets `WSINSIGHT_ZOO_REGISTRY_PATH` inside the container |
| S3 storage options (JSON) | Sets `S3_STORAGE_OPTIONS` |
| Remote cache directory | Sets `WSINSIGHT_REMOTE_CACHE_DIR` |
| `KERAS_HOME` | Sets the Keras cache directory |
| Auto-import results | Load `*.geojson` and `*.ome.csv` back into the project on success |

The `/slides` and `/results` bind mounts are derived automatically from the scope chosen in each command dialog (see **Run** below). Results land under `<project>/wsinsight-runs/<subcommand>-<timestamp>/` when a project is open, so they travel with the project and can be re-inspected later; in single-image mode (no project) a fresh scratch directory under the system temp folder is used instead.

## Run

`Extensions > WSInsight >` lists one entry per WSInsight CLI subcommand:

- **Run** — one-shot `patch → infer → hplot → ncomp → ecomp → tcomp → niche → export` pipeline
- **Patch**, **Infer**, **Region registration**
- **H-plot**, **H-plot finalize**
- **Neighborhood / Edge / Triad composition**
- **Niche discovery**, **Niche profile**, **Cell-type aggregates**, **Import spatial transcriptomics**
- **Export GeoJSON / OME-CSV**

Each action opens a form pre-populated with sensible defaults. At the top of the form, a **Process** radio group lets you choose the slide scope:

- **Current image** — run on whichever slide is open in the viewer.
- **All project images** — run on every image in the active QuPath project.
- **Selected project images…** — pick a subset from a checkbox list.

If no image is open and no project is loaded, the dialog shows a "No image available" error and does not launch. Path fields inside the form accept host paths; the extension rewrites them into container paths via the configured bind mounts before invoking `wsinsight`. Host paths that are not covered by any mount raise a clear error before the container is launched — add them under **Extra mounts** in Preferences.

## Scope (v0.1)

- **Backend**: local Docker only.
- **Slide selection**: current image, all project images, or a selected subset.
- **OS**: tested on Linux and macOS. Windows is untested (`--user $(id -u)` is skipped).

## Developer notes

### Refreshing the bundled CLI schema

The dialog forms are driven entirely by `src/main/resources/wsinsight-cli-schema.json`. This file is the canonical `wsinsight describe` output **augmented** with QuPath-only GUI hints:

| Where it comes from | What it carries |
| --- | --- |
| `wsinsight describe` (canonical) | Commands, params, defaults, `kind`, `help`, `required`, choices |
| Manually maintained in this repo | Per-command `groups` block (dialog buttons, titles, `visible_when`); per-param `group` and `column_break` layout hints |

The Gradle task `syncCliSchema` regenerates the bundled schema, preserving the GUI hints:

```bash
# default — assumes wsinsight repo at ../wsinsight and a `python3` on PATH
./gradlew syncCliSchema

# typical case — wsinsight installed in a conda env
./gradlew syncCliSchema -PwsinsightPython="conda run -n wsinsight python"

# point at a non-default wsinsight checkout
./gradlew syncCliSchema -PwsinsightRepo=/path/to/wsinsight
```

You can persist the properties in `~/.gradle/gradle.properties`:

```properties
wsinsightPython=conda run -n wsinsight python
wsinsightRepo=/path/to/wsinsight
```

Internally the task runs `python -m wsinsight describe` against the wsinsight source tree, then invokes `scripts/sync_cli_schema.py` to merge the canonical output with the existing bundled schema. Any param removed upstream that still carried a manual GUI hint is reported as a warning so the hint can be relocated.

After a refresh, `./gradlew build` confirms the schema parses cleanly and the form generator still works.

## License

Apache 2.0 — see [`LICENSE`](LICENSE).

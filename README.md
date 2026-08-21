# qupath-extension-wsinsight

QuPath 0.7 extension that exposes the [WSInsight](https://github.com/huangch/wsinsight) CLI as a graphical tool. Each menu entry is a small form that collects arguments for one WSInsight subcommand and launches the `huangchtw/wsinsight:latest` Docker container with the right bind mounts, GPU flags, and environment variables. Slides to process are picked automatically from the currently-open image or the active QuPath project; results land in a scratch directory and are imported back into the project on success.

The forms themselves are **auto-generated** from the CLI schema that `wsinsight describe --output <path>` writes. The extension reads that file at startup — it never generates or re-validates it, since the CLI is the only thing that should produce it. The same file also supplies the `--model` dropdown, so the model list always reflects the environment that will actually run inference.

## First run

Generate the schema once, then point the extension at it:

```bash
mkdir -p ~/.wsinsight
wsinsight describe --output ~/.wsinsight/cli-schema.json
```

Regenerate it whenever the CLI or the model zoo changes, then use `Extensions > wsinsight > Reload CLI schema` — no QuPath restart needed.

If you drive wsinsight through Docker, generate it from inside the image so the models match:

```bash
docker run --rm -v ~/.wsinsight:/out huangchtw/wsinsight:latest \
    wsinsight describe --output /out/cli-schema.json
```

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
| Extra mounts | Additional `host:container` bind-mount pairs, separated by commas, semicolons, or newlines. Append `:ro` for read-only |
| CLI schema path | File written by `wsinsight describe --output` (default `~/.wsinsight/cli-schema.json`) |
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
| `wsinsight describe` (canonical) | Commands, params, defaults, `kind`, `help`, `required`, choices, and the zoo `models` list |

The schema is **not** bundled in the jar and there is no Gradle task to sync or verify it: a second copy could only ever drift from the first. Regenerate it with the CLI:

```bash
wsinsight describe --output ~/.wsinsight/cli-schema.json
```

`wsinsight_version` is recorded in the file and shown on reload, so a schema generated by a different wsinsight version than the image in use is easy to spot.

## License

Apache 2.0 — see [`LICENSE`](LICENSE).

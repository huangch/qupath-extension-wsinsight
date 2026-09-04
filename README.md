# qupath-extension-wsinsight

QuPath 0.7 extension that exposes the [WSInsight](https://github.com/huangch/wsinsight)
CLI as a graphical tool. Each menu entry is a form that collects arguments for one
WSInsight subcommand and runs it, either in the `huangchtw/wsinsight:latest` Docker
container or through a `wsinsight` installed on the host. Slides are taken from the
open image or the active QuPath project; results land in a scratch directory and
are imported back into the project on success.

The forms are **auto-generated** from the CLI schema that
`wsinsight schema --output <path>` writes. The extension reads that file at
startup — it never generates or re-validates it, since the CLI is the only thing
that should produce it. The same file supplies the `--model` dropdown, so the
model list always reflects the environment that will run inference.

## First run

The extension needs the CLI schema before it can build any form. Generate it
from whichever wsinsight will do the work, so the reported models match.

Native:

```bash
mkdir -p ~/.wsinsight
wsinsight schema --output ~/.wsinsight/cli-schema.json
```

Docker:

```bash
mkdir -p ~/.wsinsight
docker run --rm -v ~/.wsinsight:/out huangchtw/wsinsight:latest \
    wsinsight schema --output /out/cli-schema.json
```

Regenerate it whenever the CLI or the model zoo changes, then use
`Extensions > wsinsight > Reload CLI schema` — no QuPath restart needed. The
schema records `wsinsight_version`, which is shown on reload, so a stale one is
easy to spot.

Reloading then offers to reset the parameters the dialogs remember. That is
offered rather than done because those values are your own input, while a
reload is usually just about picking up a new model; answering **No** still
reloads the schema.

## Requirements

- QuPath **0.7.0**.
- Either backend:
  - **Docker** (default) — a working `docker` CLI, plus the NVIDIA Container
    Toolkit for GPU acceleration, and the image pulled once:
    ```bash
    docker pull huangchtw/wsinsight:latest
    ```
  - **Native** — a `wsinsight` on `PATH` (or an absolute path to it), in an
    environment where it already runs from a terminal.
- Linux or macOS recommended.

## Build

```bash
cd qupath-extension-wsinsight
./gradlew clean jar
```

The jar lands in `build/libs/qupath-extension-wsinsight-0.1.0.jar`. Drop it into
QuPath's `extensions/` directory and restart QuPath. All runtime dependencies
(QuPath, JavaFX, slf4j) are provided by the host application.

Run the tests with `./gradlew test`.

## Configure

`Edit > Preferences > wsinsight` exposes:

| Preference | Applies to | Purpose |
| --- | --- | --- |
| Use native wsinsight | both | Run a host-installed `wsinsight` instead of the container |
| Native wsinsight binary | native | Executable to run (default `wsinsight`, resolved on `PATH`) |
| Docker binary / image | docker | `docker` executable and `huangchtw/wsinsight:latest` tag |
| GPUs | docker | Value for `docker --gpus` (`all`, `none`, `device=0,1`, …) |
| Shared memory size | docker | Value for `docker --shm-size` (default `32g`) |
| Detected GPUs | docker | Cached `nvidia-smi -L` output, refreshed at startup when the image is present |
| WSI backend | both | Library used to read slides, passed as `wsinsight --backend`. `auto` lets wsinsight pick whichever is installed |
| CLI schema path | both | File written by `wsinsight schema --output` (default `~/.wsinsight/cli-schema.json`) |
| Use local model files | both | On: pass `--zoo-model-dir` from the path in the schema. Off: pass `--model` and download from HuggingFace |
| Model zoo registry | both | `WSINSIGHT_ZOO_REGISTRY_PATH`. Blank keeps the Docker image's bundled registry, or whatever a native run inherits |
| Keras home | both | `KERAS_HOME`, where StarDist weights are found. Blank means inherit |
| Hugging Face cache | both | `HF_HOME`. Blank means inherit |
| Fast Hugging Face downloads | both | Sets `HF_HUB_ENABLE_HF_TRANSFER=1`, which needs the `hf_transfer` package |
| Export GeoJSON detections | both | Initial state of the GeoJSON checkbox. GeoJSON is the only format this extension imports |
| Import results when a run finishes | both | Import the detections as soon as a run exits successfully. Requires GeoJSON export |
| Overwrite existing results | both | Initial state of `--overwrite`: recompute slides that already have outputs |
| S3 storage options (JSON) | both | Sets `S3_STORAGE_OPTIONS` |
| Remote cache directory | both | Host cache for slides streamed from S3/GDC. In Docker mode it must sit under the slides or results directory |
| Enable experimental features | both | Show the experimental subcommands and flags (see **Menu** below) |

Results land under `<project>/wsinsight-runs/<subcommand>-<timestamp>/` when a
project is open, so they travel with the project; in single-image mode a fresh
directory under the system temp folder is used instead.

### Choosing a backend

In **Docker** mode the slides and results directories are bind-mounted as
`/slides` and `/results`, and path arguments are rewritten to match. A path
outside those two directories cannot be seen by the container, and the dialog
says so before launching rather than failing inside the run.

In **native** mode nothing is mounted or rewritten: arguments are passed to
`wsinsight` exactly as typed, so any readable path works. GPU visibility, shared
memory and the model cache are whatever the host shell already provides, so the
Docker-specific preferences (image, GPUs, shared memory) are ignored. The
extension checks `wsinsight --version` first and reports a clear error if the
executable cannot be run.

## Menu

`Extensions > wsinsight >` lists one entry per subcommand, in this order:

| Entry | Command | Experimental |
| --- | --- | --- |
| Run inference… | `run` — the one-shot `patch → infer → … → export` pipeline | |
| Patch extraction… | `patch` | |
| Inference… | `infer` | |
| Region registration… | `reg` | |
| Neighborhood composition… | `ncomp` | |
| Edge composition… | `ecomp` | yes |
| Triad composition… | `tcomp` | yes |
| Niche discovery… | `niche` | yes |
| Niche profile… | `niche-profile` | yes |
| Cell-type aggregates… | `agg` | yes |
| Import spatial transcriptomics… | `import` | yes |
| H-Plot analysis… | `hplot` | yes |
| H-Plot finalize… | `hplot-finalize` | yes |
| Export results… | `export` | |
| _— separator —_ | | |
| Import results… | _(no subcommand; uses `AutoImport`)_ | |
| Reload CLI schema | _(no subcommand; re-reads `cli-schema.json`)_ | |

Entries marked experimental are hidden unless **Enable experimental features** is
on, matching the subcommands the CLI itself hides behind `WSINSIGHT_EXPERIMENTAL`.
With the preference off the menu is just Run, Patch, Inference, Region
registration, Neighborhood composition and Export. Toggling it shows and hides
the entries live; no restart is needed.

**Import results…** is the standalone way to push GeoJSON outputs back into the
active project. Runs no longer auto-import on success — see [Import
results](#import-results) below.

## Run

Each action opens a form pre-populated with the CLI's own defaults. At the top, a
**Process** radio group chooses the slide scope:

- **Current image** — run on whichever slide is open in the viewer.
- **Selected project images…** — pick a subset from a checkbox list, which has
  **All** and **None** buttons.

Every value the form shows is sent verbatim, so the dialog always states what
the run will do. A field starts from the value you last used in this project,
falling back to the schema default; the GeoJSON export and overwrite checkboxes
start from their preferences instead. Clearing a checkbox whose CLI default is
on sends the negated flag (`--no-pin-memory`), because omitting it would leave
the default in force.

If no image is open and no project is loaded, the dialog reports "No image
available" and does not launch.

The **results directory** is left for the user to fill in. The same scope
(same images) is commonly run several times with different parameters, each
wanting its own folder, so the extension never auto-creates one for the run
dialogs. Leave the field blank to let the extension allocate a fresh
timestamped folder under the project.

Below the scope radio, a **Chain from previous run** subsection is collapsed by
default; ticking it reveals the optional `--region-inference-dir` and
`--object-inference-dir` fields that point at a previous wsinsight run's
results. This keeps the canonical workflow uncluttered while still supporting
`run → run` chaining.

A **External inputs** collapsible section lives at the bottom of the dialog
(also collapsed by default) and surfaces optional directories consumed from
external tools such as HistoQC's slide QC outputs. Most users never open it.

Path fields accept host paths. In Docker mode they are rewritten into container
paths; a path outside the slides and results directories raises a clear error
*before* the container starts. Native mode passes them through unchanged.

### Form layout

Options whose flags share a prefix (`--niche-*`, `--hplot-*`, …) are collected
into collapsible sections below the main form, so the dialog opens at a workable
size. Where the CLI has a matching switch — `--ncomp` for the `ncomp` section —
that checkbox is shown on the section header, so a feature can be enabled without
expanding it. Everything else stays in the two-column main form.

### Progress window

Container output streams into a log window while the job runs. Carriage returns
redraw the current line, so a tqdm progress bar updates in place instead of
adding one line per tick, and ANSI escape sequences are removed. **Cancel** stops
the run with `docker kill`.

## Import results

`Extensions > wsinsight > Import results…` walks any WSInsight results
directory you point it at and pushes the `*.geojson` annotations it finds back
into the matching QuPath image(s). It is fully decoupled from the run dialogs —
runs never auto-import anymore, so you can step through `patch → infer → hplot →
niche`, then trigger a single import at the end of the chain.

The dialog asks for:

- **Results directory** — typically a `<project>/wsinsight-runs/.../` folder
  produced by a previous run, but any folder that follows the WSInsight
  GeoJSON layout works.
- **Import into** — **Current open image** for the slide you have visible
  right now, or **Selected project images…** for a free-form subset you pick
  from the project.

The import scans `export-geojson/`, `export-niche-regions-geojson/`,
`model-outputs-geojson/`, `niche-outputs-geojson/`, and the cells/niches
subfolders under the last one. A summary notification reports how many objects
were added; slides that have no matching output are noted but do not abort the
import.

## Scope (v0.1)

- **Backend**: local Docker, or a native `wsinsight` on the same machine.
- **Slide selection**: current image, or a selected subset of project images.
- **OS**: tested on Linux and macOS. Windows is untested (`--user $(id -u)` is skipped).

## Developer notes

### The schema is not bundled

The forms are driven entirely by the schema file: commands, params, defaults,
`kind`, `help`, `required`, choices, and the zoo `models` list. It is **not**
shipped in the jar and there is no Gradle task to sync or verify it — a second
copy could only ever drift from the first. See **First run** for how to
regenerate it.

### Layout is inferred, not declared

`schema` carries no GUI hints, so the dialog derives its sections from the CLI's
flag prefixes and splits the main form in half. A few placements are pinned in
`GenericCommandDialog` — `--model` leads, `--stitch-workers` follows
`--num-workers`, and the directory pickers trail `--overwrite`. If richer layout
is ever wanted, `SchemaLoader` already understands per-param `group` and
`column_break` keys plus a per-command `groups` object; emitting them from
`schema` would take precedence over the inferred layout.

### Two runners, one interface

`Runner` has just `run(ProgressListener)` and `cancel()`. `DockerRunner` adds
bind mounts, `--gpus`, `--shm-size` and cancels with `docker kill`;
`NativeRunner` spawns the executable directly and cancels by destroying the
process and its descendants, since wsinsight's dataloader workers would
otherwise outlive it. Path rewriting is the other difference: `PathMapper` is
used only for Docker, and is bypassed entirely in native mode.

### Group options are not in the schema

`--backend` and `--log-level` are declared on the CLI's top-level Click group,
and `schema` only walks `cli.commands`, so neither reaches the schema. They
also have to precede the subcommand — `wsinsight --backend tiffslide run …` — so
they cannot be ordinary form fields. `GenericCommandDialog.globalArgs` emits them
ahead of the subcommand from preferences instead; anything else the group grows
belongs there too.

### Experimental command list

`WSInsightExtension.EXPERIMENTAL_COMMANDS` must match
`wsinsight.cli.cli._EXPERIMENTAL_COMMANDS`. A command the CLI hides but this set
omits is launched without `WSINSIGHT_EXPERIMENTAL` and fails as an unknown
subcommand.

## License

Apache 2.0 — see [`LICENSE`](LICENSE).

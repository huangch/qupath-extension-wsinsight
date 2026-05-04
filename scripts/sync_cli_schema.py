"""Sync the bundled CLI schema with the canonical wsinsight CLI schema.

Reads:
  * canonical schema  -- emitted by ``wsinsight describe`` (CLI source of truth)
  * existing schema   -- bundled in src/main/resources/, carries QuPath-only
                          GUI hints (``groups`` per command, ``group`` and
                          ``column_break`` per param) that the canonical
                          schema knows nothing about.

Writes the merged schema back to src/main/resources/wsinsight-cli-schema.json.

Usage:
    python scripts/sync_cli_schema.py <canonical-schema.json>

The canonical schema is regenerated with:
    python -m wsinsight describe --output /tmp/wsinsight-cli-schema.fresh.json
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

# Param-level keys that are GUI-only and must be carried over from the existing
# bundled schema when refreshing from the CLI source of truth.
PARAM_GUI_KEYS = ("group", "column_break")
# Command-level keys that are GUI-only.
COMMAND_GUI_KEYS = ("groups",)

ROOT = Path(__file__).resolve().parents[1]
BUNDLED = ROOT / "src" / "main" / "resources" / "wsinsight-cli-schema.json"


def merge_param(canonical_param: dict, old_param: dict | None) -> dict:
    out = dict(canonical_param)
    if old_param is None:
        return out
    for k in PARAM_GUI_KEYS:
        if k in old_param:
            out[k] = old_param[k]
    return out


def merge_command(name: str, canonical_cmd: dict, old_cmd: dict | None) -> dict:
    out = dict(canonical_cmd)
    if old_cmd is not None:
        for k in COMMAND_GUI_KEYS:
            if k in old_cmd:
                out[k] = old_cmd[k]
        old_params = {p["name"]: p for p in old_cmd.get("params", [])}
    else:
        old_params = {}
    out["params"] = [
        merge_param(p, old_params.get(p["name"])) for p in canonical_cmd.get("params", [])
    ]
    return out


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(f"usage: {argv[0]} <canonical-schema.json>", file=sys.stderr)
        return 2
    canonical = json.loads(Path(argv[1]).read_text(encoding="utf-8"))
    old = json.loads(BUNDLED.read_text(encoding="utf-8"))

    merged = {
        "schema_version": canonical.get("schema_version", 1),
        "commands": {},
    }
    old_commands = old.get("commands", {})
    for name, cmd in canonical["commands"].items():
        merged["commands"][name] = merge_command(name, cmd, old_commands.get(name))

    # Report orphan GUI hints (params/commands that no longer exist upstream).
    for name, old_cmd in old_commands.items():
        if name not in canonical["commands"]:
            print(f"warning: dropped command '{name}' (no longer in canonical schema)",
                  file=sys.stderr)
            continue
        canon_param_names = {p["name"] for p in canonical["commands"][name].get("params", [])}
        for p in old_cmd.get("params", []):
            if p["name"] not in canon_param_names and any(k in p for k in PARAM_GUI_KEYS):
                print(f"warning: dropped GUI hint for {name}.{p['name']} (param removed)",
                      file=sys.stderr)

    BUNDLED.write_text(
        json.dumps(merged, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(f"wrote {BUNDLED}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

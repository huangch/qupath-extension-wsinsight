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


def describe_drift(old: dict, merged: dict) -> list[str]:
    """Human-readable summary of what --check found, so the failure is actionable."""
    lines: list[str] = []
    old_cmds, new_cmds = old.get("commands", {}), merged["commands"]
    for name in sorted(set(new_cmds) - set(old_cmds)):
        lines.append(f"  command added upstream: {name}")
    for name in sorted(set(old_cmds) - set(new_cmds)):
        lines.append(f"  command removed upstream: {name}")

    def flags(cmd):
        return {f for p in cmd.get("params", []) for f in p.get("flags", [])}

    for name in sorted(set(old_cmds) & set(new_cmds)):
        gained = flags(new_cmds[name]) - flags(old_cmds[name])
        lost = flags(old_cmds[name]) - flags(new_cmds[name])
        if gained or lost:
            lines.append(f"  {name}:")
            if gained:
                lines.append(f"      missing from bundled schema: {' '.join(sorted(gained))}")
            if lost:
                lines.append(f"      stale in bundled schema    : {' '.join(sorted(lost))}")
    if not lines:
        lines.append("  (no command/flag differences; help text or defaults changed)")
    return lines


def main(argv: list[str]) -> int:
    check_only = "--check" in argv[1:]
    positional = [a for a in argv[1:] if not a.startswith("--")]
    if len(positional) != 1:
        print(f"usage: {argv[0]} [--check] <canonical-schema.json>", file=sys.stderr)
        return 2
    canonical = json.loads(Path(positional[0]).read_text(encoding="utf-8"))
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

    rendered = json.dumps(merged, indent=2, sort_keys=True) + "\n"

    if check_only:
        if BUNDLED.read_text(encoding="utf-8") == rendered:
            print("bundled CLI schema is up to date")
            return 0
        print("bundled CLI schema has drifted from `wsinsight describe`:", file=sys.stderr)
        for line in describe_drift(old, merged):
            print(line, file=sys.stderr)
        print("\nrun `./gradlew syncCliSchema` to refresh it.", file=sys.stderr)
        return 1

    BUNDLED.write_text(rendered, encoding="utf-8")
    print(f"wrote {BUNDLED}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))

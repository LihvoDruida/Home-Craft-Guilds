#!/usr/bin/env python3
"""Validate git-cliff release changelog support for Home Craft Guilds."""
from __future__ import annotations

import re
import sys
from pathlib import Path

try:
    import tomllib
except ModuleNotFoundError:  # pragma: no cover
    import tomli as tomllib  # type: ignore

ROOT = Path(__file__).resolve().parents[1]
CLIFF = ROOT / "cliff.toml"
WORKFLOW = ROOT / ".github" / "workflows" / "package.yml"


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(1)


if not CLIFF.is_file():
    fail("cliff.toml is missing at repository root")
if not WORKFLOW.is_file():
    fail(".github/workflows/package.yml is missing")

try:
    config = tomllib.loads(CLIFF.read_text(encoding="utf-8"))
except Exception as exc:  # noqa: BLE001
    fail(f"cliff.toml is not valid TOML: {exc}")

for section in ("changelog", "git"):
    if section not in config:
        fail(f"cliff.toml is missing [{section}] section")

git = config["git"]
if git.get("conventional_commits") is not True:
    fail("cliff.toml must keep conventional_commits = true")
if git.get("tag_pattern") != "v[0-9].*":
    fail("cliff.toml must keep tag_pattern = 'v[0-9].*'")

parsers = git.get("commit_parsers")
if not isinstance(parsers, list) or not parsers:
    fail("cliff.toml must define commit_parsers")
required_groups = ["🚀 New Features", "🐛 Bug Fixes", "⚡ Performance", "🛠️ Refactor"]
parser_text = repr(parsers)
for group in required_groups:
    if group not in parser_text:
        fail(f"cliff.toml commit_parsers missing group: {group}")

workflow = WORKFLOW.read_text(encoding="utf-8")
required_workflow_snippets = [
    "orhun/git-cliff-action@v4",
    "config: cliff.toml",
    "--latest --strip header --output RELEASE_CHANGELOG.md",
    "body_path: RELEASE_CHANGELOG.md",
    "changelog-file: RELEASE_CHANGELOG.md",
]
for snippet in required_workflow_snippets:
    if snippet not in workflow:
        fail(f"package.yml does not reference required git-cliff snippet: {snippet}")

if re.search(r"body_path:\s*CHANGELOG\.md", workflow):
    fail("GitHub Release must use generated RELEASE_CHANGELOG.md, not static CHANGELOG.md")
if re.search(r"changelog-file:\s*curseforge/CHANGELOG_CURSEFORGE\.md", workflow):
    fail("CurseForge must use generated RELEASE_CHANGELOG.md, not static curseforge changelog")

print("git-cliff release changelog support OK.")

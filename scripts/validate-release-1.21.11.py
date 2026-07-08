#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
TARGET_MC = "1.21.11"
TARGET_NEO_LINE = "21.11"


def fail(message: str) -> None:
    raise SystemExit(f"ERROR: {message}")


def read_properties(path: Path) -> dict[str, str]:
    if not path.is_file():
        fail(f"Missing required file: {path.relative_to(ROOT)}")
    props: dict[str, str] = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
    return props


def require_contains(path: Path, needle: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle not in text:
        fail(f"{path.relative_to(ROOT)} must contain: {needle}")


def require_not_contains(path: Path, needle: str) -> None:
    text = path.read_text(encoding="utf-8")
    if needle in text:
        fail(f"{path.relative_to(ROOT)} must not contain: {needle}")


def main() -> int:
    gradle_props = read_properties(ROOT / "gradle.properties")

    expected = {
        "mod_id": "homecraftguild",
        "mod_name": "Home Craft Guilds",
        "mod_authors": "Lihvo_Druida",
        "minecraft_version": TARGET_MC,
        "minecraft_version_range": "[1.21.11,1.21.12)",
        "neo_version_range": "[21.11,)",
        "loader_version_range": "[4,)",
    }
    for key, value in expected.items():
        actual = gradle_props.get(key)
        if actual != value:
            fail(f"gradle.properties has {key}={actual!r}; expected {value!r}")

    neo_version = gradle_props.get("neo_version", "")
    if not neo_version.startswith(TARGET_NEO_LINE + "."):
        fail(f"neo_version must stay on the {TARGET_NEO_LINE}.x line for Minecraft {TARGET_MC}; got {neo_version!r}")

    mod_version = gradle_props.get("mod_version", "")
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:[-+A-Za-z0-9.]+)?", mod_version):
        fail(f"mod_version must be release-like, got {mod_version!r}")

    require_contains(ROOT / "settings.gradle", "rootProject.name = 'homecraft-guild-neoforge'")
    require_contains(ROOT / "build.gradle", "id 'net.neoforged.moddev' version '2.0.141'")
    require_contains(ROOT / "build.gradle", "version = \"${mod_version}+mc${minecraft_version}-neoforge\"")
    require_contains(ROOT / "build.gradle", "archivesName = \"${mod_id}-neoforge-${minecraft_version}\"")
    require_contains(ROOT / "build.gradle", "JavaLanguageVersion.of(21)")
    require_contains(ROOT / "build.gradle", "description = 'Builds Home Craft Guilds for NeoForge 1.21.11.'")

    metadata = ROOT / "src/main/templates/META-INF/neoforge.mods.toml"
    require_contains(metadata, 'modId = "neoforge"')
    require_contains(metadata, 'modId = "minecraft"')

    workflow = ROOT / ".github/workflows/package.yml"
    if workflow.exists():
        text = workflow.read_text(encoding="utf-8")
        for required in (
            "TARGET_MINECRAFT_VERSION: '1.21.11'",
            "TARGET_LOADER: neoforge",
            "Build NeoForge for Minecraft 1.21.11",
            "game-versions: |\n            1.21.11",
            "loaders: |\n            neoforge",
        ):
            if required not in text:
                fail(f".github/workflows/package.yml must contain {required!r}")
        forbidden_markers = (
            "RealtimeSync",
            "realtime-sync",
            "fromJson(needs.prepare-matrix.outputs.build_matrix)",
            "matrix.loader",
            "matrix.mc_profile",
            "buildFabric",
            "buildForge",
            "buildQuilt",
            " buildFabric",
            " buildForge",
            " buildQuilt",
            "loader: fabric",
            "loader: quilt",
            "loader: forge",
        )
        for marker in forbidden_markers:
            if marker in text:
                fail(f".github/workflows/package.yml contains forbidden non-single-target marker: {marker}")

    old_profile_dir = ROOT / "buildProfiles"
    if old_profile_dir.exists():
        fail("buildProfiles/ must not be used in this single-target 1.21.11 release project")

    print("Home Craft Guilds release lock OK: NeoForge + Minecraft 1.21.11 + Java 21 only.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

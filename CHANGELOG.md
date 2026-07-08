# Changelog

## 0.1.156 — Minecraft 1.21.11 release pipeline lock

- Added GitHub Actions release workflow for exactly one target: NeoForge + Minecraft 1.21.11 + Java 21.
- Removed old multi-loader/multi-profile matrix assumptions from the release pipeline.
- Added `scripts/validate-release-1.21.11.py` to prevent accidental Fabric/Forge/Quilt or non-1.21.11 release metadata from entering CI.
- Updated GitHub and CurseForge release notes for a single 1.21.11 build.
- Kept the addon standalone-safe: optional HomeCraft integrations remain gated and disabled when the related addon/site/map runtime is absent.

## 0.1.155 — Release preparation and standalone integrations

- Cleaned project metadata for GitHub/CurseForge release.
- Fixed project name in `settings.gradle` from map project to guild project.
- Updated addon author metadata to `Lihvo_Druida`.
- Replaced oversized generated mod description with release-ready description.
- Added central optional-integration gate: `HomeCraftAddonIntegrations`.
- Added autonomous integration modes: `auto`, `standalone`, `force`.
- Spawn/Auth boundary logic now stays disabled when HomeCraft Auth / Spawn Protection is not present.
- Territory site/map sync now stays disabled by default and only runs when enabled and allowed by integration settings.
- Added GitHub README, install notes, configuration notes, integration notes, release checklist and CurseForge text.
- Added MIT license and `.gitignore` for clean source releases.

## 0.1.154 and earlier

Previous builds contained NPC preview fixes, guild XP combat contribution split, vanilla emerald economy migration, NPC trade validation, golem spawn/teleport safety, guild beds, talents, territory visuals and golem AI route/patrol improvements.

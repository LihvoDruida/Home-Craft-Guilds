# Home Craft Guilds

**Home Craft Guilds** — NeoForge addon for Minecraft **1.21.11** that adds a server-authoritative guild system with territories, ranks, guild crystal totems, protected claims, guild NPCs, traders, beds, talents, buffs and defensive golems.

- **Addon name:** Home Craft Guilds
- **Mod ID:** `homecraftguild`
- **Author:** `Lihvo_Druida`
- **Loader:** NeoForge
- **Minecraft:** `1.21.11`
- **NeoForge:** `21.11.42+`
- **Java:** 21
- **License:** MIT

## Features

- Guild creation, membership and rank management.
- Guild crystal totems and protected territory claims.
- Personal and guild territory validation with server-side permissions.
- Guild talents with separate member and golem talent branches.
- Passive guild buffs for members on guild territory.
- Guild beds with owner locking and client-rendered owner labels.
- Guild golems with patrol, defense, healing, respawn and elite-golem logic.
- Guild NPC system with admin UI, player-skin preview cache and configurable traders.
- Vanilla emerald-based economy for all built-in trades.
- Client UI for guild roster, talents, NPC control and territory visuals.
- Optional HomeCraft ecosystem integrations that automatically disable themselves when the related addon/site pipeline is not installed.

## Installation

### Server

1. Build the mod jar.
2. Put the jar into the server `mods` folder.
3. Start the server once to generate `config/homecraft-guild.properties`.
4. Edit the config if needed and restart the server.

### Client

Install the same jar on the client when you want client UI, NPC screens, territory border rendering and bed owner labels.

The server remains authoritative. Client UI only sends actions; all guild data, permissions, trade validation and territory protection are checked server-side.


## Localization

Home Craft Guilds ships with full `en_us` and `uk_ua` language files for registered content, guild effects, tooltips, NPC admin UI, skin labels, NPC presets, talents, and achievements.  Translation parity is enforced by `scripts/validate-i18n.py` and CI.

To add another language, copy `src/main/resources/assets/homecraftguild/lang/en_us.json`, translate values only, keep all keys/placeholders, and run:

```bash
python3 scripts/validate-i18n.py
```

More details: `docs/TRANSLATIONS_UA.md`.

## Build

```bash
./gradlew clean buildNeoForge --no-daemon
```

Windows:

```bat
gradlew.bat clean buildNeoForge --no-daemon
```

The compiled jar is generated in:

```text
build/libs/
```

## Commands

```mcfunction
/homecraftguild npc
/homecraftguild accept
/homecraftguild rank <nick> <BUILDER|QUARTERMASTER|WARRIOR|FARMER>
```

`/homecraftguild npc` is OP-only and opens/controls the guild NPC admin workflow.

## Optional HomeCraft integrations

Home Craft Guilds is safe to run standalone. Integration logic is gated by `homecraftIntegrationMode`:

```properties
homecraftIntegrationMode=auto
spawnBoundaryIntegrationEnabled=true
territorySyncEnabled=false
territorySyncRequiresHomeCraftMap=true
```

Modes:

- `auto` — default; optional logic runs only when the matching HomeCraft addon or site/map runtime is detected.
- `standalone` — disables all optional HomeCraft ecosystem integration logic.
- `force` — allows optional integration logic even when automatic detection cannot confirm another addon.

Details are in [`docs/INTEGRATIONS_UA.md`](docs/INTEGRATIONS_UA.md).

## Configuration

Example config: [`homecraft-guild.properties.example`](homecraft-guild.properties.example)

Full Ukrainian configuration notes: [`docs/CONFIGURATION_UA.md`](docs/CONFIGURATION_UA.md)


## GitHub Actions release

The repository release workflow is intentionally locked to one target only:

```text
Minecraft 1.21.11 + NeoForge + Java 21
```

Push a `v*` tag to build and publish the release artifact:

```bash
git tag v0.1.160
git push origin v0.1.160
```

The workflow validates the 1.21.11 lock before building and rejects old multi-loader/multi-version matrix settings.

## Release changelog automation

Release notes are generated from conventional commits through [`cliff.toml`](cliff.toml).

The GitHub Actions workflow generates `RELEASE_CHANGELOG.md` with git-cliff and uses the same generated changelog for:

- GitHub Release body.
- CurseForge changelog text.

Supported commit groups include `feat`, `fix`, `perf`, `refactor`, `chore`, `revert` and security-related entries. Documentation/style/test-only commits are intentionally skipped from release notes.

## Release files

- [`CHANGELOG.md`](CHANGELOG.md)
- [`CHANGELOG_V156_RELEASE_PIPELINE_1_21_11.md`](CHANGELOG_V156_RELEASE_PIPELINE_1_21_11.md)
- [`CHANGELOG_V157_GIT_CLIFF_RELEASE_NOTES.md`](CHANGELOG_V157_GIT_CLIFF_RELEASE_NOTES.md)
- [`cliff.toml`](cliff.toml)
- [`docs/GITHUB_RELEASE_UA.md`](docs/GITHUB_RELEASE_UA.md)
- [`curseforge/README_CURSEFORGE.md`](curseforge/README_CURSEFORGE.md)
- [`curseforge/CHANGELOG_CURSEFORGE.md`](curseforge/CHANGELOG_CURSEFORGE.md)

## License

MIT License. See [`LICENSE`](LICENSE).

# Home Craft Guilds

Home Craft Guilds is a NeoForge guild addon for Minecraft 1.21.11.

It adds server-authoritative guild gameplay: guild creation, ranks, protected guild territories, crystal totems, member buffs, guild beds, talents, configurable NPC traders and defensive guild golems.

## Main features

- Guild territories and protected claims.
- Guild crystal totem item/block.
- Guild ranks and member management.
- Member and golem talent branches.
- Guild buffs on guild territory.
- Guild beds with ownership locking.
- Guild NPCs, traders and admin UI.
- Guild golems with patrol, defense, healing and respawn logic.
- Client UI and client-side territory visuals.
- Standalone-safe optional HomeCraft integrations.

## Requirements

- Minecraft 1.21.11
- NeoForge 21.11.42 or newer in the 21.11 line
- Java 21

## Client/server

Install on both server and client for full functionality.

Server is required for gameplay logic. Client is required for UI, NPC screens, territory visuals and owner labels.

## Optional integrations

The addon works standalone. If HomeCraft Auth, Spawn Protection, Map or Site runtime is not installed, integration logic is skipped automatically.

Use `homecraftIntegrationMode=standalone` in `config/homecraft-guild.properties` for strict standalone behavior.

# Конфігурація Home Craft Guilds

Файл створюється після першого запуску:

```text
config/homecraft-guild.properties
```

## Базові параметри

```properties
guildCreateEmeraldCost=3
maxGuildMembers=20
personalBannerLimit=2
personalClaimSize=48
guildBannerLimit=4
guildClaimSize=64
creativeBypass=true
```

## Інтеграції

```properties
homecraftIntegrationMode=auto
spawnBoundaryIntegrationEnabled=true
territorySyncEnabled=false
territorySyncRequiresHomeCraftMap=true
```

Для релізного standalone-сервера без інших HomeCraft addon-ів можна поставити:

```properties
homecraftIntegrationMode=standalone
territorySyncEnabled=false
```

## Големи

```properties
guildGolemAiEnabled=true
guildGolemMaxPerGuild=5
guildEliteGolemEnabled=true
guildEliteGolemMaxPerGuild=1
guildGolemRouteAsyncEnabled=true
guildGolemRouteAvoidWater=true
guildGolemRouteAvoidDeepDrops=true
```

Звичайні гільдійні големи мають посилені HP/урон, патрулюють територію та не атакують учасників своєї гільдії. Елітні големи мають окремі ліміти й пріоритетну логіку захисту.

## Ліжка

```properties
guildBedsEnabled=true
guildBedsOneClaimedBedPerMember=true
guildBedsOnePlacedBedPerMember=true
guildBedsShowOwnerLabel=true
guildBedsHideExactCoordsForNonMembers=true
```

## NPC і торговці

NPC налаштовуються через `guildNpc.*`. Скіни є addon-owned: сервер передає ID, клієнт бере текстуру з:

```text
assets/homecraftguild/textures/entity/npc/<skinId>.png
```

Валюта built-in торгів — `minecraft:emerald`.

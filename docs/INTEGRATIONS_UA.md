# Інтеграції Home Craft Guilds

Addon працює автономно. Логіка, яка стосується інших HomeCraft addon-ів або site/map pipeline, проходить через центральний gate `HomeCraftAddonIntegrations`.

## Основний принцип

Якщо іншого HomeCraft addon-а немає, guild addon не запускає його логіку, не робить reflection-виклики і не стартує зайві background sync-задачі.

## Налаштування

```properties
homecraftIntegrationMode=auto
spawnBoundaryIntegrationEnabled=true
territorySyncEnabled=false
territorySyncRequiresHomeCraftMap=true
```

### `homecraftIntegrationMode`

- `auto` — рекомендовано. Інтеграції активуються тільки якщо знайдено відповідний addon або runtime site/map.
- `standalone` — повністю автономний режим; optional-інтеграції не запускаються.
- `force` — примусово дозволяє optional-логіку, навіть якщо auto-detect нічого не знайшов.

### Spawn/Auth integration

`GuildSpawnBoundary` читає/викликає HomeCraft Auth / Spawn Protection тільки коли:

- `spawnBoundaryIntegrationEnabled=true`;
- режим не `standalone`;
- знайдено HomeCraft Auth / Spawn Protection або увімкнено `force`.

Якщо інтеграції немає, guild addon не блокує логіку через чужий spawn-config і працює самостійно.

### Site/Map territory sync

`GuildSiteSync` запускається тільки коли:

- `territorySyncEnabled=true`;
- режим не `standalone`;
- `territorySyncRequiresHomeCraftMap=false`, або знайдено HomeCraft Map/Site runtime, або увімкнено `force`.

Для повністю автономного сервера залишай:

```properties
territorySyncEnabled=false
```

Для сервера з сайтом, але без окремого map-addon-а:

```properties
territorySyncEnabled=true
territorySyncRequiresHomeCraftMap=false
apiBaseUrl=http://127.0.0.1:3000
serverSecret=<same-secret-as-site>
```

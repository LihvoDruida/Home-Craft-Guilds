# GitHub release checklist

Цей репозиторій навмисно заблокований під один релізний target:

```text
Minecraft: 1.21.11
Loader: NeoForge
Java: 21
Author: Lihvo_Druida
```

Matrix-збірки для Fabric/Forge/Quilt або інших Minecraft-версій тут не використовуються.

## Перед комітом

- [ ] `settings.gradle` має `rootProject.name = 'homecraft-guild-neoforge'`.
- [ ] `gradle.properties` має `mod_authors=Lihvo_Druida`, `minecraft_version=1.21.11`, `minecraft_version_range=[1.21.11,1.21.12)`.
- [ ] `neo_version` залишається в лінії `21.11.x`.
- [ ] `.github/workflows/package.yml` збирає тільки `buildNeoForge`.
- [ ] `README.md`, `CHANGELOG.md`, `LICENSE`, `docs/*` оновлені.
- [ ] Старі `AUDIT_V*.md`, `javac.*.args`, локальні build/log файли не потрапляють у чистий release source.
- [ ] `homecraft-guild.properties.example` містить автономні інтеграції за замовчуванням.

## Локальна перевірка release lock

```bash
python3 scripts/validate-release-1.21.11.py
```

Windows, якщо Python доступний як `py`:

```bat
py scripts\validate-release-1.21.11.py
```

## Локальна збірка

```bat
gradlew.bat clean buildNeoForge --no-daemon
```

Очікуваний runtime jar:

```text
build/libs/homecraftguild-neoforge-1.21.11-<mod_version>+mc1.21.11-neoforge.jar
```

## GitHub Actions release

Release запускається тільки тегом:

```bash
git tag v0.1.156
git push origin v0.1.156
```

Workflow робить тільки одну збірку:

```text
Home Craft Guilds / NeoForge / Minecraft 1.21.11 / Java 21
```

## Smoke test

- [ ] Сервер стартує без HomeCraft Auth/Map/Site.
- [ ] У логах видно `Home Craft Guild integrations: mode=auto ... territorySync=false`.
- [ ] `/homecraftguild npc` працює для OP.
- [ ] Створення гільдії, тотем, UI, ліжка, големи не падають без інших addon-ів.
- [ ] З `homecraftIntegrationMode=standalone` optional-інтеграції не запускаються.
- [ ] З `territorySyncEnabled=true` і `territorySyncRequiresHomeCraftMap=false` site sync працює тільки з валідним `apiBaseUrl`/`serverSecret`.

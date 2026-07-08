
## v0.1.159 — миттєва зміна мови UI

- Виправлено змішування української та англійської мови в екрані складу гільдії.
- Додано live-перевірку активної мови Minecraft для відкритих екранів.
- Екрани складу, талантів, створення гільдії та NPC admin UI перебудовують написи після зміни мови.
- Перекладено ролі, секції складу, бафи, статуси големів, виміри, tooltip-и ліжок/големів і довідку гільдії.
- Перевірка локалізації тепер контролює нові namespaces перекладів.

# Home Craft Guilds v0.1.158 — українська/англійська локалізація і живий NPC preview

## Що додано

- Додано повні окремі мовні файли `en_us.json` і `uk_ua.json`.
- Додано перевірку `scripts/validate-i18n.py`, яка не дає випустити реліз, якщо в англійському й українському файлах різні ключі.
- Додано `docs/TRANSLATIONS_UA.md` з правилами додавання нових мов.
- Локалізовано назви контенту адона, tooltip-и гільдійного банера/тотема, NPC presets, skin labels, таланти й досягнення.

## NPC preview

- Preview у NPC admin UI тепер реагує на мишку як inventory-preview: модель повертається за курсором, а голова/нахил не лишаються мертвими.
- При зміні `skinId` preview одразу скидає кеш і перемальовується, тому можна швидко бачити, який скін вибраний.
- Системні/default NPC назви тепер ідуть через language keys. Кастомні назви, які адмін ввів вручну, не перезаписуються.

## Релізний lock

Підтримка лишається тільки для:

```text
Minecraft 1.21.11 + NeoForge + Java 21
```

# Home Craft Guilds v0.1.157 — git-cliff release notes

## Що додано

- Додано `cliff.toml` у корінь репозиторію.
- GitHub Actions тепер генерує `RELEASE_CHANGELOG.md` через git-cliff.
- GitHub Release використовує автоматично згенерований changelog.
- CurseForge upload використовує той самий `RELEASE_CHANGELOG.md`.
- Додано перевірку `scripts/validate-cliff-config.py`, щоб workflow не повернувся на статичний changelog або чужу release-схему.

## Обмеження релізу

Підтримка лишається тільки для:

```text
Minecraft 1.21.11 + NeoForge + Java 21
```

# Patch notes — Home Craft Guilds v0.1.156

## Що зроблено

- Інтегровано GitHub Actions release pipeline тільки для **Minecraft 1.21.11**.
- Release workflow тепер збирає тільки **NeoForge** і тільки task `buildNeoForge`.
- Прибрано стару логіку multi-loader/multi-profile matrix із release-пакета.
- CurseForge metadata тепер публікує тільки:
  - loader: `neoforge`
  - game version: `1.21.11`
  - Java: `Java 21`
- Додано `scripts/validate-release-1.21.11.py`, який блокує випадкове потрапляння інших Minecraft-версій, Fabric/Forge/Quilt matrix або старих RealtimeSync-назв у workflow.
- Оновлено GitHub release checklist і CurseForge changelog.
- Автономна логіка HomeCraft Guilds з v0.1.155 залишена без змін: optional-інтеграції не запускаються без потрібних HomeCraft addon/site/map компонентів.

## Важливо

Я не переніс `scripts.zip` повністю, бо він був під multi-loader/multi-profile pipeline з назвами `RealtimeSync`, Fabric/Forge/Quilt і `buildProfiles`. Для цього addon-а це було б помилкою: такий pipeline почав би шукати неіснуючі модулі та збирати зайве.

Замість цього додано короткий release-lock script тільки під Home Craft Guilds + NeoForge + Minecraft 1.21.11.

## Локальна перевірка

```bat
py scripts\validate-release-1.21.11.py
gradlew.bat clean buildNeoForge --no-daemon
```

## GitHub release

```bash
git tag v0.1.156
git push origin v0.1.156
```

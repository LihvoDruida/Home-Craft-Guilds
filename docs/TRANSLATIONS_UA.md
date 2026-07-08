# Переклади Home Craft Guilds

Мод має обов’язкову двомовну базу:

- `src/main/resources/assets/homecraftguild/lang/en_us.json`
- `src/main/resources/assets/homecraftguild/lang/uk_ua.json`

Обидва файли повинні мати однаковий набір ключів. Це важливо, бо інакше частина UI, предметів, ефектів, NPC, талантів або досягнень буде показувати raw key замість нормального тексту.

## Що вже винесено в lang-файли

- назва предмета `guild_banner`;
- назва блока `guild_banner`;
- всі гільдійні ефекти;
- tooltip гільдійного тотема;
- системні повідомлення встановлення тотема;
- NPC Admin UI;
- назви доступних NPC-скінів;
- пресети NPC-торговців;
- 3D preview повідомлення;
- вкладки, кнопки, фільтри;
- гілки талантів;
- назви, описи й ефекти талантів;
- overlay досягнень;
- назви, категорії, описи, умови й нагороди досягнень.

## Як додати нову мову

1. Скопіюй `en_us.json` у новий файл, наприклад:

```text
src/main/resources/assets/homecraftguild/lang/pl_pl.json
```

2. Переклади тільки значення, ключі не змінюй.
3. Не видаляй placeholders типу `%s` — вони підставляються кодом.
4. Проганяй перевірку:

```bash
python3 scripts/validate-i18n.py
```

## Правило для нового контенту

Для кожного нового предмета, блока, ефекту, NPC, talent, achievement або GUI-тексту потрібно одразу додати ключі в `en_us.json` і `uk_ua.json`.

Приклади форматів:

```json
"item.homecraftguild.example_item": "Example Item"
"block.homecraftguild.example_block": "Example Block"
"effect.homecraftguild.example_effect": "Example Effect"
"screen.homecraftguild.example_screen.title": "Example Screen"
"talent.homecraftguild.example_talent.title": "Example Talent"
"achievement.homecraftguild.example_achievement.title": "Example Achievement"
```

## Важливе обмеження

Користувацькі назви NPC з config/server data залишаються користувацьким текстом. Якщо адміністратор вручну задав назву NPC у конфігу, вона буде показана як задано. Системні назви, пресети й UI перекладаються через lang-файли.

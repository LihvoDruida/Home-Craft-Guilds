#!/usr/bin/env python3
"""Validate Home Craft Guilds localization resources."""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LANG_DIR = ROOT / "src/main/resources/assets/homecraftguild/lang"
REQUIRED_LOCALES = ("en_us", "uk_ua")
DYNAMIC_PREFIXES = (
    "talent.homecraftguild.",
    "skin.homecraftguild.",
    "npc_preset.homecraftguild.",
    "achievement.homecraftguild.",
    "talent_status.homecraftguild.",
)


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    sys.exit(1)


def read_locale(locale: str) -> dict[str, str]:
    path = LANG_DIR / f"{locale}.json"
    if not path.is_file():
        fail(f"Missing required locale file: {path.relative_to(ROOT)}")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001
        fail(f"Invalid JSON in {path.relative_to(ROOT)}: {exc}")
    if not isinstance(data, dict):
        fail(f"Locale file must be a JSON object: {path.relative_to(ROOT)}")
    bad = [key for key, value in data.items() if not isinstance(key, str) or not isinstance(value, str) or not key or not value]
    if bad:
        fail(f"Locale {locale} contains empty/non-string keys or values: {bad[:8]}")
    return data


def collect_static_code_keys() -> set[str]:
    keys: set[str] = set()
    pattern = re.compile(r'"((?:button|field|filter|message|preview|screen|tab|tooltip|npc|npc_preset|skin|talent|talent_branch|talent_status|achievement)\.homecraftguild[^"]*)"')
    for path in (ROOT / "src/main/java").rglob("*.java"):
        text = path.read_text(encoding="utf-8", errors="ignore")
        for match in pattern.finditer(text):
            key = match.group(1)
            if any(key == prefix for prefix in DYNAMIC_PREFIXES):
                continue
            keys.add(key)
    return keys


def main() -> int:
    locales = {locale: read_locale(locale) for locale in REQUIRED_LOCALES}
    base_keys = set(locales["en_us"].keys())
    for locale, data in locales.items():
        keys = set(data.keys())
        missing = sorted(base_keys - keys)
        extra = sorted(keys - base_keys)
        if missing or extra:
            fail(f"Locale key mismatch for {locale}: missing={missing[:12]}, extra={extra[:12]}")

    required_content_keys = [
        "item.homecraftguild.guild_banner",
        "block.homecraftguild.guild_banner",
        "effect.homecraftguild.guild_player_xp",
        "tooltip.homecraftguild.guild_banner.title",
        "screen.homecraftguild.npc_admin.title",
        "screen.homecraftguild.talents.title",
        "screen.homecraftguild.achievements.title",
        "skin.homecraftguild.guild_registrar",
        "npc_preset.homecraftguild.trader_weapons.name",
        "talent.homecraftguild.member_xp_1.title",
        "talent.homecraftguild.golem_stone_heart.effect",
        "achievement.homecraftguild.kill_ender_dragon.title",
    ]
    for key in required_content_keys:
        if key not in base_keys:
            fail(f"Missing required localization key: {key}")

    code_keys = collect_static_code_keys()
    missing_in_lang = sorted(key for key in code_keys if key not in base_keys and not any(key.startswith(prefix) for prefix in DYNAMIC_PREFIXES))
    if missing_in_lang:
        fail(f"Code references localization keys missing from lang files: {missing_in_lang[:24]}")

    print(f"Home Craft Guilds i18n OK: {len(base_keys)} keys in en_us and uk_ua, with matching key sets.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

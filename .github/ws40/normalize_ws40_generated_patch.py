#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def remove_exact(path: str, needle: str) -> None:
    p = ROOT / path
    text = p.read_text(encoding="utf-8")
    count = text.count(needle)
    if count > 1:
        raise SystemExit(f"WS40_NORMALIZE_AMBIGUOUS:{path}:{count}:{needle!r}")
    if count == 1:
        p.write_text(text.replace(needle, "", 1), encoding="utf-8")


# DamageDealEffect's raw Map<Card,Integer> use disappears only after the WS40 patch.
remove_exact(
    "forge-game/src/main/java/forge/game/ability/effects/DamageDealEffect.java",
    "import java.util.Map;\n",
)

# The core decision implementation does not use IdentityHashMap.
remove_exact(
    "forge-game/src/main/java/forge/game/combat/CombatDamageDecision.java",
    "import java.util.IdentityHashMap;\n",
)

print("WS40 generated-patch normalization complete")

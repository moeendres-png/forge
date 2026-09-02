#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def remove_exact(path: str, needle: str) -> None:
    text = read(path)
    count = text.count(needle)
    if count > 1:
        raise SystemExit(f"WS40_NORMALIZE_AMBIGUOUS:{path}:{count}:{needle!r}")
    if count == 1:
        write(path, text.replace(needle, "", 1))


def remove_java_method(path: str, signature_fragment: str) -> None:
    text = read(path)
    pos = text.find(signature_fragment)
    if pos < 0:
        return
    if text.find(signature_fragment, pos + 1) >= 0:
        raise SystemExit(f"WS40_NORMALIZE_AMBIGUOUS_METHOD:{path}:{signature_fragment}")
    line_start = text.rfind("\n", 0, pos) + 1
    start = line_start
    while start > 0:
        prev_end = start - 1
        prev_start = text.rfind("\n", 0, prev_end) + 1
        prev = text[prev_start:prev_end].strip()
        if prev in {"@Override", "@Deprecated"}:
            start = prev_start
        else:
            break
    brace = text.find("{", pos)
    semicolon = text.find(";", pos)
    if semicolon >= 0 and (brace < 0 or semicolon < brace):
        end = semicolon + 1
        if end < len(text) and text[end] == "\n":
            end += 1
        write(path, text[:start] + text[end:])
        return
    if brace < 0:
        raise SystemExit(f"WS40_NORMALIZE_NO_METHOD_BODY:{path}:{signature_fragment}")
    depth = 0
    i = brace
    state = "code"
    while i < len(text):
        c = text[i]
        n = text[i + 1] if i + 1 < len(text) else ""
        if state == "code":
            if c == '"': state = "string"
            elif c == "'": state = "char"
            elif c == "/" and n == "/": state = "line_comment"; i += 1
            elif c == "/" and n == "*": state = "block_comment"; i += 1
            elif c == "{": depth += 1
            elif c == "}":
                depth -= 1
                if depth == 0:
                    end = i + 1
                    if end < len(text) and text[end] == "\n": end += 1
                    write(path, text[:start] + text[end:])
                    return
        elif state == "string":
            if c == "\\": i += 1
            elif c == '"': state = "code"
        elif state == "char":
            if c == "\\": i += 1
            elif c == "'": state = "code"
        elif state == "line_comment":
            if c == "\n": state = "code"
        elif state == "block_comment":
            if c == "*" and n == "/": state = "code"; i += 1
        i += 1
    raise SystemExit(f"WS40_NORMALIZE_UNBALANCED_METHOD:{path}:{signature_fragment}")


remove_exact("forge-game/src/main/java/forge/game/ability/effects/DamageDealEffect.java", "import java.util.Map;\n")
remove_exact("forge-game/src/main/java/forge/game/combat/CombatDamageDecision.java", "import java.util.IdentityHashMap;\n")
remove_java_method("forge-game/src/main/java/forge/game/player/PlayerController.java", "assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder)")
remove_java_method("forge-gui/src/main/java/forge/player/PlayerControllerHuman.java", "assignCombatDamage(final Card attacker, final CardCollectionView blockers, final CardCollectionView remaining,")
remove_java_method("forge-ai/src/main/java/forge/ai/PlayerControllerAi.java", "assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder)")
remove_java_method("forge-ai/src/main/java/forge/ai/ComputerUtilCombat.java", "distributeAIDamage(final Player self, final Card combatant, CardCollectionView opposedCombatants, final CardCollectionView remaining, int dmgCanDeal, GameEntity defender, boolean overrideOrder)")
remove_exact("forge-ai/src/main/java/forge/ai/ComputerUtilCombat.java", "import com.google.common.collect.Maps;\n")
remove_exact("forge-ai/src/main/java/forge/ai/ComputerUtilCombat.java", "import forge.game.combat.AttackingBand;\n")

# Test consumers must compile against the same authority boundary; do not retain a test-only raw legality API.
remove_java_method(
    "forge-gui-desktop/src/test/java/forge/gamesimulationtests/util/PlayerControllerForTests.java",
    "assignCombatDamage(Card attacker, CardCollectionView blockers, CardCollectionView remaining, int damageDealt, GameEntity defender, boolean overrideOrder)",
)

checks = {
    "forge-game/src/main/java/forge/game/player/PlayerController.java": ["assignCombatDamage("],
    "forge-gui/src/main/java/forge/player/PlayerControllerHuman.java": ["getGui().assignCombatDamage("],
    "forge-ai/src/main/java/forge/ai/PlayerControllerAi.java": ["distributeAIDamage("],
    "forge-game/src/main/java/forge/game/combat/Combat.java": ["getController().assignCombatDamage("],
    "forge-game/src/main/java/forge/game/ability/effects/DamageDealEffect.java": ["getController().assignCombatDamage("],
    "forge-gui-desktop/src/test/java/forge/gamesimulationtests/util/PlayerControllerForTests.java": ["assignCombatDamage("],
}
for path, forbidden in checks.items():
    text = read(path)
    for needle in forbidden:
        if needle in text:
            raise SystemExit(f"WS40_LEGACY_AUTHORITY_REMAINS:{path}:{needle}")

print("WS40 generated-patch normalization and legacy-authority removal complete")

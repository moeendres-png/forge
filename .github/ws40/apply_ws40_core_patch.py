#!/usr/bin/env python3
from pathlib import Path
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
WS40 = ROOT / ".github" / "ws40"
TEMPLATE_ROOT = WS40 / "materialized"
EXISTING_TRANSFORM = WS40 / "patch_ws40_existing_sources.py"
FINAL_MARKER_PATH = ROOT / "forge-game/src/main/java/forge/game/combat/Combat.java"
FINAL_MARKER = "CombatDamageAssignmentValidator.validateAll(this"

NEW_FILES = (
    "forge-game/src/main/java/forge/game/combat/CombatDamageAssignmentValidator.java",
    "forge-game/src/main/java/forge/game/combat/CombatDamageDecision.java",
    "forge-game/src/main/java/forge/game/combat/CombatDamageDecisionView.java",
    "forge-game/src/main/java/forge/game/combat/CombatDamageSelection.java",
    "forge-game/src/main/java/forge/game/player/AmountDistributionDecision.java",
    "forge-game/src/main/java/forge/game/player/AmountDistributionDecisionView.java",
    "forge-game/src/main/java/forge/game/player/AmountDistributionSelection.java",
    "forge-gui-desktop/src/test/java/forge/game/combat/WS40CombatDamageCoreTest.java",
)


def verify_templates() -> None:
    missing = [path for path in NEW_FILES if not (TEMPLATE_ROOT / path).is_file()]
    if missing:
        raise SystemExit("WS40_PATCH_TEMPLATE_MISSING:" + ",".join(missing))


def verify_materialized_new_files() -> None:
    for path in NEW_FILES:
        template = TEMPLATE_ROOT / path
        destination = ROOT / path
        if not destination.is_file():
            raise SystemExit(f"WS40_PATCH_DESTINATION_MISSING:{path}")
        if destination.read_bytes() != template.read_bytes():
            raise SystemExit(f"WS40_PATCH_DESTINATION_MISMATCH:{path}")


def already_final() -> bool:
    if not FINAL_MARKER_PATH.is_file():
        return False
    if FINAL_MARKER not in FINAL_MARKER_PATH.read_text(encoding="utf-8"):
        return False
    return all((ROOT / path).is_file() for path in NEW_FILES)


def materialize_new_files() -> None:
    for path in NEW_FILES:
        template = TEMPLATE_ROOT / path
        destination = ROOT / path
        if destination.exists():
            if destination.read_bytes() != template.read_bytes():
                raise SystemExit(f"WS40_PATCH_PREEXISTING_MISMATCH:{path}")
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(template, destination)


def main() -> None:
    verify_templates()
    if already_final():
        verify_materialized_new_files()
        print("WS40 core patch already materialized")
        return
    if not EXISTING_TRANSFORM.is_file():
        raise SystemExit("WS40_PATCH_EXISTING_TRANSFORM_MISSING")
    subprocess.run([sys.executable, str(EXISTING_TRANSFORM)], cwd=ROOT, check=True)
    materialize_new_files()
    print("WS40 core patch materialized from bound baseline")


if __name__ == "__main__":
    main()

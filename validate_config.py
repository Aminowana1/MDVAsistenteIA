from __future__ import annotations

import sys
from pathlib import Path

RESOURCE_DIR = Path(__file__).resolve().parent / "src" / "main" / "resources"
CONFIG_PATHS = [
    RESOURCE_DIR / "config.yml",
    RESOURCE_DIR / "personality.yml",
    RESOURCE_DIR / "wiki.yml",
    RESOURCE_DIR / "integrations.yml",
]


def main() -> int:
    try:
        import yaml
    except ImportError:
        print("Error: PyYAML is not installed. Install it with: python -m pip install pyyaml")
        return 1

    failed = False
    for path in CONFIG_PATHS:
        if not path.exists():
            print(f"Error: config file not found: {path}")
            failed = True
            continue
        try:
            text = path.read_text(encoding="utf-8")
            yaml.safe_load(text)
            print(f"OK: {path.name} is valid YAML")
        except Exception as exc:
            print(f"YAML parse error in {path}: {exc}")
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Small shared helpers for the directly runnable maintenance scripts."""
import json
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]


def load_json(filename):
    """Read a JSON configuration file from the repository root."""
    return json.loads((ROOT / filename).read_text())


def read_mod_version():
    """Return the modVersion property used in produced artifact names."""
    for line in (ROOT / "gradle.properties").read_text().splitlines():
        if line.startswith("modVersion="):
            return line.split("=", 1)[1]
    raise ValueError("gradle.properties does not define modVersion")

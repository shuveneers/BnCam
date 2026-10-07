"""Generate a source index of persisted settings; this does not pretend to read device DataStore."""
import argparse
import json
import re
from pathlib import Path


def inventory(repository: Path):
    source_root = repository / "app/src/main/java/com/bncam"
    declarations = []
    key_pattern = re.compile(r'(\w+PreferencesKey)\(\s*"([^"\n]+)"\s*\)')
    sources = [(path, path.read_text(encoding="utf-8")) for path in sorted(source_root.rglob("*.kt"))]
    for path, text in sources:
        if "/data/settings/" not in path.as_posix():
            continue
        for match in key_pattern.finditer(text):
            key = match[2]
            references = []
            for other, contents in sources:
                for hit in re.finditer(re.escape('"' + key + '"'), contents):
                    references.append({"file": other.relative_to(repository).as_posix(),
                                       "line": contents.count("\n", 0, hit.start()) + 1})
            declarations.append({"keyOrTemplate": key, "type": match[1].removesuffix("PreferencesKey"),
                                 "declaration": path.relative_to(repository).as_posix(),
                                 "line": text.count("\n", 0, match.start()) + 1,
                                 "literalReferences": references,
                                 "storedValue": "requires device DataStore export",
                                 "defaultAndScopeAuthority": "CaptureSettingsSchema / LibpatcherSettingsCatalog / repository getter",
                                 "classification": "source index; resolve dynamic profile/lens templates before comparing keys"})
    return {"schemaVersion": 1, "scope": "all literal DataStore key declarations under data/settings",
            "captureRuntimeValues": "capture_performance.jsonl captureRecipe.ispSettings.resolvedSettings",
            "declarations": declarations}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parents[2]
    result = inventory(repository)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Indexed {len(result['declarations'])} persisted key declarations")

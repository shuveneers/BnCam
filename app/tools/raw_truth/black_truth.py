from __future__ import annotations

import json
import math
import re
import zipfile
from dataclasses import dataclass, asdict
from pathlib import Path
from typing import Dict, Iterable, Iterator, List, Optional, Sequence, Tuple

TEXT_SUFFIXES = {
    ".txt", ".log", ".json", ".csv", ".tsv", ".md", ".ini", ".cfg", ".xml",
}

VECTOR_RE = re.compile(r"\[\s*([-+0-9.eE]+)\s*[,;]\s*([-+0-9.eE]+)\s*[,;]\s*([-+0-9.eE]+)\s*[,;]\s*([-+0-9.eE]+)\s*\]")
KV_RE = re.compile(r"(?P<key>[A-Za-z][A-Za-z0-9_.-]+)\s*=\s*(?P<value>\[[^\]]*\]|[^;\r\n]+)")

KEY_ALIASES = {
    "sceneblackauthoritymode": "scene_black_authority_mode",
    "sceneblackmetadataauthoritative": "scene_black_metadata_authoritative",
    "sceneblackimagemutationallowed": "scene_black_image_mutation_allowed",
    "spectrapass0applied": "spectra_pass0_applied",
    "spectrapass0skipreason": "spectra_pass0_skip_reason",
    "spectrapass0inputenergy": "spectra_pass0_input_energy",
    "spectrapass0outputenergy": "spectra_pass0_output_energy",
    "spectrapass0targetfloor": "spectra_pass0_target_floor",
    "spectrapass0classification": "spectra_pass0_classification",
    "classification": "classification",
    "fallbackreason": "fallback_reason",
    "planningmethod": "planning_method",
    "planningsamplecount": "planning_sample_count",
    "channelbiasconfidence": "channel_bias_confidence",
    "greensplitmad": "green_split_mad",
    "greensplittileconsensus": "green_split_tile_consensus",
    "greensplittilecount": "green_split_tile_count",
    "channelbiasbefore": "channel_bias_before",
    "spectrapass0channelbiasbefore": "channel_bias_before",
    "rawblackchannelbiasbefore": "channel_bias_before",
    "appliedchannelbias": "applied_channel_bias",
    "spectrapass0appliedchannelbias": "applied_channel_bias",
    "channelbiasafter": "channel_bias_after",
    "spectrapass0channelbiasafter": "channel_bias_after",
    "blacklevel": "black_level",
    "blacklevels": "black_level",
    "blacklevelpattern": "black_level",
    "appliedblacklevel": "black_level",
    "effectiveblacklevel": "black_level",
    "usedblacklevel": "black_level",
    "dynamicblacklevel": "dynamic_black_level",
    "staticblacklevel": "static_black_level",
}

VECTOR_LABEL_PATTERNS: Sequence[Tuple[str, re.Pattern[str]]] = (
    ("channel_bias_before", re.compile(r"(?:channel\s*bias.*before|before.*channel\s*bias|residual.*before)", re.I)),
    ("applied_channel_bias", re.compile(r"(?:applied.*channel\s*bias|channel\s*bias.*applied)", re.I)),
    ("channel_bias_after", re.compile(r"(?:channel\s*bias.*after|after.*channel\s*bias|residual.*after)", re.I)),
    ("dynamic_black_level", re.compile(r"dynamic\s*black\s*level", re.I)),
    ("static_black_level", re.compile(r"static\s*black\s*level", re.I)),
    ("black_level", re.compile(r"(?:(?:applied|effective|used|selected)?\s*black\s*level)", re.I)),
)

CFA_LABELED_RE = re.compile(
    r"\bR\s*[:=]\s*([-+0-9.eE]+).*?\bG1\s*[:=]\s*([-+0-9.eE]+).*?"
    r"\bG2\s*[:=]\s*([-+0-9.eE]+).*?\bB\s*[:=]\s*([-+0-9.eE]+)",
    re.I,
)


def _canonical_key(key: str) -> str:
    compact = re.sub(r"[^A-Za-z0-9]", "", key).lower()
    return KEY_ALIASES.get(compact, re.sub(r"(?<!^)(?=[A-Z])", "_", key).replace(".", "_").replace("-", "_").lower())


def _parse_scalar(value: str):
    s = value.strip().strip('"')
    low = s.lower()
    if low == "true":
        return True
    if low == "false":
        return False
    if low in {"null", "none", "nan"}:
        return None if low != "nan" else math.nan
    vm = VECTOR_RE.fullmatch(s)
    if vm:
        return [float(vm.group(i)) for i in range(1, 5)]
    try:
        if re.fullmatch(r"[-+]?\d+", s):
            return int(s)
        return float(s)
    except ValueError:
        return s


def _looks_textual(data: bytes, suffix: str) -> bool:
    if suffix.lower() in TEXT_SUFFIXES:
        return True
    sample = data[:4096]
    if not sample:
        return True
    if b"\x00" in sample:
        return False
    printable = sum(1 for b in sample if b in b"\t\n\r" or 32 <= b <= 126 or b >= 128)
    return printable / max(1, len(sample)) > 0.90


def iter_text_sources(path: Path) -> Iterator[Tuple[str, str]]:
    if path.is_dir():
        for child in sorted(path.rglob("*")):
            if not child.is_file():
                continue
            try:
                data = child.read_bytes()
            except OSError:
                continue
            if _looks_textual(data, child.suffix):
                yield str(child), data.decode("utf-8", errors="replace")
        return

    if path.suffix.lower() == ".zip":
        with zipfile.ZipFile(path, "r") as zf:
            for info in zf.infolist():
                if info.is_dir() or info.file_size > 32 * 1024 * 1024:
                    continue
                try:
                    data = zf.read(info)
                except Exception:
                    continue
                suffix = Path(info.filename).suffix
                if _looks_textual(data, suffix):
                    yield f"{path}!{info.filename}", data.decode("utf-8", errors="replace")
        return

    data = path.read_bytes()
    if not _looks_textual(data, path.suffix):
        raise ValueError(f"Input does not look like a text/log artifact: {path}")
    yield str(path), data.decode("utf-8", errors="replace")


@dataclass
class FoundValue:
    value: object
    source: str
    excerpt: str


@dataclass
class TruthResult:
    input: str
    declared_dark_frame: bool
    fields: Dict[str, object]
    sources: Dict[str, str]
    excerpts: Dict[str, str]
    derived: Dict[str, object]
    warnings: List[str]
    interpretation: List[str]

    def to_dict(self) -> dict:
        return asdict(self)


def _set(found: Dict[str, FoundValue], key: str, value, source: str, excerpt: str) -> None:
    found[key] = FoundValue(value=value, source=source, excerpt=excerpt.strip()[:500])


def parse_truth(path: Path, declared_dark_frame: bool = False) -> TruthResult:
    found: Dict[str, FoundValue] = {}
    warnings: List[str] = []
    seen_sources = 0

    for source, text in iter_text_sources(path):
        seen_sources += 1
        for match in KV_RE.finditer(text):
            key = _canonical_key(match.group("key"))
            if key in set(KEY_ALIASES.values()) or key.startswith("spectra_pass0") or key.startswith("scene_black"):
                _set(found, key, _parse_scalar(match.group("value")), source, match.group(0))

        for line in text.splitlines():
            vector = VECTOR_RE.search(line)
            if vector:
                values = [float(vector.group(i)) for i in range(1, 5)]
                for canonical, label_re in VECTOR_LABEL_PATTERNS:
                    if label_re.search(line):
                        _set(found, canonical, values, source, line)
                        break

            if re.search(r"(?:bias|residual|black)", line, re.I):
                labeled = CFA_LABELED_RE.search(line)
                if labeled and "channel_bias_before" not in found:
                    values = [float(labeled.group(i)) for i in range(1, 5)]
                    _set(found, "channel_bias_before", values, source, line)

    if seen_sources == 0:
        warnings.append("No readable text sources were found in the supplied artifact.")

    fields = {k: v.value for k, v in found.items()}
    sources = {k: v.source for k, v in found.items()}
    excerpts = {k: v.excerpt for k, v in found.items()}

    vector_key: Optional[str] = None
    for candidate in ("channel_bias_before", "channel_bias_after"):
        value = fields.get(candidate)
        if isinstance(value, list) and len(value) == 4:
            vector_key = candidate
            break

    derived: Dict[str, object] = {}
    if vector_key:
        r, g1, g2, b = [float(x) for x in fields[vector_key]]
        mean_green = (g1 + g2) / 2.0
        mean_rb = (r + b) / 2.0
        derived.update({
            "vector_used": vector_key,
            "mean_green": mean_green,
            "mean_red_blue": mean_rb,
            "green_excess": mean_green - mean_rb,
            "green_split_g1_minus_g2": g1 - g2,
        })
    else:
        warnings.append("No four-channel bias/residual vector was found; derived CFA residual metrics are unavailable.")

    interpretation: List[str] = []
    if declared_dark_frame:
        interpretation.append(
            "Input was explicitly declared a covered-lens/dark-frame capture. Any reported CFA imbalance is a dark-frame residual measurement only; this tool does not infer or apply a correction."
        )
    else:
        interpretation.append(
            "Input was not declared a dark frame. Scene-derived green excess must not be interpreted as an electronic black-level error."
        )

    if fields.get("scene_black_metadata_authoritative") is True:
        interpretation.append("Camera black metadata is reported as authoritative by the captured diagnostics.")
    if fields.get("scene_black_image_mutation_allowed") is False:
        interpretation.append("Scene-derived black image mutation is reported as disabled by the captured diagnostics.")

    return TruthResult(
        input=str(path),
        declared_dark_frame=declared_dark_frame,
        fields=fields,
        sources=sources,
        excerpts=excerpts,
        derived=derived,
        warnings=warnings,
        interpretation=interpretation,
    )


def format_human(result: TruthResult) -> str:
    f = result.fields
    d = result.derived
    out: List[str] = []
    out.append("BnCam RAW BLACK TRUTH")
    out.append("=====================")
    out.append(f"Input: {result.input}")
    out.append(f"Declared dark frame: {'yes' if result.declared_dark_frame else 'no'}")

    ordered = [
        ("scene_black_authority_mode", "Authority mode"),
        ("scene_black_metadata_authoritative", "Metadata authoritative"),
        ("scene_black_image_mutation_allowed", "Image mutation allowed"),
        ("spectra_pass0_applied", "Pass 0 applied"),
        ("spectra_pass0_skip_reason", "Pass 0 skip reason"),
        ("classification", "Classification"),
        ("spectra_pass0_classification", "Pass 0 classification"),
        ("black_level", "Black level"),
        ("dynamic_black_level", "Dynamic black level"),
        ("static_black_level", "Static black level"),
        ("channel_bias_before", "Channel bias/residual before [R,G1,G2,B]"),
        ("applied_channel_bias", "Applied channel bias [R,G1,G2,B]"),
        ("channel_bias_after", "Channel bias/residual after [R,G1,G2,B]"),
        ("channel_bias_confidence", "Bias confidence"),
        ("spectra_pass0_input_energy", "Pass 0 input energy"),
        ("spectra_pass0_output_energy", "Pass 0 output energy"),
    ]
    out.append("")
    out.append("Captured fields")
    out.append("---------------")
    any_field = False
    for key, label in ordered:
        if key in f:
            any_field = True
            value = f[key]
            if isinstance(value, list):
                value = "[" + ", ".join(f"{float(x):.6g}" for x in value) + "]"
            out.append(f"{label}: {value}")
    if not any_field:
        out.append("No known RAW-black truth fields were parsed.")

    if d:
        out.append("")
        out.append("Derived CFA residual metrics")
        out.append("----------------------------")
        out.append(f"Vector used: {d['vector_used']}")
        out.append(f"Mean green: {d['mean_green']:.6f}")
        out.append(f"Mean red/blue: {d['mean_red_blue']:.6f}")
        out.append(f"Green excess: {d['green_excess']:.6f}")
        out.append(f"G1-G2 split: {d['green_split_g1_minus_g2']:.6f}")

    out.append("")
    out.append("Interpretation guardrail")
    out.append("------------------------")
    for item in result.interpretation:
        out.append(f"- {item}")
    for item in result.warnings:
        out.append(f"WARNING: {item}")
    return "\n".join(out)


def dump_json(result: TruthResult, path: Path) -> None:
    path.write_text(json.dumps(result.to_dict(), indent=2, sort_keys=True), encoding="utf-8")

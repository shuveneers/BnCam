import json
import tempfile
import unittest
from pathlib import Path
from prepare_single_frame_replay import prepare


class ReplayFixtureTest(unittest.TestCase):
    def test_repository_fixtures_are_deterministic_and_geometry_matches_payload(self):
        fixtures = Path(__file__).resolve().parents[1] / "src/test/fixtures/raw"
        for source in sorted(fixtures.glob("*.json")):
            with self.subTest(fixture=source.name), tempfile.TemporaryDirectory() as tmp:
                destination = Path(tmp)
                before = prepare(source, destination)
                contents = {p.name: p.read_bytes() for p in destination.iterdir()}
                self.assertEqual(before, prepare(source, destination))
                self.assertEqual(contents, {p.name: p.read_bytes() for p in destination.iterdir()})
                fixture = json.loads(source.read_text())
                stride = int((destination / "geometry.txt").read_text().split()[2])
                self.assertEqual(fixture["height"] * stride, len(contents["raw.bin"]))

    def test_malformed_fixture_cannot_produce_partial_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "bad.json"
            source.write_text(json.dumps({"width": 2, "height": 2, "sensorCfa": 5,
                                         "white": 1023, "positionalBlack": [0] * 4, "samples": [0] * 4}))
            destination = Path(tmp) / "export"
            with self.assertRaises(ValueError):
                prepare(source, destination)
            self.assertFalse(destination.exists())


if __name__ == "__main__":
    unittest.main()

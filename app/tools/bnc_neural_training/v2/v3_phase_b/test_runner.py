import tempfile
import unittest
from pathlib import Path

from ..checkpoint import load
from ..data import read_corpus
from .run import MANIFEST, PHASE_A_ROOT, copy_checkpoint, valid_checkpoints, trim_recovery


class CheckpointSelectionTests(unittest.TestCase):
    def test_invalid_latest_falls_back_to_valid_recovery(self):
        _, corpus = read_corpus(MANIFEST)
        source = PHASE_A_ROOT / 'A/checkpoints/latest.pt'
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            recovery = directory / 'recovery_step_002000.pt'
            copy_checkpoint(source, recovery)
            (directory / 'latest.pt').write_bytes(b'broken checkpoint')
            (directory / 'latest.pt.sha256').write_text('0'*64+'\n')
            valid, invalid = valid_checkpoints(directory, 'A', corpus)
            self.assertEqual(valid[0][0], 2000)
            self.assertEqual(valid[0][2], recovery)
            self.assertEqual(len(invalid), 1)
            self.assertEqual(load(source)['step'], 2000)

    def test_recovery_retention_keeps_two_latest_steps(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for step in (3000, 4000, 5000):
                path = directory / f'recovery_step_{step:06d}.pt'
                path.write_bytes(b'evidence')
                path.with_suffix('.pt.sha256').write_text('hash')
            trim_recovery(directory)
            self.assertEqual([p.name for p in directory.glob('*.pt')],
                             ['recovery_step_004000.pt', 'recovery_step_005000.pt'])


if __name__ == '__main__':
    unittest.main()

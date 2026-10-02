#!/usr/bin/env python3
"""Regression tests for upstream_contract.py: run against a scratch copy of
the watched upstream files and mutate them to prove drift is classified."""
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent.parent
TOOL = HERE / "upstream_contract.py"
sys.path.insert(0, str(HERE))
import upstream_contract as uc  # noqa: E402

WATCHED = sorted({rel for rel, *_ in uc.VALUES.values()} | {rel for rel, _ in uc.PRESENCE.values()}
                 | {rel for rel, *_ in uc.STRUCTS.values()})


class ContractDrift(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        for rel in WATCHED:
            (self.tmp / rel).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy(REPO / rel, self.tmp / rel)

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def run_check(self):
        return subprocess.run([sys.executable, str(TOOL), "--repo", str(self.tmp)],
                              capture_output=True, text=True).returncode

    def mutate(self, rel, pattern, repl):
        p = self.tmp / rel
        text, n = re.subn(pattern, repl, p.read_text())
        self.assertGreater(n, 0, f"fixture pattern not found in {rel}")
        p.write_text(text)

    def test_unchanged_tree_passes(self):
        self.assertEqual(self.run_check(), 0)

    def test_comment_only_change_is_ignored(self):
        self.mutate(f"{uc.SRV}/vital_signs.rs", r"/// Overall signal quality", "/// reworded comment")
        self.assertEqual(self.run_check(), 0)

    def test_port_change_is_auto_updatable(self):
        self.mutate(f"{uc.SRV}/main.rs", r'default_value = "8765"\)\]', 'default_value = "9000")]')
        self.assertEqual(self.run_check(), 3)

    def test_packed_struct_change_needs_review(self):
        self.mutate(f"{uc.FW}/edge_processing.h", r"uint8_t\s+n_persons;", "uint16_t n_persons;")
        self.assertEqual(self.run_check(), 4)

    def test_removed_field_the_app_reads_needs_review(self):
        self.mutate(f"{uc.SRV}/vital_signs.rs", r"pub heart_rate_bpm:", "pub hr_bpm:")
        self.assertEqual(self.run_check(), 4)

    def test_added_optional_field_is_not_blocking(self):
        self.mutate(f"{uc.SRV}/vital_signs.rs", r"pub signal_quality: f64,", "pub signal_quality: f64,\n    pub extra: f64,")
        self.assertEqual(self.run_check(), 3)

    def test_vanished_marker_needs_review(self):
        self.mutate(f"{uc.SRV}/host_validation.rs", r"DNS-rebinding", "rebinding")
        self.assertEqual(self.run_check(), 4)


if __name__ == "__main__":
    unittest.main()

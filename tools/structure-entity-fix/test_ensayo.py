"""Offline launch-classpath regressions; no server or network access."""
import hashlib
from pathlib import Path
import tempfile
import unittest
import zipfile
from ensayo import CASES, runtime_libraries


class RuntimeLibrariesTest(unittest.TestCase):
    def test_command_values_match_recorded_26_2_rotation_serialization(self):
        # Rotation.CLOCKWISE_180 is serialized as "180", unlike the other enum values.
        self.assertEqual({'none', 'clockwise_90', '180', 'counterclockwise_90'},
                         {rotation for _, rotation, _ in CASES})

    def fixture(self, root, relative='org/example/lib/2/lib-2.jar', digest=None):
        libs = root / 'libraries'
        path = libs / 'org/example/lib/2/lib-2.jar'
        path.parent.mkdir(parents=True)
        path.write_bytes(b'correct version')
        digest = digest or hashlib.sha256(path.read_bytes()).hexdigest()
        bootstrap = root / 'bootstrap.jar'
        with zipfile.ZipFile(bootstrap, 'w') as jar:
            jar.writestr('META-INF/libraries.list', digest + '\torg.example:lib:2\t' + relative + '\n')
        return bootstrap, libs, path

    def test_uses_manifest_only_and_ignores_unrelated_cache(self):
        with tempfile.TemporaryDirectory() as d:
            boot, libs, expected = self.fixture(Path(d))
            (libs / 'wrong-version.jar').write_bytes(b'incompatible logger')
            self.assertEqual([expected], runtime_libraries(boot, libs))

    def test_rejects_wrong_digest(self):
        with tempfile.TemporaryDirectory() as d:
            boot, libs, _ = self.fixture(Path(d), digest='0' * 64)
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                runtime_libraries(boot, libs)

    def test_rejects_traversal_before_reading(self):
        with tempfile.TemporaryDirectory() as d:
            boot, libs, _ = self.fixture(Path(d), relative='../other.jar')
            with self.assertRaisesRegex(ValueError, 'Unsafe'):
                runtime_libraries(boot, libs)

    def test_rejects_empty_manifest(self):
        with tempfile.TemporaryDirectory() as d:
            boot = Path(d) / 'empty.jar'
            with zipfile.ZipFile(boot, 'w') as jar:
                jar.writestr('META-INF/libraries.list', '')
            with self.assertRaisesRegex(ValueError, 'Empty'):
                runtime_libraries(boot, Path(d))


if __name__ == '__main__':
    unittest.main()

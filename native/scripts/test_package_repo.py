import contextlib
import io
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

from package_repo import package


class PackagingTests(unittest.TestCase):
    def test_links_hash_and_size_match_bundle(self):
        # Synthetic ZIP tests packaging only; its DEX marker is not executable code.
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cs3 = root / "input.cs3"
            with zipfile.ZipFile(cs3, "w") as archive:
                archive.writestr("classes.dex", b"dex\n035\0synthetic")
                archive.writestr("manifest.json", json.dumps({
                    "pluginClassName": "ua.nuvio.rezkadiagnostics.RezkaDiagnosticsPlugin"
                }))
            out = root / "dist"
            with contextlib.redirect_stdout(io.StringIO()):
                package(cs3, "example/test", "native-probe-1", out)
            repo = json.loads((out / "repo.json").read_text(encoding="utf-8"))
            plugin = json.loads((out / "plugins.json").read_text(encoding="utf-8"))[0]
            self.assertEqual(repo["manifestVersion"], 1)
            self.assertEqual(repo["pluginLists"], ["https://github.com/example/test/releases/download/native-probe-1/plugins.json"])
            self.assertEqual(plugin["version"], 3)
            self.assertEqual(plugin["fileSize"], cs3.stat().st_size)
            self.assertTrue(plugin["fileHash"].startswith("sha256-"))
            self.assertEqual((out / "RezkaDiagnostics.cs3").read_bytes(), cs3.read_bytes())

    def test_rejects_malformed_repository_before_reading_binary(self):
        with self.assertRaises(ValueError):
            package(Path("missing.cs3"), "https://github.com/example/test", "v1", Path("unused"))

    def test_rejects_wrong_entrypoint(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            cs3 = root / "wrong.cs3"
            with zipfile.ZipFile(cs3, "w") as archive:
                archive.writestr("classes.dex", b"dex\n035\0synthetic")
                archive.writestr("manifest.json", '{"pluginClassName":"wrong.Plugin"}')
            with self.assertRaisesRegex(ValueError, "entry point"):
                package(cs3, "example/test", "v1", root / "dist")
            self.assertFalse((root / "dist").exists())


if __name__ == "__main__":
    unittest.main()

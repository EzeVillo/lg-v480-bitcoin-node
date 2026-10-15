"""Synthetic privacy checks. Fixtures contain no real device information."""

from contextlib import redirect_stdout
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import check_publication as audit


class PatternTests(unittest.TestCase):
    def test_sensitive_values(self):
        cases = [
            (".".join(map(str, (192, 168, 4, 5))), "IP_ADDRESS"),
            (".".join(map(str, (127, 0, 0, 1))), "IP_ADDRESS"),
            (":".join(["2001", "db8", "", "1234"]), "IP_ADDRESS"),
            (":".join(["", "", "1"]), "IP_ADDRESS"),
            ("a" * 56 + ".onion", "ONION_HOST"),
            ("a" * 16 + ".onion", "ONION_HOST"),
            ("aa" + ":bb:cc:dd:ee:ff", "MAC_ADDRESS"),
            ("ABCD" + "-1234", "CARD_IDENTIFIER"),
            ("u0_" + "a123", "TERMUX_ACCOUNT"),
            ("SHA256:" + "A" * 43, "SSH_IDENTITY"),
            ("-----BEGIN " + "OPENSSH PRIVATE KEY-----", "PRIVATE_KEY"),
            ("ghp_" + "A" * 36, "ACCESS_TOKEN"),
            ("rpcpassword=" + "UNSAFEVALUE", "CREDENTIAL_ASSIGNMENT"),
            ("rpcuser=" + "localuser", "CREDENTIAL_ASSIGNMENT"),
            ("C:" + "\\Users\\someone\\file", "PERSONAL_PATH"),
            ("/" + "home/someone/file", "PERSONAL_PATH"),
            ("someone" + "@example.com", "PERSONAL_EMAIL"),
        ]
        for value, rule in cases:
            with self.subTest(rule=rule):
                self.assertIn(rule, {r for _, r in audit.inspect_text(value.encode())})

    def test_safe_configuration(self):
        for value in ["http://localhost:8080/cgi-bin/api", "rpcpassword=@RPC_PASSWORD@",
                      "/data/data/com.termux/files/home/bin/node-start",
                      "/storage/SD_CARD_ID/Android/data/com.termux/files/bitcoin"]:
            self.assertEqual(audit.inspect_text(value.encode()), [])

    def test_legacy_exception_only_for_loopback(self):
        loopback = ".".join(map(str, (127, 0, 0, 1))).encode()
        private = ".".join(map(str, (192, 168, 4, 5))).encode()
        self.assertEqual(audit.inspect_text(loopback, legacy_loopback=True), [])
        self.assertTrue(audit.inspect_text(private, legacy_loopback=True))

    def test_binary_symlink_and_private_paths(self):
        self.assertEqual(audit.inspect_text(b"text\0data"), [(1, "BINARY_CONTENT")])
        self.assertIsNotNone(audit.file_policy("public/android/app.apk"))
        self.assertIsNotNone(audit.file_policy("public/link.java", "120000"))
        self.assertIsNotNone(audit.file_policy("device-backup/data.md"))


class RepositoryTests(unittest.TestCase):
    def test_index_and_deleted_history_are_scanned_without_echoing_values(self):
        with tempfile.TemporaryDirectory(prefix="v480-audit-") as directory:
            root = Path(directory)
            def git(*args):
                return subprocess.check_output([
                    "git", "-c", "user.name=Privacy test",
                    "-c", "user.email=test@users.noreply.github.com", *args,
                ], cwd=root, stderr=subprocess.PIPE)
            def run(*args):
                output = io.StringIO()
                with patch.object(audit, "ROOT", root), patch.object(sys, "argv", ["audit", *args]), redirect_stdout(output):
                    code = audit.main()
                return code, output.getvalue()

            git("init", "-q")
            (root / "PUBLIC_FILES.txt").write_text(".gitignore\nPUBLIC_FILES.txt\nREADME.md\n")
            (root / ".gitignore").write_text("# fixture\n")
            readme = root / "README.md"
            readme.write_text("Safe fixture\n")
            git("add", ".")
            self.assertEqual(run("--staged")[0], 0)
            git("commit", "-qm", "Safe fixture")

            private = ".".join(map(str, (192, 168, 4, 5)))
            readme.write_text(private + "\n")
            # Working files must not be confused with the staged snapshot.
            self.assertEqual(run("--staged")[0], 0)
            self.assertEqual(run()[0], 1)
            git("add", "README.md")
            code, output = run("--staged")
            self.assertEqual(code, 1)
            self.assertIn("IP_ADDRESS", output)
            self.assertNotIn(private, output)
            git("commit", "-qm", "Synthetic unsafe fixture")

            readme.write_text("Safe fixture\n")
            git("add", "README.md")
            git("commit", "-qm", "Remove synthetic value")
            self.assertEqual(run("--staged")[0], 0)
            code, output = run("--history", "HEAD")
            self.assertEqual(code, 1)
            self.assertIn("IP_ADDRESS", output)
            self.assertNotIn(private, output)


if __name__ == "__main__":
    unittest.main()

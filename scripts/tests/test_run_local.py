import contextlib
import importlib.util
import io
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "scripts"))
spec = importlib.util.spec_from_file_location("run_local", REPO / "scripts/run-local.py")
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class RunLocalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.env_file = Path(self.temp.name) / ".env"
        self.password = secrets.token_urlsafe(48)
        self.admin_password = secrets.token_urlsafe(48)
        self.example = (REPO / ".env.example").read_text(encoding="utf-8")
        self.valid_env = self.example.replace("APP_DOMAIN=15-164-244-37.sslip.io", "APP_DOMAIN=localhost") \
            .replace("HTTPS_PORT=443", "HTTPS_PORT=8443") \
            .replace("POSTGRES_PASSWORD=needed", "POSTGRES_PASSWORD='" + self.password + "'") \
            .replace("POSTGRES_ADMIN_PASSWORD=needed", "POSTGRES_ADMIN_PASSWORD='" + self.admin_password + "'") \
            .replace("DATA_GO_KR_SERVICE_KEY=needed", "DATA_GO_KR_SERVICE_KEY=fixture-only-not-an-api-key")
        self.env_file.write_text(self.valid_env, encoding="utf-8")

    def invoke(self, arguments=None):
        output, errors = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(errors):
            result = runner.main(arguments or ["--env-file", str(self.env_file)])
        self.assertNotIn(self.password, output.getvalue() + errors.getvalue())
        self.assertNotIn(self.admin_password, output.getvalue() + errors.getvalue())
        return result, output.getvalue(), errors.getvalue()

    @patch.object(runner.shutil, "which", return_value="docker")
    @patch.object(runner.subprocess, "run")
    def test_start_uses_explicit_local_files_and_preserves_existing_settings(self, run, which):
        before = self.env_file.read_bytes()
        with patch.dict(os.environ, {"POSTGRES_PASSWORD": "incorrect-process-override", "DATA_GO_KR_ENABLED": "false"}):
            result, output, errors = self.invoke()
        self.assertEqual(0, result)
        self.assertEqual("", errors)
        self.assertIn("https://localhost:8443/swagger-ui/index.html", output)
        self.assertEqual(before, self.env_file.read_bytes())
        prefix = ["docker", "compose", "--env-file", str(self.env_file), "-f", str(REPO / "compose.yaml")]
        self.assertEqual(prefix + ["config", "--quiet"], run.call_args_list[0].args[0])
        self.assertEqual(prefix + ["up", "--detach", "--build", "--wait"], run.call_args_list[1].args[0])
        for call in run.call_args_list:
            self.assertEqual(REPO, call.kwargs["cwd"])
            self.assertTrue(call.kwargs["check"])
            self.assertNotIn("POSTGRES_PASSWORD", call.kwargs["env"])
            self.assertNotIn("DATA_GO_KR_ENABLED", call.kwargs["env"])

    @patch.object(runner.subprocess, "run")
    def test_missing_settings_are_not_created_or_started(self, run):
        self.env_file.unlink()
        result, _, _ = self.invoke()
        self.assertEqual(1, result)
        self.assertFalse(self.env_file.exists())
        run.assert_not_called()

    @patch.object(runner.subprocess, "run")
    def test_needed_values_block_start_without_replacing_passwords(self, run):
        self.env_file.write_text(self.example, encoding="utf-8")
        before = self.env_file.read_bytes()
        result, _, errors = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("DATA_GO_KR_SERVICE_KEY", errors)
        self.assertEqual(before, self.env_file.read_bytes())
        run.assert_not_called()

    @patch.object(runner.subprocess, "run")
    def test_external_domain_is_rejected_without_changing_settings(self, run):
        self.env_file.write_text(self.valid_env.replace("APP_DOMAIN=localhost", "APP_DOMAIN=example.com"), encoding="utf-8")
        before = self.env_file.read_bytes()
        result, _, errors = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("localhost", errors)
        self.assertEqual(before, self.env_file.read_bytes())
        run.assert_not_called()

    @patch.object(runner.shutil, "which", return_value=None)
    @patch.object(runner.subprocess, "run")
    def test_missing_docker_reports_failure(self, run, which):
        result, _, errors = self.invoke()
        self.assertEqual(1, result)
        self.assertIn("Docker", errors)
        run.assert_not_called()

    @patch.object(runner.shutil, "which", return_value="docker")
    @patch.object(runner.subprocess, "run", side_effect=subprocess.CalledProcessError(7, ["docker", "compose"]))
    def test_compose_failure_propagates_and_cannot_report_a_running_server(self, run, which):
        result, output, errors = self.invoke()
        self.assertEqual(7, result)
        self.assertNotIn("Swagger:", output)
        self.assertIn("실패", errors)
        self.assertEqual(1, run.call_count)


if __name__ == "__main__":
    unittest.main()

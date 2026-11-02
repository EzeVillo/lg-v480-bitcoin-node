"""Exercise the CGI with simulated RPC/process failures, without stopping Core.

Run on POSIX with Python, or set NODE_API_TEST_SSH to a Termux SSH host alias.
"""

import base64
import json
import os
from pathlib import Path
import shlex
import subprocess
import unittest


ROOT = Path(__file__).resolve().parent.parent
SOURCE = (ROOT / "public/node/api.cgi").read_text(encoding="utf-8")
SSH = os.environ.get("NODE_API_TEST_SSH")
SUPPORTED = bool(SSH or os.name == "posix")


@unittest.skipUnless(SUPPORTED, "Use POSIX or NODE_API_TEST_SSH")
class NodeApiTests(unittest.TestCase):
    def run_api(self, scenario, process="running", query=""):
        prefix = "/data/data/com.termux/files/usr" if SSH else "/usr"
        prelude = f"""
fixture_prefix={shlex.quote(prefix)}
fixture_scenario={shlex.quote(scenario)}
fixture_process={shlex.quote(process)}
QUERY_STRING={shlex.quote(query)}
TMPDIR=${{TMPDIR:-/tmp}}
export QUERY_STRING TMPDIR
mock_rpc() {{
  for fixture_method do :; done
  if [ "$fixture_method" = getblockchaininfo ] || [ "$fixture_method" = -getinfo ]; then
    case "$fixture_scenario" in
      warmup) printf 'error code: -28\\nerror message:\\nLoading block index...\\n' >&2; return 1 ;;
      timeout) printf 'error: timeout on transient RPC request\\n' >&2; return 1 ;;
      full_queue) printf 'error: Server response: Work queue depth exceeded\\n' >&2; return 1 ;;
      auth) printf 'error: Authorization failed: Incorrect rpcuser or rpcpassword\\n' >&2; return 1 ;;
      rpc_error) printf 'error code: -1\\nerror message:\\nInternal error\\n' >&2; return 1 ;;
    esac
  fi
  case "$fixture_method" in
    getblockchaininfo) printf '{{\\n  "chain": "main",\\n  "blocks": 42,\\n  "headers": 42,\\n  "initialblockdownload": false,\\n  "verificationprogress": 1\\n}}\\n' ;;
    getnetworkinfo)
      [ "$fixture_scenario" != network_failure ] || return 1
      printf '{{\\n  "version": 320100,\\n  "connections_in": 1,\\n  "connections_out": 10\\n}}\\n' ;;
    getmempoolinfo) printf '{{\\n  "size": 7,\\n  "bytes": 2000,\\n  "maxmempool": 20000000\\n}}\\n' ;;
    -getinfo) printf 'fixture details\\n' ;;
  esac
}}
pidof() {{ [ "$fixture_process" = running ]; }}
ps() {{
  case "$fixture_process" in
    fallback) printf 'bitcoind\\n' ;;
    misleading) printf 'runsv bitcoind\\nnot-bitcoind\\n' ;;
  esac
}}
sv() {{
  case "$*" in
    *bitcoind*) printf 'run: bitcoind: (pid 123) 12345s; run: log: (pid 124) 12345s\\n' ;;
    *) printf 'run: tor: (pid 125) 12345s\\n' ;;
  esac
}}
"""
        source = SOURCE.replace(
            "node_prefix='/data/data/com.termux/files/usr'", 'node_prefix="$fixture_prefix"'
        ).replace(
            "node_home='/data/data/com.termux/files/home'", "node_home='/nonexistent-node-api-fixture'"
        ).replace('bitcoin_cli="$node_prefix/bin/bitcoin-cli"', "bitcoin_cli=mock_rpc")
        if SSH:
            command = ["ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=8", SSH, "sh -s"]
        else:
            command = ["sh", "-s"]
        result = subprocess.run(command, input=(prelude + source).encode(),
                                capture_output=True, timeout=40, check=True)
        headers, body = result.stdout.split(b"\r\n\r\n", 1)
        self.assertIn(b"Cache-Control: no-store", headers)
        return json.loads(body)

    def test_healthy_rpc(self):
        data = self.run_api("healthy")
        self.assertEqual(data["bitcoin"], "activo")
        self.assertIsNone(data["rpc_error_code"])
        self.assertEqual(data["blocks"], 42)
        self.assertEqual(data["core_version"], 320100)
        self.assertEqual(data["mempool_max"], 20000000)
        self.assertEqual(data["core_uptime"], 12345)

    def test_only_explicit_warmup_means_starting(self):
        data = self.run_api("warmup")
        self.assertEqual(data["bitcoin"], "iniciando")
        self.assertEqual(data["rpc_error_code"], -28)
        self.assertEqual(data["blocks"], 0)

    def test_timeout_and_queue_overload_are_not_startup(self):
        for scenario in ("timeout", "full_queue"):
            with self.subTest(scenario=scenario):
                data = self.run_api(scenario)
                self.assertEqual(data["bitcoin"], "sin_respuesta")
                self.assertIsNone(data["rpc_error_code"])

    def test_authentication_and_rpc_errors_are_distinct(self):
        for scenario in ("auth", "rpc_error"):
            with self.subTest(scenario=scenario):
                self.assertEqual(self.run_api(scenario)["bitcoin"], "error_rpc")

    def test_stopped_process_and_false_name_matches(self):
        for process in ("stopped", "misleading"):
            with self.subTest(process=process):
                self.assertEqual(self.run_api("timeout", process)["bitcoin"], "detenido")

    def test_exact_process_name_fallback(self):
        self.assertEqual(self.run_api("timeout", "fallback")["bitcoin"], "sin_respuesta")

    def test_stale_warmup_with_dead_process(self):
        self.assertEqual(self.run_api("warmup", "stopped")["bitcoin"], "detenido")

    def test_optional_rpc_failure_keeps_chain_active(self):
        data = self.run_api("network_failure")
        self.assertEqual(data["bitcoin"], "activo")
        self.assertEqual(data["blocks"], 42)

    def test_detail_query_is_exact(self):
        self.assertIn("bitcoin", self.run_api("healthy", query="detail=10"))
        data = self.run_api("healthy", query="detail=1")
        self.assertEqual(base64.b64decode(data["detail_b64"]).strip(), b"fixture details")


if __name__ == "__main__":
    unittest.main()

"""Unit tests for meta_model_client. Mock transport only; synthetic data only.

Run from this directory:
    python3 -m unittest -v test_meta_model_client
"""

import io
import json
import os
import socket
import unittest
import urllib.error
from datetime import datetime, timezone
from unittest import mock

import meta_model_client as mm

FAKE_KEY = "sk-test-SYNTHETIC-000"
ENDPOINT = "https://mock.invalid/v1/chat/completions"
MODEL = "synthetic-model"
PROMPT = "Synthetic prompt: say OK"
VALID_ENV = {
    mm.ENV_ENDPOINT: ENDPOINT,
    mm.ENV_MODEL: MODEL,
    mm.ENV_KEY: FAKE_KEY,
}

_ORIGINAL_TRANSPORT = mm.urllib_transport


def _forbidden_transport(*args, **kwargs):
    raise AssertionError("The real urllib transport must never be used in tests.")


def setUpModule():
    mm.urllib_transport = _forbidden_transport


def tearDownModule():
    mm.urllib_transport = _ORIGINAL_TRANSPORT


def ok_body(content="OK"):
    return json.dumps({"choices": [{"message": {"role": "assistant", "content": content}}]}).encode()


class FakeTransport:
    """Returns/raises queued responses in order and records every call."""

    def __init__(self, *responses):
        self.responses = list(responses)
        self.calls = []

    def __call__(self, method, url, headers, body, timeout):
        self.calls.append({"method": method, "url": url, "headers": dict(headers),
                           "body": body, "timeout": timeout})
        if not self.responses:
            raise AssertionError("FakeTransport called more times than expected")
        item = self.responses.pop(0)
        if isinstance(item, BaseException):
            raise item
        return item


def make_client(*responses, **kwargs):
    transport = FakeTransport(*responses)
    sleeps = []
    kwargs.setdefault("sleep", sleeps.append)
    kwargs.setdefault("rng", lambda: 0.5)
    client = mm.MetaModelClient(mm.load_config(VALID_ENV), transport=transport, **kwargs)
    return client, transport, sleeps


# ---------------------------------------------------------------- config
class ConfigTests(unittest.TestCase):
    def test_valid_config(self):
        cfg = mm.load_config(VALID_ENV)
        self.assertEqual(cfg.endpoint, ENDPOINT)
        self.assertEqual(cfg.model, MODEL)
        self.assertEqual(cfg.host, "mock.invalid")

    def test_missing_each_var_raises_config_error(self):
        for name in VALID_ENV:
            with self.subTest(missing=name):
                env = dict(VALID_ENV)
                del env[name]
                with self.assertRaises(mm.ConfigError) as cm:
                    mm.load_config(env)
                self.assertIn(name, str(cm.exception))
                self.assertNotIn(FAKE_KEY, str(cm.exception))

    def test_blank_var_raises_config_error(self):
        for name in VALID_ENV:
            for blank in ("", "   ", "\t\n"):
                with self.subTest(blank=name, value=repr(blank)):
                    env = dict(VALID_ENV, **{name: blank})
                    with self.assertRaises(mm.ConfigError):
                        mm.load_config(env)

    def test_empty_env_raises(self):
        with self.assertRaises(mm.ConfigError):
            mm.load_config({})

    def test_reads_os_environ_when_env_not_given(self):
        with mock.patch.dict(os.environ, VALID_ENV, clear=True):
            self.assertEqual(mm.load_config().model, MODEL)
        with mock.patch.dict(os.environ, {}, clear=True):
            with self.assertRaises(mm.ConfigError):
                mm.load_config()

    def test_http_endpoint_rejected(self):
        env = dict(VALID_ENV, **{mm.ENV_ENDPOINT: "http://mock.invalid/v1/chat/completions"})
        with self.assertRaises(mm.ConfigError) as cm:
            mm.load_config(env)
        self.assertIn("https", str(cm.exception))
        self.assertNotIn(FAKE_KEY, str(cm.exception))

    def test_other_bad_endpoints_rejected(self):
        for bad in ("mock.invalid/v1", "ftp://mock.invalid/x", "https:///nohost",
                    "https://user:pw@mock.invalid/v1"):
            with self.subTest(endpoint=bad):
                with self.assertRaises(mm.ConfigError):
                    mm.load_config(dict(VALID_ENV, **{mm.ENV_ENDPOINT: bad}))

    def test_key_with_internal_whitespace_rejected_without_echo(self):
        bad_key = "sk-test-SYNTHETIC 000"
        with self.assertRaises(mm.ConfigError) as cm:
            mm.load_config(dict(VALID_ENV, **{mm.ENV_KEY: bad_key}))
        self.assertNotIn(bad_key, str(cm.exception))

    def test_client_requires_config_instance(self):
        with self.assertRaises(mm.ConfigError):
            mm.MetaModelClient(VALID_ENV, transport=FakeTransport())


# ---------------------------------------------------------------- request
class RequestTests(unittest.TestCase):
    def test_request_body_and_headers(self):
        client, transport, _ = make_client((200, {}, ok_body()))
        self.assertEqual(client.complete(PROMPT), "OK")
        self.assertEqual(len(transport.calls), 1)
        call = transport.calls[0]
        self.assertEqual(call["method"], "POST")
        self.assertEqual(call["url"], ENDPOINT)
        body = json.loads(call["body"].decode("utf-8"))
        self.assertEqual(body["model"], MODEL)
        self.assertEqual(body["max_tokens"], 8)
        self.assertEqual(body["temperature"], 0)
        self.assertEqual(body["messages"], [{"role": "user", "content": PROMPT}])
        self.assertEqual(set(body), {"model", "messages", "max_tokens", "temperature"})
        self.assertEqual(call["headers"]["Authorization"], "Bearer " + FAKE_KEY)
        self.assertEqual(call["headers"]["Content-Type"], "application/json")

    def test_build_request_defaults(self):
        headers, body = mm.build_request(mm.load_config(VALID_ENV), PROMPT)
        payload = json.loads(body)
        self.assertEqual((payload["max_tokens"], payload["temperature"]), (8, 0))


# ---------------------------------------------------------------- parsing
class ParseTests(unittest.TestCase):
    def test_success_parsed(self):
        self.assertEqual(mm.parse_response(ok_body("hello")), "hello")

    def test_malformed_json(self):
        with self.assertRaises(mm.ResponseFormatError):
            mm.parse_response(b"{not json")

    def test_non_utf8_body(self):
        with self.assertRaises(mm.ResponseFormatError):
            mm.parse_response(b"\xff\xfe\x00garbage")

    def test_empty_body(self):
        for body in (b"", None):
            with self.subTest(body=body):
                with self.assertRaises(mm.ResponseFormatError):
                    mm.parse_response(body)

    def test_missing_or_empty_choices(self):
        for payload in ({}, {"choices": []}, {"choices": None}, {"choices": "x"}):
            with self.subTest(payload=payload):
                with self.assertRaises(mm.ResponseFormatError):
                    mm.parse_response(json.dumps(payload).encode())

    def test_bad_message_shape(self):
        for payload in ({"choices": ["x"]}, {"choices": [{}]}, {"choices": [{"message": "x"}]}):
            with self.subTest(payload=payload):
                with self.assertRaises(mm.ResponseFormatError):
                    mm.parse_response(json.dumps(payload).encode())

    def test_non_string_content(self):
        for content in (None, 42, ["a"], {"text": "a"}, True):
            with self.subTest(content=content):
                body = json.dumps({"choices": [{"message": {"content": content}}]}).encode()
                with self.assertRaises(mm.ResponseFormatError):
                    mm.parse_response(body)

    def test_top_level_not_object(self):
        with self.assertRaises(mm.ResponseFormatError):
            mm.parse_response(b"[1, 2, 3]")

    def test_large_malformed_body_truncated_and_redacted(self):
        body = ("Bearer " + FAKE_KEY + " " + "x" * 10000).encode()
        with self.assertRaises(mm.ResponseFormatError) as cm:
            mm.parse_response(body, secrets=(FAKE_KEY,))
        msg = str(cm.exception)
        self.assertLess(len(msg), 500)
        self.assertIn("truncated", msg)
        self.assertNotIn(FAKE_KEY, msg)

    def test_client_success_and_format_error(self):
        client, _, _ = make_client((200, {}, ok_body("fine")))
        self.assertEqual(client.complete(PROMPT), "fine")
        client, _, _ = make_client((200, {}, b"<html>oops</html>"))
        with self.assertRaises(mm.ResponseFormatError):
            client.complete(PROMPT)


# ---------------------------------------------------------------- HTTP errors
class ErrorMappingTests(unittest.TestCase):
    def test_401_and_403_auth_error_without_key(self):
        for status in (401, 403):
            with self.subTest(status=status):
                echo = json.dumps({"error": "bad key " + FAKE_KEY,
                                   "hdr": "Authorization: Bearer " + FAKE_KEY}).encode()
                client, transport, sleeps = make_client((status, {}, echo), max_retries=2)
                with self.assertRaises(mm.AuthError) as cm:
                    client.complete(PROMPT)
                self.assertEqual(cm.exception.status, status)
                self.assertNotIn(FAKE_KEY, str(cm.exception))
                self.assertNotIn(FAKE_KEY, repr(cm.exception))
                self.assertEqual(len(transport.calls), 1)
                self.assertEqual(sleeps, [])

    def test_other_4xx_api_error(self):
        for status in (400, 404, 422):
            with self.subTest(status=status):
                client, transport, _ = make_client((status, {}, b'{"error":"x"}'), max_retries=2)
                with self.assertRaises(mm.APIError) as cm:
                    client.complete(PROMPT)
                self.assertIs(type(cm.exception), mm.APIError)
                self.assertEqual(len(transport.calls), 1)

    def test_500_server_error_not_retried(self):
        client, transport, sleeps = make_client((500, {}, b"boom"), max_retries=3)
        with self.assertRaises(mm.ServerError):
            client.complete(PROMPT)
        self.assertEqual(len(transport.calls), 1)
        self.assertEqual(sleeps, [])

    def test_502_server_error_not_retried(self):
        client, transport, _ = make_client((502, {}, b""), max_retries=3)
        with self.assertRaises(mm.ServerError):
            client.complete(PROMPT)
        self.assertEqual(len(transport.calls), 1)

    def test_503_not_retried_by_default(self):
        client, transport, sleeps = make_client((503, {}, b""), (200, {}, ok_body()))
        with self.assertRaises(mm.ServerError):
            client.complete(PROMPT)
        self.assertEqual(len(transport.calls), 1)
        self.assertEqual(sleeps, [])

    def test_503_retried_when_enabled(self):
        client, transport, sleeps = make_client((503, {}, b""), (200, {}, ok_body("ok2")),
                                                max_retries=1)
        self.assertEqual(client.complete(PROMPT), "ok2")
        self.assertEqual(len(transport.calls), 2)
        self.assertEqual(len(sleeps), 1)
        self.assertGreater(sleeps[0], 0)
        self.assertLessEqual(sleeps[0], mm.DEFAULT_RETRY_AFTER_CAP)

    def test_503_exhausted_raises_server_error(self):
        client, transport, sleeps = make_client(*[(503, {}, b"")] * 3, max_retries=2)
        with self.assertRaises(mm.ServerError):
            client.complete(PROMPT)
        self.assertEqual(len(transport.calls), 3)
        self.assertEqual(len(sleeps), 2)

    def test_backoff_is_exponential_with_jitter_and_capped(self):
        client, _, _ = make_client(rng=lambda: 1.0, backoff_base=1.0, retry_after_cap=30.0)
        self.assertEqual([client._backoff(a, None) for a in range(7)],
                         [1.0, 2.0, 4.0, 8.0, 16.0, 30.0, 30.0])
        client, _, _ = make_client(rng=lambda: 0.0, backoff_base=1.0)
        self.assertEqual(client._backoff(2, None), 2.0)

    def test_non_timeout_transport_error_not_retried(self):
        exc = urllib.error.URLError(ConnectionRefusedError("refused"))
        client, transport, _ = make_client(exc, max_retries=3)
        with self.assertRaises(mm.TransportError):
            client.complete(PROMPT)
        self.assertEqual(len(transport.calls), 1)


# ---------------------------------------------------------------- timeouts
class TimeoutTests(unittest.TestCase):
    def test_timeouts_raise_request_timeout_without_retry(self):
        cases = [socket.timeout("timed out"), TimeoutError("timed out"),
                 urllib.error.URLError(socket.timeout("timed out")),
                 urllib.error.URLError("timed out"),
                 mm.RequestTimeout("pre-mapped")]
        for exc in cases:
            with self.subTest(exc=repr(exc)):
                client, transport, sleeps = make_client(exc, (200, {}, ok_body()),
                                                        max_retries=3)
                with self.assertRaises(mm.RequestTimeout) as cm:
                    client.complete(PROMPT)
                self.assertEqual(len(transport.calls), 1)
                self.assertEqual(sleeps, [])
                self.assertNotIn(FAKE_KEY, str(cm.exception))


# ---------------------------------------------------------------- 429
class RateLimitTests(unittest.TestCase):
    def test_429_not_retried_by_default(self):
        client, transport, sleeps = make_client((429, {"Retry-After": "5"}, b""),
                                                (200, {}, ok_body()))
        with self.assertRaises(mm.RateLimitError) as cm:
            client.complete(PROMPT)
        self.assertEqual(cm.exception.retry_after, 5.0)
        self.assertEqual(len(transport.calls), 1)
        self.assertEqual(sleeps, [])

    def test_429_retry_after_capped_then_success(self):
        client, transport, sleeps = make_client(
            (429, {"Retry-After": "120"}, b""), (429, {"retry-after": "120"}, b""),
            (200, {}, ok_body("after-wait")), max_retries=2)
        self.assertEqual(client.complete(PROMPT), "after-wait")
        self.assertEqual(len(transport.calls), 3)
        self.assertEqual(sleeps, [30.0, 30.0])

    def test_429_retry_after_below_cap_honoured(self):
        client, _, sleeps = make_client((429, {"Retry-After": "2"}, b""),
                                        (200, {}, ok_body()), max_retries=2)
        client.complete(PROMPT)
        self.assertEqual(sleeps, [2.0])

    def test_429_exhausted_raises_rate_limit_error(self):
        client, transport, sleeps = make_client(*[(429, {"Retry-After": "120"}, b"")] * 3,
                                                max_retries=2)
        with self.assertRaises(mm.RateLimitError) as cm:
            client.complete(PROMPT)
        self.assertEqual(cm.exception.retry_after, 120.0)
        self.assertEqual(len(transport.calls), 3)
        self.assertEqual(sleeps, [30.0, 30.0])

    def test_429_without_retry_after_uses_backoff(self):
        client, _, sleeps = make_client((429, {}, b""), (200, {}, ok_body()),
                                        max_retries=1, rng=lambda: 1.0)
        client.complete(PROMPT)
        self.assertEqual(sleeps, [1.0])

    def test_parse_retry_after(self):
        now = datetime(2026, 1, 1, 0, 0, 0, tzinfo=timezone.utc)
        self.assertEqual(mm.parse_retry_after("7"), 7.0)
        self.assertEqual(mm.parse_retry_after("-3"), 0.0)
        self.assertEqual(mm.parse_retry_after("Thu, 01 Jan 2026 00:00:10 GMT", now=now), 10.0)
        for bad in (None, "", "soon", "nan", "inf"):
            with self.subTest(bad=bad):
                self.assertIsNone(mm.parse_retry_after(bad))


# ---------------------------------------------------------------- redaction
class RedactionTests(unittest.TestCase):
    def test_key_absent_from_repr_and_str(self):
        client, _, _ = make_client()
        cfg = mm.load_config(VALID_ENV)
        for text in (repr(client), str(client), repr(cfg), str(cfg)):
            self.assertNotIn(FAKE_KEY, text)
            self.assertIn(mm.REDACTED, text)

    def test_key_absent_from_exception_str(self):
        body = ("server echoed " + FAKE_KEY).encode()
        for status, exc_type in ((500, mm.ServerError), (429, mm.RateLimitError),
                                 (400, mm.APIError), (401, mm.AuthError)):
            with self.subTest(status=status):
                client, _, _ = make_client((status, {}, body))
                with self.assertRaises(exc_type) as cm:
                    client.complete(PROMPT)
                self.assertNotIn(FAKE_KEY, str(cm.exception))

    def test_transport_exception_text_redacted(self):
        exc = RuntimeError("failed with header Authorization: Bearer " + FAKE_KEY)
        client, _, _ = make_client(exc)
        with self.assertRaises(mm.TransportError) as cm:
            client.complete(PROMPT)
        self.assertNotIn(FAKE_KEY, str(cm.exception))

    def test_key_absent_from_logs(self):
        client, _, _ = make_client((429, {"Retry-After": "1"}, b""), (200, {}, ok_body()),
                                   max_retries=1)
        with self.assertLogs("meta_model_client", level="DEBUG") as cm:
            client.complete(PROMPT)
        joined = "\n".join(cm.output)
        self.assertNotIn(FAKE_KEY, joined)
        self.assertNotIn("Bearer", joined)
        self.assertNotIn("/v1/chat/completions", joined)
        self.assertIn("mock.invalid", joined)
        self.assertIn("status=429", joined)
        self.assertIn("status=200", joined)

    def test_key_absent_from_timeout_logs(self):
        client, _, _ = make_client(socket.timeout())
        with self.assertLogs("meta_model_client", level="DEBUG") as cm:
            with self.assertRaises(mm.RequestTimeout):
                client.complete(PROMPT)
        self.assertNotIn(FAKE_KEY, "\n".join(cm.output))

    def test_redact_scrubs_secrets_and_bearer(self):
        self.assertEqual(mm.redact("key=" + FAKE_KEY, [FAKE_KEY]), "key=[REDACTED]")
        self.assertEqual(mm.redact("Authorization: Bearer abc.def-123"),
                         "Authorization: Bearer [REDACTED]")
        self.assertEqual(mm.redact("authorization: bearer   XYZ rest"),
                         "authorization: Bearer [REDACTED] rest")
        self.assertNotIn("other-token", mm.redact("Bearer other-token", []))
        self.assertEqual(mm.redact("nothing here", ["", None]), "nothing here")
        self.assertEqual(mm.redact(None), "")


# ---------------------------------------------------------------- dry run / CLI
class DryRunTests(unittest.TestCase):
    def test_dry_run_output_has_no_key(self):
        text = mm.dry_run(mm.load_config(VALID_ENV), PROMPT)
        self.assertNotIn(FAKE_KEY, text)
        self.assertIn("Destination host: mock.invalid", text)
        self.assertIn("Model: synthetic-model", text)
        self.assertIn("Bearer [REDACTED]", text)
        body_line = text.strip().splitlines()[-1]
        self.assertEqual(json.loads(body_line), {
            "model": MODEL, "messages": [{"role": "user", "content": PROMPT}],
            "max_tokens": 8, "temperature": 0})

    def test_cli_dry_run_sends_nothing(self):
        for argv in (["--dry-run"], []):
            with self.subTest(argv=argv):
                transport, out, err = FakeTransport(), io.StringIO(), io.StringIO()
                rc = mm.main(argv, env=VALID_ENV, out=out, err=err, transport=transport)
                self.assertEqual(rc, 0)
                self.assertEqual(transport.calls, [])
                self.assertIn("DRY RUN", out.getvalue())
                self.assertNotIn(FAKE_KEY, out.getvalue() + err.getvalue())

    def test_cli_missing_config_exits_2(self):
        out, err = io.StringIO(), io.StringIO()
        rc = mm.main(["--dry-run"], env={mm.ENV_KEY: FAKE_KEY}, out=out, err=err,
                     transport=FakeTransport())
        self.assertEqual(rc, 2)
        self.assertIn("Config error", err.getvalue())
        self.assertNotIn(FAKE_KEY, out.getvalue() + err.getvalue())

    def test_cli_live_uses_injected_transport_once(self):
        transport, out, err = FakeTransport((200, {}, ok_body("live-ok"))), io.StringIO(), io.StringIO()
        with self.assertLogs("meta_model_client", level="INFO"):
            rc = mm.main(["--live"], env=VALID_ENV, out=out, err=err, transport=transport)
        self.assertEqual(rc, 0)
        self.assertEqual(len(transport.calls), 1)
        self.assertIn("live-ok", out.getvalue())
        self.assertNotIn(FAKE_KEY, out.getvalue() + err.getvalue())

    def test_cli_rejects_dry_run_with_live(self):
        with mock.patch("sys.stderr", new_callable=io.StringIO):
            with self.assertRaises(SystemExit):
                mm.main(["--dry-run", "--live"], env=VALID_ENV, transport=FakeTransport())


if __name__ == "__main__":
    unittest.main()

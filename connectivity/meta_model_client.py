#!/usr/bin/env python3
"""Minimal, stdlib-only client for the "Meta Model API" (hackathon context).

IMPORTANT: The endpoint URL, auth scheme, model IDs and response schema are
NOT verified against official documentation. Nothing destination-specific is
hard-coded; everything comes from environment variables. See README.md for
the list of UNVERIFIED assumptions.

CLI:
    python3 meta_model_client.py --dry-run      # prints request, sends nothing
    python3 meta_model_client.py --live         # sends ONE request (costs credits)

With no flag the CLI behaves like --dry-run. Nothing is sent without --live.
"""

from __future__ import annotations

import argparse
import email.utils
import json
import logging
import math
import os
import random
import re
import socket
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from typing import Callable, Dict, Iterable, Mapping, Optional, Tuple

logger = logging.getLogger("meta_model_client")

ENV_ENDPOINT = "META_MODEL_API_ENDPOINT"
ENV_MODEL = "META_MODEL_API_MODEL"
ENV_KEY = "META_MODEL_API_KEY"

REDACTED = "[REDACTED]"
DEFAULT_MAX_TOKENS = 8
DEFAULT_TEMPERATURE = 0
DEFAULT_TIMEOUT = 30.0
DEFAULT_RETRY_AFTER_CAP = 30.0
DEFAULT_BACKOFF_BASE = 1.0
RETRYABLE_STATUSES = frozenset({429, 503})
MAX_RESPONSE_BYTES = 1024 * 1024
SNIPPET_LIMIT = 200

# (method, url, headers, body_bytes, timeout) -> (status, headers, body_bytes)
Transport = Callable[[str, str, Mapping[str, str], bytes, float],
                     Tuple[int, Mapping[str, str], bytes]]


# --------------------------------------------------------------------------
# Errors
# --------------------------------------------------------------------------

class MetaModelError(Exception):
    """Base class for all client errors."""


class ConfigError(MetaModelError):
    """Missing or invalid configuration."""


class APIError(MetaModelError):
    """Unexpected HTTP status (non-2xx not covered by a subclass)."""

    def __init__(self, message: str, status: Optional[int] = None):
        super().__init__(message)
        self.status = status


class AuthError(APIError):
    """HTTP 401 / 403."""


class RateLimitError(APIError):
    """HTTP 429."""

    def __init__(self, message: str, status: Optional[int] = 429,
                 retry_after: Optional[float] = None):
        super().__init__(message, status)
        self.retry_after = retry_after


class ServerError(APIError):
    """HTTP 5xx."""


class RequestTimeout(MetaModelError):
    """The request timed out. Never retried automatically (it may have been billed)."""


class TransportError(MetaModelError):
    """Non-timeout network failure (DNS, connection refused, TLS, ...)."""


class ResponseFormatError(MetaModelError):
    """2xx response whose body does not match the assumed schema."""


# --------------------------------------------------------------------------
# Redaction helpers
# --------------------------------------------------------------------------

_BEARER_RE = re.compile(r"(?i)\bbearer\s+\S+")


def redact(text: object, secrets: Iterable[Optional[str]] = ()) -> str:
    """Replace every occurrence of each non-empty secret, and any
    `Bearer <token>` pattern, with [REDACTED]."""
    out = "" if text is None else str(text)
    for secret in secrets or ():
        if secret:
            out = out.replace(secret, REDACTED)
    return _BEARER_RE.sub("Bearer " + REDACTED, out)


def _snippet(body: Optional[bytes], secrets: Iterable[Optional[str]] = (),
             limit: int = SNIPPET_LIMIT) -> str:
    """Redacted, truncated, single-line preview of a response body."""
    if not body:
        return "<empty body>"
    text = body.decode("utf-8", "replace") if isinstance(body, (bytes, bytearray)) else str(body)
    text = redact(text, secrets).replace("\r", " ").replace("\n", " ")
    if len(text) > limit:
        return "%s...[truncated, %d chars total]" % (text[:limit], len(text))
    return text


def _safe_host(url: str) -> str:
    """Hostname only: no scheme, userinfo, port, path or query."""
    try:
        return urllib.parse.urlsplit(url).hostname or "<unknown-host>"
    except ValueError:
        return "<invalid-host>"


# --------------------------------------------------------------------------
# Config
# --------------------------------------------------------------------------

class Config:
    __slots__ = ("endpoint", "model", "_api_key")

    def __init__(self, endpoint: str, model: str, api_key: str):
        self.endpoint = endpoint
        self.model = model
        self._api_key = api_key

    @property
    def api_key(self) -> str:
        return self._api_key

    @property
    def host(self) -> str:
        return _safe_host(self.endpoint)

    def __repr__(self) -> str:
        return "Config(host=%r, model=%r, api_key=%r)" % (self.host, self.model, REDACTED)

    __str__ = __repr__


def load_config(env: Optional[Mapping[str, str]] = None) -> Config:
    """Read config from env vars. No defaults for the destination; fail closed."""
    env = os.environ if env is None else env
    values = {}
    missing = []
    for name in (ENV_ENDPOINT, ENV_MODEL, ENV_KEY):
        raw = env.get(name)
        value = raw.strip() if isinstance(raw, str) else ""
        if not value:
            missing.append(name)
        values[name] = value
    if missing:
        raise ConfigError("Missing or blank required environment variable(s): "
                          + ", ".join(missing))

    endpoint, model, key = values[ENV_ENDPOINT], values[ENV_MODEL], values[ENV_KEY]

    try:
        parts = urllib.parse.urlsplit(endpoint)
        hostname = parts.hostname
    except ValueError:
        raise ConfigError("%s is not a valid URL." % ENV_ENDPOINT) from None
    if parts.scheme.lower() != "https":
        raise ConfigError("%s must use https:// (got scheme %r)." % (ENV_ENDPOINT, parts.scheme))
    if not hostname:
        raise ConfigError("%s has no host." % ENV_ENDPOINT)
    if parts.username is not None or parts.password is not None:
        raise ConfigError("%s must not embed credentials (user:pass@host)." % ENV_ENDPOINT)
    if any(ch.isspace() for ch in key):
        # Prevent header injection; never echo the value.
        raise ConfigError("%s contains whitespace (value not shown)." % ENV_KEY)
    return Config(endpoint=endpoint, model=model, api_key=key)


# --------------------------------------------------------------------------
# Request builder / response parser (schema-specific, UNVERIFIED)
# --------------------------------------------------------------------------

# UNVERIFIED: confirm against official docs
def build_request(config: Config, prompt: str,
                  max_tokens: int = DEFAULT_MAX_TOKENS,
                  temperature: float = DEFAULT_TEMPERATURE) -> Tuple[Dict[str, str], bytes]:
    """ASSUMED OpenAI-compatible chat-completions body and Bearer auth."""
    payload = {
        "model": config.model,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": max_tokens,
        "temperature": temperature,
    }
    headers = {
        "Authorization": "Bearer " + config.api_key,
        "Content-Type": "application/json",
        "Accept": "application/json",
    }
    return headers, json.dumps(payload).encode("utf-8")


# UNVERIFIED: confirm against official docs
def parse_response(body: Optional[bytes], secrets: Iterable[Optional[str]] = ()) -> str:
    """ASSUMED schema: extract choices[0].message.content as a string."""
    try:
        data = json.loads((body or b"").decode("utf-8"))
    except ValueError:  # includes JSONDecodeError and UnicodeDecodeError
        raise ResponseFormatError("Response is not valid JSON: " + _snippet(body, secrets)) from None
    if not isinstance(data, dict):
        raise ResponseFormatError("Response top-level JSON is %s, expected object."
                                  % type(data).__name__)
    choices = data.get("choices")
    if not isinstance(choices, list) or not choices:
        raise ResponseFormatError("Response has missing or empty 'choices'.")
    first = choices[0]
    message = first.get("message") if isinstance(first, dict) else None
    if not isinstance(message, dict):
        raise ResponseFormatError("Response 'choices[0].message' is missing or not an object.")
    content = message.get("content")
    if not isinstance(content, str):
        raise ResponseFormatError("Response 'choices[0].message.content' is %s, expected str."
                                  % type(content).__name__)
    return content


# --------------------------------------------------------------------------
# Retry-After
# --------------------------------------------------------------------------

def parse_retry_after(value: Optional[str], now: Optional[datetime] = None) -> Optional[float]:
    """Parse Retry-After (delta-seconds or HTTP-date). Returns seconds >= 0, or None."""
    if value is None:
        return None
    text = str(value).strip()
    if not text:
        return None
    try:
        seconds = float(text)
    except ValueError:
        try:
            when = email.utils.parsedate_to_datetime(text)
        except (TypeError, ValueError, IndexError):
            return None
        if when is None:
            return None
        if when.tzinfo is None:
            when = when.replace(tzinfo=timezone.utc)
        seconds = (when - (now or datetime.now(timezone.utc))).total_seconds()
    if not math.isfinite(seconds):
        return None
    return max(0.0, seconds)


# --------------------------------------------------------------------------
# Default transport (urllib). Tests must never use this.
# --------------------------------------------------------------------------

class _NoRedirect(urllib.request.HTTPRedirectHandler):
    """Refuse redirects so the Authorization header is never forwarded elsewhere."""

    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401
        return None


def urllib_transport(method: str, url: str, headers: Mapping[str, str],
                     body: bytes, timeout: float) -> Tuple[int, Dict[str, str], bytes]:
    req = urllib.request.Request(url, data=body, headers=dict(headers), method=method)
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(req, timeout=timeout) as resp:
            return resp.getcode(), dict(resp.headers.items()), resp.read(MAX_RESPONSE_BYTES)
    except urllib.error.HTTPError as exc:
        try:
            data = exc.read(MAX_RESPONSE_BYTES)
        except Exception:
            data = b""
        hdrs = dict(exc.headers.items()) if exc.headers is not None else {}
        return exc.code, hdrs, data


def _is_timeout(exc: BaseException) -> bool:
    if isinstance(exc, (socket.timeout, TimeoutError)):
        return True
    if isinstance(exc, urllib.error.URLError):
        reason = exc.reason
        if isinstance(reason, (socket.timeout, TimeoutError)):
            return True
        if isinstance(reason, str) and "timed out" in reason.lower():
            return True
    return False


# --------------------------------------------------------------------------
# Client
# --------------------------------------------------------------------------

class MetaModelClient:
    def __init__(self, config: Config, transport: Optional[Transport] = None,
                 sleep: Callable[[float], None] = time.sleep,
                 max_retries: int = 0,
                 timeout: float = DEFAULT_TIMEOUT,
                 retry_after_cap: float = DEFAULT_RETRY_AFTER_CAP,
                 backoff_base: float = DEFAULT_BACKOFF_BASE,
                 rng: Callable[[], float] = random.random):
        if not isinstance(config, Config):
            raise ConfigError("config must be a Config instance (use load_config()).")
        if not isinstance(max_retries, int) or isinstance(max_retries, bool) or max_retries < 0:
            raise ValueError("max_retries must be a non-negative int.")
        if retry_after_cap < 0 or backoff_base < 0 or timeout <= 0:
            raise ValueError("retry_after_cap/backoff_base must be >= 0 and timeout > 0.")
        self._config = config
        self._transport = transport if transport is not None else urllib_transport
        self._sleep = sleep
        self.max_retries = max_retries
        self.timeout = timeout
        self.retry_after_cap = retry_after_cap
        self.backoff_base = backoff_base
        self._rng = rng

    def __repr__(self) -> str:
        return "MetaModelClient(host=%r, model=%r, api_key=%r, max_retries=%d)" % (
            self._config.host, self._config.model, REDACTED, self.max_retries)

    __str__ = __repr__

    @property
    def _secrets(self) -> Tuple[str, ...]:
        return (self._config.api_key,)

    def _backoff(self, attempt: int, retry_after: Optional[float]) -> float:
        if retry_after is not None:
            return min(retry_after, self.retry_after_cap)
        ceiling = min(self.retry_after_cap, self.backoff_base * (2 ** attempt))
        # "Equal jitter": between ceiling/2 and ceiling.
        return ceiling / 2.0 + self._rng() * ceiling / 2.0

    def _error_for(self, status: int, headers: Mapping[str, str], body: bytes) -> APIError:
        host = self._config.host
        detail = _snippet(body, self._secrets)
        if status in (401, 403):
            return AuthError("Authentication failed (HTTP %d) at %s; check %s (value not shown). "
                             "Body: %s" % (status, host, ENV_KEY, detail), status)
        if status == 429:
            lower = {str(k).lower(): v for k, v in (headers or {}).items()}
            retry_after = parse_retry_after(lower.get("retry-after"))
            return RateLimitError("Rate limited (HTTP 429) at %s; retry_after=%s. Body: %s"
                                  % (host, retry_after, detail), 429, retry_after)
        if 500 <= status <= 599:
            return ServerError("Server error (HTTP %d) at %s. Body: %s" % (status, host, detail),
                               status)
        return APIError("Unexpected HTTP %d at %s. Body: %s" % (status, host, detail), status)

    def complete(self, prompt: str, max_tokens: int = DEFAULT_MAX_TOKENS,
                 temperature: float = DEFAULT_TEMPERATURE) -> str:
        """Send ONE chat-completion request (plus opt-in retries on 429/503)."""
        headers, body = build_request(self._config, prompt, max_tokens, temperature)
        method, url, host = "POST", self._config.endpoint, self._config.host
        attempt = 0
        while True:
            try:
                status, resp_headers, resp_body = self._transport(
                    method, url, headers, body, self.timeout)
            except RequestTimeout:
                logger.warning("method=%s host=%s status=timeout", method, host)
                raise
            except MetaModelError:
                raise
            except Exception as exc:  # noqa: BLE001 - mapped to safe errors below
                if _is_timeout(exc):
                    logger.warning("method=%s host=%s status=timeout", method, host)
                    raise RequestTimeout("Request to %s timed out after %ss; not retried "
                                         "(it may have been billed)." % (host, self.timeout)) from None
                logger.warning("method=%s host=%s status=transport-error", method, host)
                raise TransportError("Transport failure contacting %s: %s" % (
                    host, redact("%s: %s" % (type(exc).__name__, exc), self._secrets))) from None

            logger.info("method=%s host=%s status=%s", method, host, status)
            if 200 <= status <= 299:
                return parse_response(resp_body, self._secrets)
            err = self._error_for(status, resp_headers, resp_body)
            if status in RETRYABLE_STATUSES and attempt < self.max_retries:
                delay = self._backoff(attempt, getattr(err, "retry_after", None))
                attempt += 1
                self._sleep(delay)
                continue
            raise err


# --------------------------------------------------------------------------
# Dry run / CLI
# --------------------------------------------------------------------------

DEFAULT_PROMPT = "Reply with the single word: OK"


def dry_run(config: Config, prompt: str = DEFAULT_PROMPT,
            max_tokens: int = DEFAULT_MAX_TOKENS,
            temperature: float = DEFAULT_TEMPERATURE) -> str:
    """Return a human-readable description of the request. Sends nothing."""
    headers, body = build_request(config, prompt, max_tokens, temperature)
    secrets = (config.api_key,)
    path = urllib.parse.urlsplit(config.endpoint).path or "/"
    lines = [
        "DRY RUN: nothing was sent.",
        "Method: POST",
        "Destination host: %s" % config.host,
        "Endpoint path: %s" % path,
        "Model: %s" % config.model,
        "Headers:",
    ]
    for name, value in headers.items():
        lines.append("  %s: %s" % (name, redact(value, secrets)))
    lines.append("Body (exact bytes that would be sent):")
    lines.append(body.decode("utf-8"))
    return redact("\n".join(lines), secrets)


def main(argv=None, env=None, out=None, err=None, transport=None) -> int:
    out = out or sys.stdout
    err = err or sys.stderr
    parser = argparse.ArgumentParser(description="Meta Model API connectivity client "
                                     "(UNVERIFIED schema). Default: dry run.")
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--dry-run", action="store_true", help="Print the request; send nothing (default).")
    mode.add_argument("--live", action="store_true", help="Send ONE real request. Consumes credits.")
    parser.add_argument("--prompt", default=DEFAULT_PROMPT, help="Synthetic prompt text.")
    args = parser.parse_args(argv)

    try:
        config = load_config(env)
    except ConfigError as exc:
        print("Config error: %s" % exc, file=err)
        return 2

    if not args.live:
        print(dry_run(config, args.prompt), file=out)
        return 0

    print("LIVE: sending one request to host %s, model %s (max_tokens=%d)."
          % (config.host, config.model, DEFAULT_MAX_TOKENS), file=err)
    client = MetaModelClient(config, transport=transport)
    try:
        content = client.complete(args.prompt)
    except MetaModelError as exc:
        print("%s: %s" % (type(exc).__name__, redact(exc, (config.api_key,))), file=err)
        return 1
    print(redact(content, (config.api_key,)), file=out)
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s %(message)s")
    sys.exit(main())

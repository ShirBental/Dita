> **Historical (TASK-055, 2026-11-04).** Kept as evidence; do not follow as current instructions. Current status: [../docs/capability-status.md](../docs/capability-status.md); index: [../docs/README.md](../docs/README.md).

# connectivity: Meta Model API client (unverified)

**No live call has been made.** Nothing in this directory has been checked against a real endpoint.

## Purpose
A minimal Python 3 client for the "Meta Model API" (hackathon context), using only the standard library, plus unit tests that use a mocked transport. Every destination-specific value comes from config, and request and response handling sit in two functions marked `# UNVERIFIED: confirm against official docs` (`build_request`, `parse_response`).

## Configuration (environment variables, no defaults)
| Variable | Meaning |
|---|---|
| `META_MODEL_API_ENDPOINT` | Full chat-completions URL. Must be `https://`, with no `user:pass@`. |
| `META_MODEL_API_MODEL` | Model ID string. |
| `META_MODEL_API_KEY` | API key. Never logged, printed or included in errors. |

If any of these is missing or blank, the client raises `ConfigError`.

## Running the tests (mock transport only, no network)
```
cd /path/to/Dita/connectivity && python3 -m unittest -v test_meta_model_client
```

## Dry run (sends nothing)
```
cd /path/to/Dita/connectivity && \
META_MODEL_API_ENDPOINT=https://mock.invalid/v1/chat/completions \
META_MODEL_API_MODEL=synthetic-model \
META_MODEL_API_KEY=sk-test-SYNTHETIC-000 \
python3 meta_model_client.py --dry-run
```
With no flag, the CLI also does a dry run. Only `--live` sends a request. Each live call needs prior human approval covering the model, destination, data sent and expected cost (see `.avengers/context.md`).

## Behaviour
- Defaults are `max_tokens=8`, `temperature=0` and `max_retries=0`.
- Errors map as follows: 401/403 → `AuthError`, 429 → `RateLimitError` (with `.retry_after`), 5xx → `ServerError`, other non-2xx → `APIError`, timeout → `RequestTimeout`, other network failures → `TransportError`, unexpected 2xx body → `ResponseFormatError`. Response bodies quoted in errors are redacted and cut to 200 characters.
- Retries happen only when `max_retries > 0`, and only on 429 and 503. `Retry-After` is honoured up to a 30 s cap. Without it, the client uses exponential backoff with jitter. A timeout is never retried. The sleep function is injectable.
- The default urllib transport refuses redirects so the `Authorization` header can't be forwarded to another host. It does honour standard proxy environment variables.

## UNVERIFIED assumptions
1. The endpoint is a single HTTPS URL that takes a POST of the full chat-completions request.
2. Authentication is `Authorization: Bearer <key>`.
3. The request body follows the OpenAI-compatible shape `{model, messages:[{role:"user",content}], max_tokens, temperature}`, and the API accepts those field names and values (including `temperature=0`).
4. The response text is at `choices[0].message.content`, as a string.
5. Model ID strings: none are known or hard-coded.
6. Status codes mean what the code assumes: 401/403 for auth, 429 for rate limit, 503 for retryable unavailability. Retry-After is sent in seconds or as an HTTP-date.
7. A 30 s request timeout and a 30 s Retry-After cap are reasonable.
8. Billing: a timed-out or failed POST may still use credits, which is why the client never retries a timeout. Pricing, credits and rate limits have not been checked.
9. JSON request and response, UTF-8, `Content-Type: application/json`.

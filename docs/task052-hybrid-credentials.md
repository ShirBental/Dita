# TASK-052: credentials for scripts/e2e-hybrid-verify.sh

Stages 2 and 3 run against Codex's isolated TASK-049 stack: `dita-task049-postgres` (127.0.0.1:55532, database
`dita049`) and `dita-task049-vespa3` (127.0.0.1:58180). They also use the demo-stack Neo4j (127.0.0.1:57474), which
Codex's run shared. Codex's handoff (outputs/TASK-049-base-manifest.json, `reproduction.environment_values`) states
that the password values were **not included**. Only the variable names were recorded.

| Variable | Used for | Known value? |
|---|---|---|
| `DITA049_APP_PASSWORD` | `dita_app` on :55532/dita049 | No. Set by Codex when the container was created |
| `DITA049_RELAY_PASSWORD` | `dita_relay` on :55532/dita049 | No. Set by Codex when V3 was applied (`psql -v relay_password=…`) |
| `DITA_NEO4J_PASSWORD` | demo Neo4j :57474 | The script falls back to the demo stack's documented dev default, as stage 1 does |

## How the script loads them (no printing, no committing)

1. Variables already in the environment win.
2. Otherwise the script parses (never sources) `.env.task049` in the project root, or the file named by
   `DITA049_CREDENTIALS_FILE`. The file must be a regular file owned by you, not a symlink, with mode 600. Only the
   three keys above are accepted. `.gitignore` already excludes `.env.*`.
3. A read-only login check (`SELECT 1`, Neo4j `RETURN 1`) runs before Maven. A rejected credential stops the run with
   exit 2 and changes nothing.

The run log reports only where each value came from, never the value.

## Manual setup (run these yourself in a normal terminal; the agent sandbox cannot reach Docker)

1. Create the file without echoing anything:
   ```bash
   umask 077 && touch .env.task049 && chmod 600 .env.task049
   ```
2. If Codex passed the app password as container environment (the compose init script reads `DITA_APP_PASSWORD`),
   copy it straight into the file without displaying it:
   ```bash
   docker inspect dita-task049-postgres --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^DITA_APP_PASSWORD=' | sed 's/^DITA_APP_PASSWORD=/DITA049_APP_PASSWORD=/' >> .env.task049
   ```
   Check that it worked with `grep -c '^DITA049_APP_PASSWORD=' .env.task049` (expect `1`).
3. The relay password is not stored in the container. Add it from Codex's TASK-049 command ledger or your own notes
   with an editor (not `echo` on the command line, which saves it in shell history): `DITA049_RELAY_PASSWORD=…`
4. If Codex used the project's dev placeholders (the same ones the demo stack uses), those values work. The login check
   tells you either way, and a wrong guess changes nothing.
5. If the login check reports `password authentication failed`, check the file's structure without showing values:
   ```bash
   awk '!/^[[:space:]]*#/ && /=/ {k=substr($0,1,index($0,"=")-1); v=substr($0,index($0,"=")+1); printf "%s len=%d edge_whitespace=%s\n", k, length(v), (v ~ /^[ \t]|[ \t\r]$/) ? "YES" : "no"}' .env.task049
   ```
   To list only the variable names Codex gave the container (no values):
   ```bash
   docker inspect dita-task049-postgres --format '{{range .Config.Env}}{{println .}}{{end}}' | cut -d= -f1
   ```
6. Run:
   ```bash
   scripts/e2e-hybrid-verify.sh
   ```

If neither password can be recovered, the only remaining routes are an `ALTER ROLE … PASSWORD` on the isolated
database or recreating Codex's stack. Both are credential resets/rotations and were deliberately not done. They need
your explicit decision.

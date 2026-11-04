#!/usr/bin/env bash
# Telegram connectivity checks for @ExampleDitaBot (TASK-040). Run LOCALLY on the user's Mac; the agent sandbox cannot
# read .env.local. Two read-only modes. Neither one sends a message, changes a bot setting, or acknowledges an update:
#   scripts/telegram-verify.sh auth       getMe + getWebhookInfo. Authenticates the token; reads no messages.
#   scripts/telegram-verify.sh discover   ONE getUpdates peek (timeout 0, NO offset => nothing is acknowledged, NO
#                                         allowed_updates => the stored bot setting is unchanged). Prints METADATA ONLY:
#                                         chat id/type/title, author id, is_bot, message id, date, text length.
#                                         Message text is never printed or written to disk. Asks for 'approve' first.
# The token is read only from .env.local (TELEGRAM_BOT_TOKEN), is passed to curl on stdin (never argv, so `ps` cannot
# see it), is never printed, and nothing is written to disk. No Meta/Muse/AI service is contacted; MODEL_API_KEY is
# neither read nor exported.
set -uo pipefail
cd "$(dirname "$0")/.."
MODE=${1:-}
[[ "$MODE" == auth || "$MODE" == discover ]] || { echo "usage: $0 auth|discover"; exit 64; }
unset MODEL_API_KEY

[[ -f .env.local ]] || { echo "no .env.local in $(pwd); create it (chmod 600) with one TELEGRAM_BOT_TOKEN= line"; exit 1; }
LINES=$(grep -cE '^[[:space:]]*(export[[:space:]]+)?TELEGRAM_BOT_TOKEN=' .env.local 2>/dev/null || true)
[[ "$LINES" == 1 ]] || { echo "expected exactly one TELEGRAM_BOT_TOKEN= line in .env.local (found ${LINES:-0})"; exit 1; }
RAW=$(grep -E '^[[:space:]]*(export[[:space:]]+)?TELEGRAM_BOT_TOKEN=' .env.local | tr -d '\r')
TOKEN=${RAW#*TELEGRAM_BOT_TOKEN=}; unset RAW
TOKEN="${TOKEN#"${TOKEN%%[![:space:]]*}"}"; TOKEN="${TOKEN%"${TOKEN##*[![:space:]]}"}"
if [[ "$TOKEN" =~ ^\"(.*)\"$ || "$TOKEN" =~ ^\'(.*)\'$ ]]; then TOKEN=${BASH_REMATCH[1]}; fi
# Same shape check as DitaSettings (value never shown).
[[ "$TOKEN" =~ ^[0-9]{5,16}:[A-Za-z0-9_-]{30,64}$ ]] || { unset TOKEN; echo "TELEGRAM_BOT_TOKEN in .env.local is not a bot token shape (value not shown)"; exit 1; }
trap 'unset TOKEN' EXIT

# One Bot API call. The URL (with token) goes to curl as a stdin config (printf is a builtin: not in argv). curl's
# stderr is discarded (it can echo URLs); only its exit code is reported. The body stays in memory (a pipe).
tg() {  # $1 = method, $2 = JSON body
  local out rc
  out=$(printf 'url = "https://api.telegram.org/bot%s/%s"\n' "$TOKEN" "$1" \
    | curl -s --max-time 20 --proto '=https' --max-redirs 0 -H 'Content-Type: application/json' \
           --data-binary "$2" -K - 2>/dev/null)
  rc=$?
  if (( rc != 0 )) || [[ -z "$out" ]]; then
    echo "{\"ok\":false,\"error_code\":\"curl-exit-$rc\",\"description\":\"network error (6=DNS 7=connect 28=timeout 35/60=TLS 56=proxy)\"}"
  else
    printf '%s' "$out"
  fi
}

if [[ "$MODE" == auth ]]; then
  echo "== getMe"
  tg getMe '{}' | python3 -I -c '
import json,sys
r=json.load(sys.stdin)
if not r.get("ok"): print("AUTH FAIL:", r.get("error_code"), r.get("description")); sys.exit(2)
u=r["result"]
print("AUTH OK  id=%s username=@%s is_bot=%s" % (u.get("id"), u.get("username"), u.get("is_bot")))
print("  can_join_groups=%s can_read_all_group_messages=%s (expect True: privacy disabled)" % (u.get("can_join_groups"), u.get("can_read_all_group_messages")))
# The token owner is reported above; no personal bot name is embedded here.
' || exit $?
  echo "== getWebhookInfo"
  tg getWebhookInfo '{}' | python3 -I -c '
import json,sys
r=json.load(sys.stdin)
if not r.get("ok"): print("FAIL:", r.get("description")); sys.exit(2)
w=r["result"]
print("  webhook set: %s  pending_update_count=%s  allowed_updates=%s" % (bool(w.get("url")), w.get("pending_update_count"), w.get("allowed_updates")))
if w.get("url"): print("  NOTE: a webhook blocks getUpdates (long polling). Removing it changes a bot setting: needs your decision.")
'
  echo "=== auth RESULT: done (no messages read, nothing acknowledged, nothing sent)"
  exit 0
fi

cat <<INFO
About to make ONE Telegram getUpdates call (discover):
  destination : https://api.telegram.org (Bot API), token from .env.local (never printed)
  parameters  : timeout=0 limit=100, NO offset (nothing acknowledged), NO allowed_updates (bot settings unchanged)
  output      : metadata only (chat id/type/title, author id, is_bot, ids, dates, text LENGTH). No text. Nothing saved.
  not done    : no sendMessage, no polling loop, no Dita app, no database, no Muse/AI call
INFO
read -r -p "Type 'approve' to make this one call: " ok
[[ "$ok" == approve ]] || { echo "cancelled; nothing sent"; exit 1; }

tg getUpdates '{"timeout":0,"limit":100}' | python3 -I -c '
import json,sys,datetime,collections
r=json.load(sys.stdin)
if not r.get("ok"): print("FAIL:", r.get("error_code"), r.get("description")); sys.exit(2)
ups=r["result"]; print("updates returned: %d (Telegram keeps unacknowledged updates ~24h)" % len(ups))
ids=[u.get("update_id") for u in ups]
print("duplicate update_ids in batch: %d" % (len(ids)-len(set(ids))))
chats=collections.OrderedDict()
for u in ups:
    kind=next((k for k in ("message","edited_message","my_chat_member","callback_query","channel_post") if k in u), "other")
    m=u.get(kind,{}) if kind!="callback_query" else u[kind].get("message",{})
    chat=m.get("chat",{}); frm=(u[kind].get("from") if kind=="callback_query" else m.get("from")) or {}
    ts=m.get("date"); when=datetime.datetime.fromtimestamp(ts,datetime.timezone.utc).isoformat() if ts else "-"
    text=m.get("text")
    print("  update=%s kind=%s chat_id=%s type=%s author_id=%s is_bot=%s sender_chat=%s msg_id=%s date=%s text_len=%s%s" % (
        u.get("update_id"), kind, chat.get("id"), chat.get("type"), frm.get("id"), frm.get("is_bot"), "sender_chat" in m,
        m.get("message_id"), when, len(text) if isinstance(text,str) else "-",
        (" migrate_to_chat_id=%s" % m["migrate_to_chat_id"]) if "migrate_to_chat_id" in m else ""))
    c=chats.setdefault(chat.get("id"), {"type":chat.get("type"),"title":chat.get("title"),"authors":set(),"n":0})
    c["n"]+=1
    if frm.get("id") and not frm.get("is_bot"): c["authors"].add(frm["id"])
print("distinct chats:")
for cid,c in chats.items():
    print("  chat_id=%s type=%s title=%r updates=%d human_author_ids=%s" % (cid,c["type"],c["title"],c["n"],sorted(c["authors"])))
print("Confirm the SyntheticDemoGroup chat_id by matching YOUR test message (its date/author), not by the title alone.")
'
rc=$?
echo "=== discover RESULT: rc=$rc (nothing acknowledged, nothing sent, nothing saved)"
exit $rc

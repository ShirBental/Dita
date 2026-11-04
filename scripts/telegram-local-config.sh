#!/usr/bin/env bash
# Sourced by Telegram runners. Parse assignments as data, never execute the local file.
local_config=config/telegram.local.env
CHAT=${DITA_DEMO_CHAT:-}; AUTHOR=${DITA_DEMO_AUTHOR:-}
AUTHORS=${DITA_DEMO_AUTHORS:-}; BOT=${DITA_DEMO_BOT:-}
if [[ -f $local_config ]]; then
  while IFS='=' read -r key value || [[ -n $key ]]; do
    value=${value%$'\r'}
    case "$key" in
      ''|\#*) continue ;;
      DITA_DEMO_CHAT) CHAT=${CHAT:-$value} ;;
      DITA_DEMO_AUTHOR) AUTHOR=${AUTHOR:-$value} ;;
      DITA_DEMO_AUTHORS) AUTHORS=${AUTHORS:-$value} ;;
      DITA_DEMO_BOT) BOT=${BOT:-$value} ;;
      *) echo 'Unsupported local Telegram configuration key'; exit 64 ;;
    esac
  done < "$local_config"
fi
AUTHORS=${AUTHORS:-$AUTHOR}
[[ $CHAT =~ ^-[0-9]+$ && $AUTHOR =~ ^[0-9]+$ && $AUTHORS =~ ^[0-9]+(,[0-9]+)*$ && $BOT =~ ^[A-Za-z0-9_]{5,32}$ ]] \
  || { echo 'Configure config/telegram.local.env from config/telegram.example.env (or set DITA_DEMO_*).'; exit 64; }
[[ ,$AUTHORS, == *,$AUTHOR,* ]] || { echo 'Primary author must be in the session author list'; exit 64; }

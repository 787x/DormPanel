#!/bin/sh
set -eu
umask 077
: "${TRIM_APPDEST:?}" "${TRIM_PKGVAR:?}" "${TRIM_PKGETC:?}"
NODE=/var/apps/nodejs_v22/target/bin/node
PID_FILE="$TRIM_PKGVAR/app.pid"
export SOCKET_PATH="$TRIM_APPDEST/app.sock"
running() {
  [ -f "$PID_FILE" ] || return 1
  read -r pid < "$PID_FILE"
  case "$pid" in ''|*[!0-9]*) return 1;; esac
  [ -r "/proc/$pid/cmdline" ] || return 1
  tr '\000' '\n' < "/proc/$pid/cmdline" | grep -Fxq "$TRIM_APPDEST/server/src/server.mjs" || return 1
  kill -0 "$pid" 2>/dev/null
}
case "${1:-}" in
  start)
    running && exit 0
    mkdir -p "$TRIM_PKGVAR" "$TRIM_PKGETC"
    chmod 700 "$TRIM_PKGVAR" "$TRIM_PKGETC"
    [ -x "$NODE" ] || { echo '缺少 fnOS nodejs_v22 运行时' > "$TRIM_TEMP_LOGFILE"; exit 1; }
    rm -f "$SOCKET_PATH"
    "$NODE" --max-old-space-size=192 "$TRIM_APPDEST/server/src/server.mjs" > "$TRIM_PKGVAR/app.log" 2>&1 &
    echo "$!" > "$PID_FILE"
    n=0
    while [ "$n" -lt 10 ]; do
      if running && [ -S "$SOCKET_PATH" ]; then
        chgrp www-data "$SOCKET_PATH"
        chmod 660 "$SOCKET_PATH"
        exit 0
      fi
      n=$((n+1)); sleep 1
    done
    echo '课程表服务未能启动，请检查应用日志' > "$TRIM_TEMP_LOGFILE"
    exit 1
    ;;
  stop)
    if running; then
      kill -TERM "$pid"
      n=0
      while running && [ "$n" -lt 10 ]; do n=$((n+1)); sleep 1; done
      if running; then kill -KILL "$pid"; fi
    fi
    rm -f "$PID_FILE" "$SOCKET_PATH"
    ;;
  status) if running && [ -S "$SOCKET_PATH" ]; then exit 0; else exit 3; fi ;;
  *) exit 1 ;;
esac

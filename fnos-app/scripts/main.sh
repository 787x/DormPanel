#!/bin/sh
set -eu
umask 077
: "${TRIM_APPDEST:?}" "${TRIM_PKGVAR:?}" "${TRIM_PKGETC:?}"
NODE=/var/apps/nodejs_v22/target/bin/node
PID_FILE="$TRIM_PKGVAR/app.pid"
READY_FILE="$TRIM_PKGVAR/socket.ready"
export SOCKET_PATH="$TRIM_APPDEST/app.sock"
access() { sh "$TRIM_APPDEST/server/socket-access.sh" "$1"; }
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
    if running; then access directory && access socket; exit $?; fi
    mkdir -p "$TRIM_PKGVAR" "$TRIM_PKGETC"
    chmod 700 "$TRIM_PKGVAR" "$TRIM_PKGETC"
    [ -x "$NODE" ] || { echo '缺少 fnOS nodejs_v22 运行时' > "$TRIM_TEMP_LOGFILE"; exit 1; }
    access directory || { echo '不能建立应用私有目录权限；未启动服务' > "$TRIM_TEMP_LOGFILE"; exit 1; }
    rm -f "$SOCKET_PATH" "$READY_FILE"
    # 同一管理目录的进程级锁由 Node 继承；并发 start 也不能产生第二个 Writer。
    ( flock -n 9 || exit 1
      export DORMPANEL_WRITER_LOCK=1
      exec "$NODE" --max-old-space-size=192 "$TRIM_APPDEST/server/src/server.mjs"
    ) 9>"$TRIM_PKGVAR/.writer.lock" > "$TRIM_PKGVAR/app.log" 2>&1 &
    echo "$!" > "$PID_FILE"
    n=0
    while [ "$n" -lt 10 ]; do
      if running && [ -S "$SOCKET_PATH" ] && [ -f "$READY_FILE" ] && [ "$(cat "$READY_FILE")" = "$pid" ]; then
        if access socket; then exit 0; fi
        # 权限设置失败时不回退到共享组/全员写权限。
        break
      fi
      n=$((n+1)); sleep 1
    done
    if running; then kill -TERM "$pid"; fi
    rm -f "$PID_FILE" "$SOCKET_PATH" "$READY_FILE"
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
    rm -f "$PID_FILE" "$SOCKET_PATH" "$READY_FILE"
    ;;
  status) if running && [ -S "$SOCKET_PATH" ]; then exit 0; else exit 3; fi ;;
  *) exit 1 ;;
esac

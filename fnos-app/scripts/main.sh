#!/bin/sh
set -eu
umask 077
: "${TRIM_APPDEST:?}" "${TRIM_PKGVAR:?}" "${TRIM_PKGETC:?}"
NODE=/var/apps/nodejs_v22/target/bin/node
PID_FILE="$TRIM_PKGVAR/app.pid"
READY_FILE="$TRIM_PKGVAR/socket.ready"
export SOCKET_PATH="$TRIM_APPDEST/app.sock"
access() { sh "$TRIM_APPDEST/server/socket-access.sh" "$1"; }
alive() {
  case "$1" in ''|*[!0-9]*) return 1;; esac
  [ -r "/proc/$1/cmdline" ] || return 1
  tr '\000' '\n' < "/proc/$1/cmdline" | grep -Fxq "$TRIM_APPDEST/server/src/server.mjs" || return 1
  kill -0 "$1" 2>/dev/null
}
running() {
  [ -f "$PID_FILE" ] || return 1
  IFS= read -r pid < "$PID_FILE" || return 1
  alive "$pid"
}
case "${1:-}" in
  start)
    mkdir -p "$TRIM_PKGVAR" "$TRIM_PKGETC"
    # 父进程先取得锁，之后才可触碰运行文件；Node 继承同一 fd 持锁。
    exec 9>"$TRIM_PKGVAR/.writer.lock"
    if ! flock -n 9; then
      # 未 ready 时只失败；已正常运行则复验原有权限。两种情况均不清理运行文件。
      if running && [ -S "$SOCKET_PATH" ] && [ -f "$READY_FILE" ] && [ "$(cat "$READY_FILE")" = "$pid" ]; then access directory && access socket; exit $?; fi
      exit 1
    fi
    if running; then access directory && access socket; exit $?; fi
    chmod 700 "$TRIM_PKGVAR" "$TRIM_PKGETC"
    [ -x "$NODE" ] || { echo '缺少 fnOS nodejs_v22 运行时' > "$TRIM_TEMP_LOGFILE"; exit 1; }
    access directory || { echo '不能建立应用私有目录权限；未启动服务' > "$TRIM_TEMP_LOGFILE"; exit 1; }
    rm -f "$SOCKET_PATH" "$READY_FILE" "$PID_FILE"
    export DORMPANEL_WRITER_LOCK=1
    "$NODE" --max-old-space-size=192 "$TRIM_APPDEST/server/src/server.mjs" > "$TRIM_PKGVAR/app.log" 2>&1 &
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
    [ -d "$TRIM_PKGVAR" ] || exit 0
    exec 9>"$TRIM_PKGVAR/.writer.lock"
    if running; then
      stopping_pid="$pid"
      kill -TERM "$stopping_pid"
      n=0
      while alive "$stopping_pid" && [ "$n" -lt 10 ]; do n=$((n+1)); sleep 1; done
      if alive "$stopping_pid"; then kill -KILL "$stopping_pid"; fi
    fi
    # 老进程退出后可能已有新 start 获锁；不能清理新实例的文件。
    if ! flock -n 9; then exit 1; fi
    if running; then exit 1; fi
    rm -f "$PID_FILE" "$SOCKET_PATH" "$READY_FILE"
    ;;
  status) if running && [ -S "$SOCKET_PATH" ]; then exit 0; else exit 3; fi ;;
  *) exit 1 ;;
esac

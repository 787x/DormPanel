#!/bin/sh
# 实测 fnOS 1.2.0800 由 root trim_http_cgi 连接；不向共享 UID/GID 授权。
# 应用用户与 root 属于信任边界，这不是对端进程身份认证。
set -eu
: "${TRIM_APPDEST:?}"
command -v chmod >/dev/null 2>&1 || { echo '缺少 chmod；拒绝放宽 Socket 权限' >&2; exit 1; }
private_access() {
  chmod "$2" "$1"
  # 清理开发版遗留 ACL。没有 setfacl 时 chmod 仍将 ACL mask 置零，
  # named-user/group 条目不能越过 0700/0600 的 group class 获得权限。
  if command -v setfacl >/dev/null 2>&1; then setfacl -b "$1"; fi
  chmod "$2" "$1"
}
case "${1:-}" in
  directory)
    # 仅修改本应用 target，不修改卷、全局网关或其他应用。
    private_access "$TRIM_APPDEST" 700
    ;;
  socket)
    [ -S "$TRIM_APPDEST/app.sock" ] && [ ! -L "$TRIM_APPDEST/app.sock" ]
    private_access "$TRIM_APPDEST/app.sock" 600
    ;;
  *) exit 1 ;;
esac

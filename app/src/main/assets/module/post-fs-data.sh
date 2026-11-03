#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/apex-lib.sh"
command -v flock >/dev/null && command -v nsenter >/dev/null || exit 1
exec 9>"$MODDIR/service.lock"
flock -n 9 || exit 0
RUN_COUNT=0
[ ! -f "$MODDIR/early-run-count" ] || read -r RUN_COUNT < "$MODDIR/early-run-count"
case "$RUN_COUNT" in ''|*[!0-9]*) RUN_COUNT=0;; esac
RUN_COUNT=$((RUN_COUNT + 1))
printf '%s\n' "$RUN_COUNT" > "$MODDIR/early-run-count"
report "stage=early_start template=8 api=$(getprop ro.build.version.sdk)"
# 不等待开机，APEX 尚未就绪时交给服务阶段补扫。
[ -d "$APEX" ] && active && run_stores
report "stage=early_complete；游戏信任需实际抓包验证"

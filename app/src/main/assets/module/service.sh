#!/system/bin/sh
MODDIR=${0%/*}
. "$MODDIR/apex-lib.sh"
command -v flock >/dev/null && command -v nsenter >/dev/null || { report "缺少 flock/nsenter，模块服务停止"; exit 1; }
exec 9>"$MODDIR/service.lock"
flock -n 9 || exit 0
RUN_COUNT=0
[ ! -f "$MODDIR/run-count" ] || read -r RUN_COUNT < "$MODDIR/run-count"
case "$RUN_COUNT" in ''|*[!0-9]*) RUN_COUNT=0;; esac
RUN_COUNT=$((RUN_COUNT + 1))
printf '%s\n' "$RUN_COUNT" > "$MODDIR/run-count.tmp"
mv "$MODDIR/run-count.tmp" "$MODDIR/run-count"
ROUND=0
STARTED_AT=$(uptime_seconds)
trap 'status=$?; report "stage=service_exit exit=$status rounds=$ROUND elapsed_s=$(($(uptime_seconds) - STARTED_AT))"' 0
report "service_start template=8 api=$(getprop ro.build.version.sdk) android=$(getprop ro.build.version.release) selinux=$(getenforce 2>/dev/null) kernel=$(uname -r)"
# 传统模式由管理器覆盖 system，保留启动记录但不扫描 APEX。
grep -q '^mode=apex$' "$MODDIR/install-info.txt" || { report "stage=traditional_mode"; exit 0; }
ROOT_KIND=$(sed -n 's/^root=//p' "$MODDIR/install-info.txt")
case "$ROOT_KIND" in Magisk|KernelSU|SukiSU|APatch) ;; *) report "Root 实现未知"; exit 1;; esac
report "stage=environment root=$ROOT_KIND module=$MODDIR boot_completed=$(getprop sys.boot_completed)"
while active; do
  ROUND=$((ROUND + 1))
  run_stores
  sleep 15
done
report "模块已禁用或待卸载，停止新增挂载；重启后移除现有挂载"
# 不删除 /dev 快照，不强行卸载仍被运行中应用使用的目录。

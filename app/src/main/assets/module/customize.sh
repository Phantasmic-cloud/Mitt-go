#!/system/bin/sh
# 仅由管理器安装；APEX 模式使用脚本挂载，不修改真实系统分区。
[ "$BOOTMODE" = true ] || abort "请在 Magisk / KernelSU / SukiSU / APatch 管理器中安装"
case "$API" in ''|*[!0-9]*) abort "无法识别 Android 版本";; esac
[ "$API" -ge 24 ] || abort "需要 Android 7 或更高版本"
# APatch 同时提供兼容用 MAGISK_VER_CODE，必须优先识别。
if [ "$APATCH" = true ]; then root_kind=APatch
elif [ "$KSU" = true ]; then
  root_kind=KernelSU
  [ "KernelSU" != SukiSU ] || root_kind=SukiSU
elif [ -n "$MAGISK_VER_CODE" ]; then root_kind=Magisk
else abort "仅支持 Magisk / KernelSU / SukiSU / APatch 管理器"
fi
case "KernelSU" in
  auto) ;;
  "$root_kind") ;;
  *) abort "模块目标管理器与当前安装环境不匹配";;
esac
[ "$root_kind" != SukiSU ] || ui_print "请确保 SukiSU 已启用系统挂载支持"
[ "$root_kind" != APatch ] || ui_print "请确保 APatch 已启用系统挂载支持"
store=/system/etc/security/cacerts
mode=system
if [ -d /apex/com.android.conscrypt/cacerts ]; then
  mode=apex
  store=/apex/com.android.conscrypt/cacerts
  command -v nsenter >/dev/null && command -v flock >/dev/null || abort "缺少 nsenter 或 flock，无法准备 APEX 模块"
  [ -x /system/bin/mount ] && [ -x /system/bin/umount ] || abort "缺少系统挂载工具"
  touch "$MODPATH/skip_mount"
  ui_print "APEX 实验模式：需要重启，尚未完成 Android 14+ 真机验收"
elif [ "$API" -ge 34 ]; then
  abort "Android 14+ 未发现预期 APEX 证书库，请提供设备诊断"
elif [ "$root_kind" = KernelSU ]; then
  meta=/data/adb/metamodule
  [ -d "$meta" ] && [ ! -f "$meta/disable" ] && [ ! -f "$meta/remove" ] || abort "传统模式需先启用 KernelSU 挂载 metamodule 并重启"
  grep -Eq '^metamodule=(1|true)$' "$meta/module.prop" || abort "无法识别 KernelSU 挂载 metamodule"
fi
[ -d "$store" ] || abort "系统证书目录不存在"
cert="$MODPATH/system/etc/security/cacerts/__HASH__.0"
[ -f "$cert" ] || abort "模块证书缺失"
if [ -e "$store/__HASH__.0" ]; then
  cmp -s "$store/__HASH__.0" "$cert" || abort "证书文件名冲突，请先卸载冲突模块并重启"
fi
set_perm_recursive "$MODPATH/system" 0 0 0755 0644 u:object_r:system_file:s0
set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/apex-mount.sh" 0 0 0644
set_perm "$MODPATH/apex-lib.sh" 0 0 0644
printf 'template=8\nroot=%s\napi=%s\nmode=%s\nsha256=__SHA256__\n' "$root_kind" "$API" "$mode" > "$MODPATH/install-info.txt"
set_perm "$MODPATH/install-info.txt" 0 0 0644
ui_print "当前 CA：__SHA256__"
ui_print "请重启并重新启动游戏。证书可见不代表游戏已信任"
ui_print "禁用/卸载后必须重启，才会移除运行中进程的证书挂载"

#!/system/bin/sh
# 在目标命名空间执行：先完整填充私有 tmpfs，再一次移动到目标，失败保留原库。
SOURCE=$1
TARGET=$2
MARKER=.mitt-ca-__SHA256__
case "$TARGET" in /apex/com.android.conscrypt/cacerts|/system/etc/security/cacerts) ;; *) exit 2;; esac
[ -f "$SOURCE/$MARKER" ] && [ -f "$SOURCE/__HASH__.0" ] && [ -d "$TARGET" ] || exit 3
# 所有能力探测均在本次临时挂载中进行；不尝试修改真实系统分区。
step() {
  stage=$1
  PHASE=$stage
  shift
  if output=$("$@" 2>&1); then rc=0; else rc=$?; fi
  printf 'step=%s exit=%s command=%s error=%s\n' "$stage" "$rc" "$(printf '%s' "$*" | head -c 768)" "$(printf '%s' "$output" | head -c 2048)"
  return "$rc"
}
mount_step() {
  mount_stage=$1
  shift
  step "$mount_stage" /system/bin/mount "$@" && return 0
  # 仅使用已安装管理器的工具，不从网络下载或从任意 PATH 执行。
  for busybox in /data/adb/magisk/busybox /data/adb/ksu/bin/busybox /data/adb/ap/bin/busybox; do
    [ -x "$busybox" ] || continue
    step "$mount_stage" "$busybox" mount "$@" && return 0
  done
  return 1
}
WORK=/dev/mitt-ca-work-$$
mkdir -m 0700 "$WORK" || exit 4
parent_mounted=0
child_mounted=0
cleanup() {
  status=$?
  printf 'step=mount_result phase=%s exit=%s target=%s\n' "$PHASE" "$status" "$TARGET"
  # 仅卸载本进程创建的临时挂载；目标成功后不卸载目标。
  if [ "$child_mounted" = 1 ]; then step cleanup_child /system/bin/umount "$WORK/store" || { echo '临时库卸载失败'; exit 10; }; fi
  if [ "$parent_mounted" = 1 ]; then step cleanup_parent /system/bin/umount "$WORK" || { echo '临时父挂载卸载失败'; exit 11; }; fi
  rmdir "$WORK" 2>/dev/null
  exit "$status"
}
trap cleanup 0
mount_step parent_tmpfs -t tmpfs -o mode=0700,nodev,nosuid,noexec mitt-ca-work "$WORK" || exit 5
parent_mounted=1
# 私有父挂载避免从共享父目录移动挂载被内核拒绝。
# Toybox 识别 private 而非 make-private；显式提供类型和占位源避免读取 fstab。
mount_step parent_private -t tmpfs -o private none "$WORK" || exit 5
# 命令成功也必须核对传播属性，拒绝共享父挂载下继续移动。
if ! awk -v target="$WORK" '
  $5 == target {found=1; for(i=7;i<=NF && $i!="-";i++) if($i ~ /^(shared|master|propagate_from):/ || $i=="unbindable") bad=1}
  END {exit !(found && !bad)}' /proc/self/mountinfo; then
  echo 'step=private_verify exit=1；临时父挂载未确认私有，保留原证书库'
  exit 5
fi
mkdir "$WORK/store" || exit 5
mount_step child_tmpfs -t tmpfs -o mode=0755,nodev,nosuid,noexec mitt-ca-__SHA256__ "$WORK/store" || exit 5
child_mounted=1
step copy_certificates cp -p "$SOURCE"/* "$WORK/store/" && step copy_marker cp -p "$SOURCE/$MARKER" "$WORK/store/" || exit 6
PHASE=directory_context
context=$(ls -Zd "$TARGET" | awk '{print $1}')
case "$context" in u:object_r:*:s0) ;; *) exit 6;; esac
PHASE=file_context
first=""
for file in "$TARGET"/*; do [ ! -f "$file" ] || { first="$file"; break; }; done
[ -n "$first" ] || exit 6
file_context=$(ls -Z "$first" | awk '{print $1}')
case "$file_context" in u:object_r:*:s0) ;; *) exit 6;; esac
PHASE=permissions_and_labels
chown -R 0:0 "$WORK/store" && chmod 0755 "$WORK/store" &&
chmod 0644 "$WORK/store"/* "$WORK/store/$MARKER" &&
chcon "$context" "$WORK/store" && chcon "$file_context" "$WORK/store"/* "$WORK/store/$MARKER" || exit 7
PHASE=verify_snapshot
for file in "$SOURCE"/* "$SOURCE/$MARKER"; do
  cmp -s "$file" "$WORK/store/${file##*/}" || exit 8
done
# 操作前再核对目标，拒绝覆盖准备期间其他模块新增/变更的证书。
PHASE=verify_target
for file in "$TARGET"/* "$TARGET"/.[!.]* "$TARGET"/..?*; do
  [ -e "$file" ] || continue
  cmp -s "$file" "$SOURCE/${file##*/}" || exit 8
done
for file in "$SOURCE"/*; do
  [ "${file##*/}" = '__HASH__.0' ] || cmp -s "$file" "$TARGET/${file##*/}" || exit 8
done
mount_step activate_move -o move "$WORK/store" "$TARGET" || exit 9
child_mounted=0

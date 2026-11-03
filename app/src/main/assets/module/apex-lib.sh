#!/system/bin/sh
# 自有实现：只合并当前模块 CA，原证书与 APEX 镜像保持不变。
APEX=/apex/com.android.conscrypt/cacerts
PROC=/proc
STORE=/dev/mitt-ca-__SHA256__
CERT="$MODDIR/system/etc/security/cacerts/__HASH__.0"
MARKER=.mitt-ca-__SHA256__

active() {
  [ -d "$MODDIR" ] && [ ! -e "$MODDIR/disable" ] && [ ! -e "$MODDIR/remove" ]
}

uptime_seconds() {
  local seconds rest
  read -r seconds rest < /proc/uptime
  printf '%s' "${seconds%%.*}"
}

report() {
  [ -d "$MODDIR" ] || return 0
  if [ -f "$MODDIR/runtime.log" ] && [ "$(wc -c < "$MODDIR/runtime.log")" -gt 262144 ]; then
    [ ! -f "$MODDIR/runtime.1.log" ] || mv "$MODDIR/runtime.1.log" "$MODDIR/runtime.2.log"
    mv "$MODDIR/runtime.log" "$MODDIR/runtime.1.log"
  fi
  LOG_SEQUENCE=$((${LOG_SEQUENCE:-0} + 1))
  printf '%s run=%s pid=%s round=%s seq=%s uptime_s=%s %s\n' \
    "$(date '+%Y-%m-%dT%H:%M:%S%z')" "${RUN_COUNT:-0}" "$$" "${ROUND:-0}" "$LOG_SEQUENCE" "$(uptime_seconds)" "$*" >> "$MODDIR/runtime.log"
}

prepare_store() {
  local stage count file first dir_context file_context
  [ -f "$CERT" ] && [ -d "$APEX" ] || return 1
  if [ -d "$STORE" ]; then
    # 不清空已被其他进程使用的目录；同一开机周期可重用已验证的快照。
    [ -f "$STORE/$MARKER" ] && cmp -s "$CERT" "$STORE/__HASH__.0"
    return $?
  fi
  if [ -e "$APEX/__HASH__.0" ] && ! cmp -s "$CERT" "$APEX/__HASH__.0"; then
    report "证书名称冲突，未修改挂载"
    return 1
  fi
  stage="$STORE.staging.$$"
  mkdir -m 0700 "$stage" || return 1
  count=0
  first=""
  for file in "$APEX"/*; do
    [ -f "$file" ] || continue
    if ! cp "$file" "$stage/"; then rm -rf "$stage"; return 1; fi
    count=$((count + 1))
    first="$file"
  done
  if [ "$count" -eq 0 ]; then rm -rf "$stage"; return 1; fi
  dir_context=$(ls -Zd "$APEX" | awk '{print $1}')
  file_context=$(ls -Z "$first" | awk '{print $1}')
  case "$dir_context" in u:object_r:*:s0) ;; *) rm -rf "$stage"; return 1;; esac
  case "$file_context" in u:object_r:*:s0) ;; *) rm -rf "$stage"; return 1;; esac
  if ! cp "$CERT" "$stage/__HASH__.0" ||
     ! printf '%s\n' '__SHA256__' > "$stage/$MARKER" ||
     ! chown -R 0:0 "$stage" || ! chmod 0755 "$stage" ||
     ! chmod 0644 "$stage"/* "$stage/$MARKER" ||
     ! chcon "$dir_context" "$stage" ||
     ! chcon "$file_context" "$stage"/* "$stage/$MARKER"; then
    rm -rf "$stage"
    return 1
  fi
  # /dev 为开机期目录；卸载模块不会删除正在使用的证书快照，重启自动消失。
  mv "$stage" "$STORE" || return 1
  report "stage=store_prepared original_certificates=$count dir_context=$dir_context file_context=$file_context"
}

visible() {
  local target="$PROC/$1/root$APEX"
  [ -f "$target/$MARKER" ] && cmp -s "$CERT" "$target/__HASH__.0"
}

# 挂载点本身不能证明存在第三方模块。仅当目标公开证书与快照一致时叠加，
# 确保不会丢失目标进程独有的证书，也不会恢复该进程已移除的证书。
compatible_store() {
  local target="$PROC/$1/root$APEX" file name count=0
  for file in "$target"/* "$target"/.[!.]* "$target"/..?*; do
    [ -e "$file" ] || continue
    name=${file##*/}
    [ "$name" != "$MARKER" ] || continue
    if [ ! -f "$file" ] || ! cmp -s "$file" "$STORE/$name"; then
      report "PID $1 stage=conflict reason=target_content_diff file=$name"
      return 1
    fi
    count=$((count + 1))
  done
  [ "$count" -gt 0 ] || { report "PID $1 stage=conflict reason=empty_or_unreadable_store"; return 1; }
  for file in "$STORE"/*; do
    name=${file##*/}
    # 当前 CA 是本次唯一允许新增的公开证书。
    [ "$name" != '__HASH__.0' ] || continue
    cmp -s "$file" "$target/$name" || {
      report "PID $1 stage=conflict reason=snapshot_content_diff file=$name"
      return 1
    }
  done
}

mount_evidence() {
  # mountinfo 不含请求内容；记录精确挂载及其 APEX 父挂载，便于判断系统或模块来源。
  awk -v target="$APEX" '$5 == target || $5 == "/apex/com.android.conscrypt" {print}' "$PROC/$1/mountinfo" |
    head -c 2048
}

inject() {
  local pid="$1" source_path output status evidence
  case "$pid" in ''|*[!0-9]*) return 1;; esac
  active || return 1
  [ -e "$PROC/$pid/ns/mnt" ] || return 1
  visible "$pid" && return 0
  evidence=$(mount_evidence "$pid")
  if ! compatible_store "$pid"; then
    report "PID $pid stage=mount_view action=skip mountinfo=$evidence"
    return 2
  fi
  # 内容相同的已有 CA 只表示目标可见，不冒充本模块安装成功。
  if cmp -s "$CERT" "$PROC/$pid/root$APEX/__HASH__.0"; then
    report "PID $pid stage=cert_present ownership=external；当前 CA 已可见，未新增挂载"
    return 0
  fi
  report "PID $pid stage=mount_view action=merge_verified_store mountinfo=$evidence"
  # bind 来源必须属于进入后的挂载命名空间，不能经模块进程的 /proc/.../root 引用外部挂载。
  # /dev 内普通目录通常共享底层内容，先从目标视图核对，再在目标命名空间解析普通路径。
  if [ ! -f "$PROC/$pid/root$STORE/$MARKER" ] || ! cmp -s "$CERT" "$PROC/$pid/root$STORE/__HASH__.0"; then
    report "PID $pid stage=source 目标命名空间不可见当前证书快照，未挂载"
    return 1
  fi
  source_path="$STORE"
  # 保存命令原始错误与阶段，区分 nsenter/mount 失败和挂载后不可见。
  if output=$(nsenter --mount="$PROC/$pid/ns/mnt" -- /system/bin/sh "$MODDIR/apex-mount.sh" "$source_path" "$APEX" 2>&1); then
    report "PID $pid stage=mount_steps $(printf '%s' "$output" | tail -c 8192)"
    if visible "$pid"; then report "PID $pid stage=mount_verified backend=tmpfs_move target=$APEX"; return 0; fi
    report "PID $pid stage=verify mount_exit=0 marker_visible=$([ -f "$PROC/$pid/root$APEX/$MARKER" ] && echo yes || echo no) cert_visible=$([ -f "$PROC/$pid/root$APEX/__HASH__.0" ] && echo yes || echo no)"
    # 验证精确挂载源仍属于本模块，避免误撤销其他模块随后覆盖的挂载。
    if ! awk -v target="$APEX" '$5 == target && / - tmpfs mitt-ca-__SHA256__ / {owned=1} END {exit !owned}' "$PROC/$pid/mountinfo"; then
      report "PID $pid stage=rollback_skipped reason=ownership_changed；请重启"
      return 1
    fi
    # 仅撤销本次成功创建但无法验证的挂载，不卸载其他模块的挂载。
    if output=$(nsenter --mount="$PROC/$pid/ns/mnt" -- /system/bin/umount "$APEX" 2>&1); then :
    else
      status=$?
      report "PID $pid stage=rollback exit=$status error=$(printf '%s' "$output" | tail -c 8192)；请重启"
    fi
  else
    status=$?
    report "PID $pid stage=mount exit=$status backend=tmpfs_move source=$source_path target=$APEX error=$(printf '%s' "$output" | tail -c 8192)"
  fi
  return 1
}

# 从一次进程快照计算 Zygote 的所有后代，包含间接派生的应用进程。
descendants() {
  awk -v roots="$1" '
    BEGIN {n=split(roots,r," "); for(i=1;i<=n;i++) if(r[i]~/^[0-9]+$/) z[r[i]]=1}
    $1~/^[0-9]+$/ && $2~/^[0-9]+$/ {parent[$1]=$2}
    END {for(p in parent) {q=p; for(i=0;i<128 && q in parent;i++) {
      if(z[parent[q]]) {print p; break} if(parent[q]==q) break; q=parent[q]
    }}}
  '
}

sweep() {
  local roots pid result failed=0 total=0 conflicts=0 started_at
  started_at=$(uptime_seconds)
  roots="$(pidof zygote 2>/dev/null) $(pidof zygote64 2>/dev/null)"
  [ -n "$(printf '%s' "$roots" | tr -d ' ')" ] || return 1
  # 先处理 Zygote，新创建的应用继承该视图；再补当前已有进程。
  for pid in $roots $(ps -A -o PID,PPID | descendants "$roots"); do
    inject "$pid"
    result=$?
    [ "$result" -eq 0 ] || failed=$((failed + 1))
    [ "$result" -ne 2 ] || conflicts=$((conflicts + 1))
    total=$((total + 1))
  done
  local summary="检查进程 $total，未确认 $failed；游戏信任需实际抓包验证"
  report "stage=sweep total=$total confirmed=$((total - failed)) failed=$failed conflicts=$conflicts elapsed_s=$(($(uptime_seconds) - started_at))；游戏信任需实际抓包验证"
  [ "$failed" -eq 0 ]
}

# 每个目标保留其原有证书集合，不能用 APEX 快照覆盖传统库。
run_stores() (
  local target base_store="$STORE"
  for target in /apex/com.android.conscrypt/cacerts /system/etc/security/cacerts; do
    APEX="$target"
    [ -d "$APEX" ] || continue
    case "$target" in /system/*) STORE="$base_store-system";; *) STORE="$base_store";; esac
    prepare_store || { report "stage=store_failed target=$target"; continue; }
    # PID 1 的视图用于后续进程继承；当前视图用于管理器自身派生进程。
    inject 1 || :
    inject $$ || :
    sweep || :
  done
)

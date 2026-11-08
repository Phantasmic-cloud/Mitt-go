#!/bin/sh
# 把 GitHub/ 里的发布用文件铺到目标仓库目录。
# 代码目录（app/ core/ scripts/ 等）另外拷贝，不在这里处理。
#
#   ./scripts/publish.sh /path/to/Mitt-go-repo
#
set -eu

SRC=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
DST=${1:-}

if [ -z "$DST" ]; then
    echo "usage: $0 <repo-dir>" >&2
    exit 1
fi
if [ ! -d "$DST" ]; then
    echo "no such directory: $DST" >&2
    exit 1
fi

# tar 管道比 rsync 少一个依赖，--exclude 掉自己。
tar -C "$SRC" --exclude=publish.sh -cf - . | tar -C "$DST" -xf -

echo "copied GitHub/ into $DST"
ls -A "$DST"
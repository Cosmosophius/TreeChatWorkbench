#!/usr/bin/env bash
# 条畅桌面（linux-arm64 便携包）启动脚本。
# 用法：./talktree.sh [条畅的原有启动参数…]
# 依赖：本机已装 JVM 21+（BootJDK），或把 JDK 放在本脚本同目录 jdk/ 下自动检测优先。
# 该包为 jar 集合打包（jpackage 无法在 macOS 交叉产出 Linux 目标），见仓库 docs/PORTING.md §7。

set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="$DIR/lib"
ASSETS="$DIR/assets"

# 1. 找 Java
if [ -d "$DIR/jdk" ]; then
  JAVA_HOME_LOCAL="$DIR/jdk"
elif [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version >/dev/null 2>&1; then
  JAVA_HOME_LOCAL="$JAVA_HOME"
elif command -v java >/dev/null 2>&1; then
  JAVA_HOME_LOCAL=""
else
  echo "未找到 Java。请安装 JDK 21+，或把解压后的 JDK 放到 $DIR/jdk/" >&2
  exit 1
fi

if [ -n "$JAVA_HOME_LOCAL" ]; then
  JAVA_BIN="$JAVA_HOME_LOCAL/bin/java"
else
  JAVA_BIN="$(command -v java)"
fi

# 2. classpath 组包（主 jar 在 lib/ 中随 runtime jar 一并收集）
CP="$(find "$LIB" -name '*.jar' -printf '%p:' 2>/dev/null | sed 's/:$//')"
if [ -z "$CP" ]; then
  # 不支持 -printf 的 busybox/findu 兼容
  CP="$(find "$LIB" -name '*.jar' | tr '\n' ':' | sed 's/:$//')"
fi

# 3. 词库资产桥：assets 目录并入 classpath（充面层按 assets/<名> 从 classpath 读）
exec "$JAVA_BIN" -Xmx2g -Dfile.encoding=UTF-8 \
  -cp "$CP:$ASSETS" com.treechat.desktop.MainKt "$@"

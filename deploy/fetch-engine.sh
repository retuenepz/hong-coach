#!/usr/bin/env bash
# =====================================================================
#  下载 Pikafish 官方 Linux 引擎到 ./engine
#
#  为什么需要这一步：仓库里 src/main/resources/engine/ 下那份是 Windows 的
#  pikafish-avx2.exe，Linux 服务器上跑不了；而且引擎包不进仓库（见 .gitignore）。
#
#  用法（在项目根目录执行）：
#      bash deploy/fetch-engine.sh              # 下载到 ./engine
#      bash deploy/fetch-engine.sh /opt/engine  # 下载到指定目录
#
#  前置：curl（或 wget）+ 7z
#      Ubuntu / Debian : sudo apt install -y p7zip-full
#      CentOS / Rocky  : sudo yum install -y p7zip
# =====================================================================
set -euo pipefail

RELEASE="2026-09-06"
TAG="Pikafish-${RELEASE}"
ARCHIVE="Pikafish.${RELEASE}.7z"
DEFAULT_URL="https://github.com/official-pikafish/Pikafish/releases/download/${TAG}/${ARCHIVE}"
# GitHub 直连太慢或不通时，可以整个地址换掉（例如加一层加速前缀）：
#   PIKAFISH_URL="https://ghfast.top/${DEFAULT_URL}" bash deploy/fetch-engine.sh
URL="${PIKAFISH_URL:-$DEFAULT_URL}"

# 包里这个通用二进制会自动识别 CPU 指令集（AVX2 / BMI2 / SSE4.1 都能跑），
# 不用像老版本那样按机器型号挑文件。
EXE_NAME="Pikafish-Linux-x86-64-universal"
NNUE_NAME="pikafish.nnue"

DEST="${1:-./engine}"

log() { printf '\033[36m%s\033[0m\n' "$*"; }
die() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# ---------- 检查工具 ----------
command -v curl >/dev/null 2>&1 || command -v wget >/dev/null 2>&1 \
    || die "缺少 curl 或 wget，请先安装"

SEVEN_ZIP=""
for c in 7z 7zz 7za; do
    if command -v "$c" >/dev/null 2>&1; then SEVEN_ZIP="$c"; break; fi
done
[ -n "$SEVEN_ZIP" ] || die "缺少 7z 解压工具。Ubuntu 上执行：sudo apt install -y p7zip-full"

# ---------- 检查 CPU 架构 ----------
# 官方 release 里只有 Linux x86-64 的二进制（另有 Windows / MacOS / Android-arm64），
# 没有 Linux ARM 版。先拦下来，省得白下 51MB 再来一个「无法执行二进制文件」。
ARCH="$(uname -m)"
if [ "$ARCH" != "x86_64" ] && [ "$ARCH" != "amd64" ]; then
    die "这台机器是 $ARCH，官方没有对应的 Linux 二进制。
     ARM 服务器需要自己编译 Pikafish，做法见 deploy/DEPLOY.md「服务器是 ARM 架构」一节。"
fi

# ---------- 下载 ----------
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

log "下载 $ARCHIVE （约 51 MB，含全部平台）…"
if command -v curl >/dev/null 2>&1; then
    curl -fL --retry 3 --retry-delay 2 -o "$TMP_DIR/$ARCHIVE" "$URL"
else
    wget -O "$TMP_DIR/$ARCHIVE" "$URL"
fi

# ---------- 检查下载结果 ----------
# 网络到不了 GitHub 时，常见两种「假成功」：拿到一个 HTML 报错页，
# 或者中途断掉留下个几 KB 的残缺文件。下面直接按体积判掉，否则报错会出在解压那步
# （七拐八弯的 7z 报错，看不出真正原因）。
SIZE=$(wc -c < "$TMP_DIR/$ARCHIVE" | tr -d ' ')
if [ "$SIZE" -lt 20000000 ]; then
    die "下载到的文件只有 $((SIZE / 1024)) KB，不是完整的引擎包（正常约 51 MB），多半是网络到不了 GitHub。
     换个源重试：
         PIKAFISH_URL=\"https://ghfast.top/$DEFAULT_URL\" bash deploy/fetch-engine.sh
     或者手动下载同一个 release 的 7z 包，把 Pikafish-Linux-x86-64-universal 和
     pikafish.nnue 两个文件解出来放进 $DEST 即可。"
fi

# ---------- 解压 ----------
log "解压…"
"$SEVEN_ZIP" x -y -o"$TMP_DIR/extract" "$TMP_DIR/$ARCHIVE" >/dev/null

SRC_EXE="$TMP_DIR/extract/$EXE_NAME"
SRC_NNUE="$TMP_DIR/extract/$NNUE_NAME"
[ -f "$SRC_EXE" ]  || die "包里没找到 $EXE_NAME，官方可能改了打包结构，请手动下载"
[ -f "$SRC_NNUE" ] || die "包里没找到 $NNUE_NAME，权重文件是引擎的必需件"

# ---------- 落位 ----------
mkdir -p "$DEST"
cp -f "$SRC_EXE"  "$DEST/$EXE_NAME"
cp -f "$SRC_NNUE" "$DEST/$NNUE_NAME"
chmod 755 "$DEST/$EXE_NAME"

log "已放置："
ls -lh "$DEST/$EXE_NAME" "$DEST/$NNUE_NAME" | awk '{print "    " $9 "  " $5}'

# ---------- 冒烟测试 ----------
# 光是 uciok 还不够——引擎真正要用的是「能不能搜出一手棋」，
# 而这一步会加载 nnue，权重放错地方就会在这里露出来。
log "冒烟测试（uci + 试搜一步）…"
RESULT="$(cd "$DEST" && printf 'uci\nposition startpos\ngo depth 1\nquit\n' \
    | timeout 30 "./$EXE_NAME" 2>&1 || true)"

if grep -q 'uciok' <<<"$RESULT" && grep -q '^bestmove ' <<<"$RESULT"; then
    log "引擎工作正常：$(grep '^bestmove ' <<<"$RESULT" | head -1)"
else
    printf '\033[33m%s\033[0m\n' "引擎自检没通过，输出如下（放到服务器上再试一次，本地架构不同属正常）："
    printf '%s\n' "$RESULT" | head -20
    echo
    printf '\033[33m%s\033[0m\n' "常见原因：CPU 太老不支持；或 $NNUE_NAME 没和可执行文件放在同一目录。"
fi

echo
log "完成。引擎目录：$(cd "$DEST" && pwd)"
log "接下来：docker compose up -d --build"

#!/bin/bash
set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC} $1"; }
success() { echo -e "${GREEN}[OK]${NC}   $1"; }
warn()    { echo -e "${YELLOW}[WARN]${NC} $1"; }
die()     { echo -e "${RED}[ERR]${NC}  $1"; exit 1; }

[[ $EUID -eq 0 ]] && die "Do NOT run as root."

# ─── CONFIG ───────────────────────────────
CMDTOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
ANDROID_HOME="$HOME/android-sdk"
CMDTOOLS_DIR="$ANDROID_HOME/cmdline-tools/latest"
PROJECT_DIR="$HOME/Documents/GitHub/AndroidGSM"
# ──────────────────────────────────────────

echo ""
echo -e "${CYAN}=================================================${NC}"
echo -e "${CYAN}   Android SDK Installer — Kali Linux           ${NC}"
echo -e "${CYAN}=================================================${NC}"
echo ""

# ── STEP 1: Dependencies ──────────────────
info "Installing unzip, wget..."
sudo apt-get update -qq
sudo apt-get install -y unzip wget 2>/dev/null

# ── STEP 2: Download cmdline-tools ────────
info "Downloading Android command-line tools (~150MB)..."
mkdir -p "$CMDTOOLS_DIR"
TMP_ZIP="/tmp/cmdtools.zip"
wget -q --show-progress "$CMDTOOLS_URL" -O "$TMP_ZIP"

info "Extracting..."
TMP_EXTRACT="/tmp/cmdtools-extract"
rm -rf "$TMP_EXTRACT"
unzip -q "$TMP_ZIP" -d "$TMP_EXTRACT"
cp -r "$TMP_EXTRACT/cmdline-tools/"* "$CMDTOOLS_DIR/"
rm -rf "$TMP_ZIP" "$TMP_EXTRACT"
success "Command-line tools ready"

# ── STEP 3: Environment variables ─────────
info "Setting ANDROID_HOME in ~/.bashrc and ~/.zshrc..."
setup_env() {
  local FILE="$1"
  [[ ! -f "$FILE" ]] && return
  grep -q "ANDROID_HOME" "$FILE" && return
  cat >> "$FILE" <<EOF

# Android SDK
export ANDROID_HOME="$ANDROID_HOME"
export PATH="\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools"
EOF
}
setup_env "$HOME/.bashrc"
setup_env "$HOME/.zshrc"

export ANDROID_HOME="$ANDROID_HOME"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

# ── STEP 4: Accept licenses ───────────────
info "Accepting SDK licenses..."
yes | "$CMDTOOLS_DIR/bin/sdkmanager" --licenses > /dev/null 2>&1 || true
success "Licenses accepted"

# ── STEP 5: Install SDK components ────────
info "Installing platform-tools, android-36, build-tools 36.0.0..."
info "(This is ~1.5–2GB — go grab a coffee ☕)"
"$CMDTOOLS_DIR/bin/sdkmanager" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0"
success "SDK components installed"

# ── STEP 6: Point local.properties to SDK ─
info "Updating local.properties for project: $PROJECT_DIR"
if [[ -d "$PROJECT_DIR" ]]; then
  LOCAL_PROPS="$PROJECT_DIR/local.properties"
  if [[ -f "$LOCAL_PROPS" ]]; then
    sed -i "s|^sdk.dir=.*|sdk.dir=$ANDROID_HOME|" "$LOCAL_PROPS"
  else
    echo "sdk.dir=$ANDROID_HOME" > "$LOCAL_PROPS"
  fi
  success "local.properties updated: sdk.dir=$ANDROID_HOME"
else
  warn "Project folder not found at $PROJECT_DIR — skipping local.properties"
fi

# ── DONE ──────────────────────────────────
echo ""
echo -e "${GREEN}=================================================${NC}"
echo -e "${GREEN}   ✅  Android SDK Ready!                       ${NC}"
echo -e "${GREEN}=================================================${NC}"
echo ""
echo -e "  ${CYAN}Build + install to phone:${NC}"
echo -e "  ${YELLOW}source ~/.bashrc${NC}"
echo -e "  ${YELLOW}cd $PROJECT_DIR && ./gradlew installDebug${NC}"
echo ""
echo -e "  ${CYAN}Or install a pre-built APK:${NC}"
echo -e "  ${YELLOW}adb install -r app/build/outputs/apk/debug/app-debug.apk${NC}"
echo ""

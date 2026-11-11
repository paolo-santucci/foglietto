#!/usr/bin/env bash
# tool/run_android.sh — one command to run Foglietto on a local Android emulator.
#
#   ensure AVD -> boot emulator -> ./gradlew :androidApp:assembleDebug
#              -> adb install -r -t -> am start .MainActivity
#
# Usage: bash tool/run_android.sh [flags]        (bash tool/run_android.sh --help)
#
# Design rules:
#   - Read-only with respect to emulator state the flags did not ask for: no
#     adb uninstall, no user-data wipe unless --wipe (emulator -wipe-data) or
#     --recreate (avdmanager delete + create), and an emulator this script did
#     not start (--serial) is never killed.
#   - Works from any cwd: the repo root is derived from $BASH_SOURCE and every
#     path below is absolute.  The single cd into the repo root is guarded.
#   - No eval, no python, no new dependencies.  Bash 4+ only.  Every expansion
#     is quoted; command substitution results are checked before use.
#   - Every failure exits non-zero and names both the missing piece and the
#     exact command that fixes it.
#   - The accelerated (KVM) start gets exactly one automatic retry with
#     `-accel off` when it provably dies before booting: on nested
#     virtualisation / containers `emulator -accel-check` reports KVM usable
#     while the accelerated CPU init segfaults.  --tcg forces software mode up
#     front, --no-tcg-fallback disables the retry.
#   - The retry only starts on an AVD it has proven free: no process for the AVD
#     and no <avd>.avd/*.lock left (bounded wait), because a second emulator
#     refuses a locked AVD ("Running multiple emulators with the same AVD").
#     If the AVD stays locked the retry is not started and the failure says so;
#     it is never reported as a boot failure.  -read-only is passed only when
#     freedom cannot be proven, never as the normal path (it cannot persist
#     user data or apply -wipe-data).
#   - Host limitation (measured on this repository's development container):
#     the API 36 image does not boot here at all - the accelerated start
#     segfaults during guest start-up for every -gpu backend, and software
#     emulation (--tcg) registers with adb then dies 1-5 minutes in.  The
#     defaults are the configuration that was measured to work instead: the API
#     30 image with -gpu host boots in ~26s.  The script still detects both
#     failure modes and reports them; where neither image boots use
#     --serial <serial> with a physical device, or run the script on the host OS
#     outside the container.  Environment guidance for nested-virtualisation
#     hosts, not a guarantee about any particular machine.
#   - Teardown states only what it can prove: `emu kill` is suggested for an
#     emulator that is still alive (resolved pid, a live process for this AVD,
#     or the serial in `adb devices`), never for one whose boot attempts died.
#     The note is printed once, from cleanup, and names everything this script
#     left running together with the command that stops it.
#   - The picture comes from scrcpy, not from the emulator's own window: the
#     emulator window dies in this container in both CPU modes (KVM and TCG),
#     while scrcpy mirrors a headless emulator into a real desktop window.  So
#     the default run stays headless and, after a successful `am start`, starts
#     scrcpy detached when it is installed and a display is available.  A missing
#     binary or display only explains why the run is headless; --scrcpy demands
#     the window (and fails without it), --no-scrcpy forbids it.
#   - A stale <avd>.avd/*.lock left behind by a killed emulator is cleared and
#     the start retried once, but only when nothing owns it: no live process for
#     this AVD and no live pid in the emulator's own runtime files.  A lock with a
#     live owner still stops the run, with the owner named.
#
# Defaults are the configuration measured to work on this host: system image
# system-images;android-30;google_apis;x86_64, device profile pixel_2, AVD
# name foglietto_api30, and -gpu host while /dev/dri exposes a render node.
# Nothing host-specific is hard-coded: the SDK is resolved from the environment
# at run time and the GPU mode falls back to software rendering.

set -uo pipefail

PROG="run_android.sh"
STEP=0

# --- defaults ---------------------------------------------------------------
# API 30 rather than API 36, because the API 36 image does not boot on this
# host's nested virtualisation: the accelerated start segfaults (exit 139) for
# every -gpu backend, and -accel off registers with adb then dies 1-5 minutes
# in.  The API 30 image on the pixel_2 profile with -gpu host boots in ~26s and
# runs the app, and the APK's minSdk 26 makes it a valid manual-test target.
# --image system-images;android-36;google_apis;x86_64 --device pixel_7 remains
# available on hosts where that image boots.
#
# The AVD name moves with the image on purpose: an existing AVD is reused as-is
# without checking its system image, so keeping foglietto_api36 here would
# silently boot the unusable API 36 image instead of creating the API 30 one.
DEFAULT_AVD="foglietto_api30"
DEFAULT_IMAGE="system-images;android-30;google_apis;x86_64"
DEFAULT_DEVICE="pixel_2"
# The measured API 30 + -gpu host boot takes ~26s, but a software-emulated (TCG)
# boot and a cold boot on a host without a GPU take minutes, so the ceiling is
# deliberately generous; --timeout is the override.
DEFAULT_TIMEOUT=900

# How long to wait for a freshly started emulator to appear in `adb devices`.
SERIAL_WAIT_SECS=60

# How long a retry waits for the failed attempt to release the AVD.  A second
# emulator refuses to start while the first still holds <avd>.avd/*.lock (or is
# still dying): "Running multiple emulators with the same AVD is an experimental
# feature. Please use -read-only flag".
AVD_RELEASE_WAIT_SECS=30

APP_ID="com.paolosantucci.foglietto"
COMPONENT="${APP_ID}/.MainActivity"

AVD="$DEFAULT_AVD"
IMAGE="$DEFAULT_IMAGE"
DEVICE="$DEFAULT_DEVICE"
TIMEOUT="$DEFAULT_TIMEOUT"
SERIAL=""

WIPE=0
RECREATE=0
WINDOW=0
HEADLESS=1
CLEAN=0
NO_BUILD=0
SHUTDOWN=0
LOGCAT=0
TCG=0
NO_TCG_FALLBACK=0

# scrcpy: auto = mirror when scrcpy is installed and a display exists, force =
# --scrcpy (fail if it cannot), off = --no-scrcpy (never start it).
SCRCPY_MODE="auto"
# Resolved from PATH during validation; empty when scrcpy is not installed.
SCRCPY_BIN=""
# The window title names the app, so the window is identifiable in a task bar.
SCRCPY_TITLE="Foglietto — Android emulator"
SCRCPY_LOG=""
# 1 only after scrcpy was actually started by this run.
SCRCPY_LAUNCHED=0
# scrcpy's real pid when it could be resolved with certainty; empty otherwise,
# and then teardown names only the pkill command (never a guessed pid).
SCRCPY_PID=""
SCRCPY_WRAPPER_PID=""

STARTED_BY_US=0
# The emulator's real pid, resolved after the start (never $!: setsid may fork
# and leave $! pointing at a wrapper that has already exited).  Stays empty when
# it cannot be resolved with certainty; teardown then skips the fallback kill
# entirely and relies on `adb emu kill`.
EMU_PID=""
EMU_WRAPPER_PID=""

# Why boot_wait returned non-zero: "" while booting/on success, otherwise
# "timeout", "died" or "no-serial".
BOOT_WAIT_FAILURE=""

# Effective CPU mode of the current (or next) start: 0 = accelerated (KVM, the
# default), 1 = software (emulator -accel off).  --tcg seeds it with 1; the boot
# loop flips it to 1 for the single automatic fallback.
TCG_MODE="$TCG"

# What wait_avd_released found before the automatic retry: 0 = the AVD is
# provably free, 1 = still occupied (no second emulator is started), 2 = freedom
# cannot be proven.
AVD_RELEASE_STATE=0
# 1 only when the retry has to start without proof that the AVD is free: the
# emulator then gets -read-only.  Never the normal path, because a read-only
# instance cannot persist user data and cannot apply -wipe-data.
RETRY_READ_ONLY=0

# The per-attempt emulator logs, in start order (used by the failure report).
ATTEMPT_LOGS=()
ATTEMPT_LABELS=()
# Evidence lines assembled for a failure report (see emu_diagnostics).
DIAG=()

# The crash databases present before the current attempt started: anything newer
# is the emulator's own record of this attempt crashing.
CRASH_DB_BASELINE=""

# Graphics backend.  `host` is the measured working mode on this host: the
# container exposes /dev/dri/renderD* and -gpu host resolves to the Mesa Intel
# UHD adapter, which is what makes the API 30 image boot.  Without a render
# node the documented software renderer `swiftshader_indirect` stays the
# default.  FOGLIETTO_ANDROID_GPU overrides both, including the spelling, in
# case a future emulator drops one of the names.
GPU_RENDER_NODE=""
for render_candidate in /dev/dri/renderD*; do
    [[ -e "$render_candidate" ]] || continue
    GPU_RENDER_NODE="$render_candidate"
    break
done
if [[ -n "${FOGLIETTO_ANDROID_GPU:-}" ]]; then
    GPU_MODE="$FOGLIETTO_ANDROID_GPU"
    GPU_REASON="FOGLIETTO_ANDROID_GPU=$FOGLIETTO_ANDROID_GPU"
elif [[ -n "$GPU_RENDER_NODE" ]]; then
    GPU_MODE="host"
    GPU_REASON="render node $GPU_RENDER_NODE present, so the host GPU is used"
else
    GPU_MODE="swiftshader_indirect"
    GPU_REASON="no /dev/dri/renderD* render node, so software rendering is used"
fi

# --- output helpers ---------------------------------------------------------
usage() {
    cat <<'USAGE'
usage: bash tool/run_android.sh [flags]

Ensures an AVD exists, boots the emulator, builds the debug APK, installs it,
launches com.paolosantucci.foglietto/.MainActivity and (by default) mirrors the
device into a desktop window with scrcpy.

AVD / device
  --avd NAME           AVD name                     (default foglietto_api30)
  --image PKG          system image package         (default system-images;android-30;google_apis;x86_64)
  --device PROFILE     avdmanager device profile    (default pixel_2)
  --serial SERIAL      use an already-running emulator/device with this serial;
                       no emulator is started and nothing is killed on exit
  --wipe               boot with -wipe-data (fresh user data, apps/settings lost)
  --recreate           delete the AVD entirely and create it again

  Without --serial, an emulator that is already running this AVD is reused
  automatically (reinstalling and relaunching the app on it) instead of being
  started a second time - a second start is refused by the emulator with
  "Running multiple emulators with the same AVD".  --wipe refuses to reuse a
  live instance and says how to stop it, because fresh user data cannot be
  applied to a running one.

Emulator window
  --window             show the emulator window (omits -no-window; needs $DISPLAY)
                       headless by default: a set-but-unreachable $DISPLAY is not
                       taken as a request for a window
                       Note: the emulator's own window does not work in this
                       development container - it dies in both KVM and TCG mode.
                       Use scrcpy below to actually see the device.

Device window (scrcpy)
  --scrcpy             mirror the device into a desktop window with scrcpy; fails
                       with a clear message when scrcpy is not on PATH or neither
                       DISPLAY nor WAYLAND_DISPLAY is set
  --no-scrcpy          never start scrcpy: the emulator stays headless
                       Default: mirror automatically when scrcpy is on PATH and
                       DISPLAY or WAYLAND_DISPLAY is set, otherwise stay headless
                       and say why.  Not in auto mode with --window, where the
                       emulator draws its own window; --scrcpy forces it anyway.
                       scrcpy is started detached after the app is launched, so
                       the script still exits; the final note names the emulator
                       and scrcpy and how to stop each.

Emulator CPU
  --tcg                start the emulator with -accel off (software CPU).  Needed
                       on hosts where KVM is present but the accelerated CPU init
                       crashes (nested virtualisation / containers); the boot is
                       then much slower - allow several minutes
  --no-tcg-fallback    disable the single automatic retry with -accel off that
                       happens when the default accelerated start dies before it
                       boots

  Note: the automatic retry first waits (up to 30s) for the failed attempt to
  release the AVD.  A second emulator cannot start while a lingering process or
  a leftover <avd>.avd/*.lock still holds it, so if the AVD stays locked the
  retry is not started and the failure report says that instead of blaming the
  boot.  -read-only is used only when the AVD's freedom cannot be proven.

  Note: the script detects a start that crashes during guest start-up
  (segfault) and a software-emulated boot that dies a few minutes in, and
  reports both.  Where neither image boots, use --serial <serial> with a
  physical device, or run the script on the host OS outside the container.

Host notes (measured on this repository's development container)
  API 36 image: the accelerated start segfaults (exit 139) for every -gpu
  backend, and -accel off (--tcg) registers with adb but dies 1-5 minutes in -
  so API 36 is unusable here and is not the default.  API 30 on pixel_2 with
  -gpu host boots in ~26s and runs the app (the APK's minSdk 26 accepts it).
  --image system-images;android-36;google_apis;x86_64 --device pixel_7 remains
  available on hosts where that image works.  --tcg is the no-GPU fallback.
  The emulator's own window (--window) is unusable here too: it dies in both KVM
  and TCG mode, while a headless emulator boots in ~26s.  scrcpy is the supported
  way to see the device (mirrors the headless emulator into a real window).
  A stale <avd>.avd/*.lock left by a killed emulator is cleared automatically,
  but only when it is provably unowned: a lock with a live owner still stops the
  run and names that owner.
  These are environment observations, not a guarantee about any machine.

Build
  --clean              run ./gradlew clean before :androidApp:assembleDebug
  --no-build           skip Gradle entirely (use the APK already in build outputs)

Timing / exit
  --timeout SECS       boot timeout in seconds      (default 900)
  --shutdown           kill the emulator on exit (only if this script started it)
  --logcat             stream the app's logcat at the end; implies keeping the
                       emulator running
  -h, --help           print this help and exit

Environment
  FOGLIETTO_ANDROID_GPU   emulator -gpu mode; overrides the automatic choice
                          (default: host when a /dev/dri/renderD* render node
                          exists, else swiftshader_indirect)
  ANDROID_HOME / ANDROID_SDK_ROOT   SDK location; fallbacks are
                          $HOME/Android/Sdk, /opt/android-sdk, /usr/lib/android-sdk
  ANDROID_AVD_HOME        where <name>.avd / <name>.ini live; exported for both
                          avdmanager and the emulator.  When unset: an existing
                          $XDG_CONFIG_HOME/.android/avd is reused (XDG_CONFIG_HOME
                          defaults to $HOME/.config), else $HOME/.android/avd,
                          created if needed
  ANDROID_USER_HOME       exported as the parent of ANDROID_AVD_HOME unless you
                          already set it

Examples
  bash tool/run_android.sh
      Default run: boot, build, install, launch, then mirror the device into a
      scrcpy window (headless emulator, visible picture).

  bash tool/run_android.sh --no-scrcpy
      The same without any window: headless emulator, nothing left to close.

  bash tool/run_android.sh --no-build --tcg
      Software CPU: for hosts whose KVM crashes the accelerated emulator (the
      automatic fallback does this for you, but --tcg states the intent).

  bash tool/run_android.sh --window --wipe
      Interactive run with the emulator's own window and fresh user data (only on
      a host where that window works; use scrcpy in this container).

  bash tool/run_android.sh --serial emulator-5554 --no-build --logcat
      Reinstall into an emulator you already have and tail the app log.
USAGE
}

die() {
    local message="$1"
    shift
    printf '\n[FAIL] %s\n' "$message" >&2
    local line
    for line in "$@"; do
        printf '       %s\n' "$line" >&2
    done
    exit 1
}

say() {
    STEP=$((STEP + 1))
    printf '\n==> [%d] %s\n' "$STEP" "$*"
}

note() {
    printf '    %s\n' "$*"
}

require_value() {
    local flag="$1"
    local value="$2"
    [[ -n "$value" ]] || die "option $flag requires a value" "run: bash tool/$PROG --help"
    [[ "$value" != -* ]] || die "option $flag requires a value, got '$value'" "run: bash tool/$PROG --help"
}

# --- device helpers ---------------------------------------------------------
list_serials() {
    # Every serial `adb devices` reports, in any state (device/offline/...).
    "$ADB" devices 2>/dev/null | sed -e '1d' | awk 'NF >= 2 { print $1 }'
}

is_known_serial() {
    local candidate="$1"
    [[ -n "${before_serials:-}" ]] || return 1
    printf '%s\n' "$before_serials" | grep -qxF -- "$candidate"
}

detect_new_serial() {
    # Prints the first serial that was not attached before this script started.
    # Returns 0 with a serial, 2 when the emulator this script started is
    # provably gone first (a resolved pid vanished, or no process for this AVD
    # is left - never inferred from $!), 1 on timeout.
    local deadline=$((SECONDS + SERIAL_WAIT_SECS))
    local serial
    local elapsed=0
    while ((SECONDS < deadline)); do
        while IFS= read -r serial; do
            [[ -n "$serial" ]] || continue
            if ! is_known_serial "$serial"; then
                printf '%s' "$serial"
                return 0
            fi
        done < <(list_serials)
        # A crashed emulator never registers: stop waiting for it instead of
        # burning the full SERIAL_WAIT_SECS.  The grace period covers the moment
        # between `&` and the exec of the emulator binary.
        if [[ -n "${EMU_PID:-}" ]] && ! pid_is_live "$EMU_PID"; then
            return 2
        fi
        if ((elapsed >= 5)) && ! emu_process_alive; then
            return 2
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    return 1
}

show_log_tail() {
    local lines="${1:-30}"
    printf '    --- last %s lines of %s ---\n' "$lines" "$EMU_LOG" >&2
    tail -n "$lines" "$EMU_LOG" >&2 2>/dev/null || true
}

pid_is_live() {
    # Liveness for a pid that must be *running*: a zombie has already exited but
    # still answers kill -0 (a crashed emulator that nobody reaped does exactly
    # that), so the process state is checked as well.
    local pid="${1:-}"
    [[ -n "$pid" ]] || return 1
    kill -0 "$pid" 2>/dev/null || return 1
    if command -v ps >/dev/null 2>&1; then
        local state
        state="$(ps -o stat= -p "$pid" 2>/dev/null | tr -d '[:space:]')"
        [[ -n "$state" ]] || return 1
        [[ "$state" != Z* ]] || return 1
    fi
    return 0
}

pid_cmdline_is_this_avd() {
    # A runtime pid file outlives the emulator that wrote it and the kernel
    # recycles pids, so a live pid is only accepted when /proc/<pid>/cmdline
    # actually names the emulator and this AVD.  No /proc, an unreadable
    # cmdline, or a mismatch is a negative result - never an assumption that
    # the pid belongs to the emulator.
    local pid="${1:-}"
    local cmdline=""
    [[ -n "$pid" ]] || return 1
    [[ -r "/proc/$pid/cmdline" ]] || return 1
    cmdline="$( { tr '\0' ' ' < "/proc/$pid/cmdline"; } 2>/dev/null || true )"
    [[ -n "$cmdline" ]] || return 1
    [[ "$cmdline" == *emulator* ]] || return 1
    [[ "$cmdline" == *"$AVD"* ]] || return 1
    return 0
}

log_has_segfault() {
    # True when a log carries the crash signature of a CPU-init segfault.  The
    # exit status itself is unavailable: the emulator runs detached, so nobody
    # waits for it and a 139 never reaches this shell.
    local log="${1:-$EMU_LOG}"
    [[ -f "$log" ]] || return 1
    grep -qiE 'segmentation fault|core dumped|signal 11|sigsegv|exit(ed)? (code|status) 139|died with signal' \
        "$log" 2>/dev/null
}

crash_db_list() {
    # Every crash database the emulator has written for this user, oldest first.
    local dir="/tmp/android-${USER:-user}"
    local candidate
    for candidate in "$dir"/emu-crash-*.db; do
        [[ -e "$candidate" ]] || continue
        printf '%s\n' "$candidate"
    done
}

crash_db_new() {
    # A crash database that did not exist before the current attempt started.
    # This is the emulator's own crash record, so it is evidence, not a guess.
    local db
    while IFS= read -r db; do
        [[ -n "$db" ]] || continue
        printf '%s\n' "$CRASH_DB_BASELINE" | grep -qxF -- "$db" || {
            printf '%s' "$db"
            return 0
        }
    done < <(crash_db_list)
    return 1
}

crash_db_latest() {
    # The newest crash database, for the report when none is new.
    local db newest=""
    while IFS= read -r db; do
        [[ -n "$db" ]] && newest="$db"
    done < <(crash_db_list)
    [[ -n "$newest" ]] || return 1
    printf '%s' "$newest"
}

wait_serial_gone() {
    # After a failed attempt the dead emulator may linger in `adb devices` as
    # offline.  Waiting it out keeps the next attempt from adopting that serial.
    local serial="$1"
    local i
    for ((i = 0; i < 20; i++)); do
        list_serials | grep -qxF -- "$serial" || return 0
        sleep 1
    done
    return 1
}

running_serial_for_avd() {
    # Print the serial of an emulator that is already running AVD $1 and is
    # online, so this run can reuse it instead of starting a second instance
    # (which the emulator refuses with "Running multiple emulators with the same
    # AVD", a message that says nothing about the healthy device behind it).
    # rc 0 = printed a serial, rc 1 = none.  The AVD name comes from the
    # emulator console (`adb emu avd name` answers "<name>\nOK"), because the
    # serial alone never proves which AVD an instance booted.
    local wanted="$1" serial name
    while IFS= read -r serial; do
        [[ -n "$serial" ]] || continue
        [[ "$serial" == emulator-* ]] || continue
        name="$("$ADB" -s "$serial" emu avd name 2>/dev/null | tr -d '\r' | sed -n '1p')"
        if [[ "$name" == "$wanted" ]]; then
            printf '%s\n' "$serial"
            return 0
        fi
    done < <("$ADB" devices 2>/dev/null | awk '$2 == "device" { print $1 }')
    return 1
}

avd_instance_dir() {
    # <ANDROID_AVD_HOME>/<AVD>.avd: the AVD's own state directory and the place
    # the emulator keeps its multi-instance locks.  Empty (rc 1) when the AVD
    # home was never resolved, which only happens on the --serial path where no
    # AVD is booted at all.
    [[ -n "${ANDROID_AVD_HOME:-}" ]] || return 1
    printf '%s/%s.avd' "$ANDROID_AVD_HOME" "$AVD"
}

avd_lock_files() {
    # Every lock a running instance leaves in the AVD directory
    # (<avd>.avd/*.lock - a file or a directory, depending on the emulator
    # version); one of them is the second emulator's reason to refuse the start.
    # rc 0 = at least one lock, rc 1 = none, rc 2 = cannot be determined
    # (unresolved AVD home, missing or unreadable directory).  "I see no lock"
    # and "I cannot see the lock directory" must not collapse into one answer.
    local dir file
    local found=1
    dir="$(avd_instance_dir || true)"
    if [[ -z "$dir" ]] || [[ ! -d "$dir" ]] || [[ ! -r "$dir" ]]; then
        return 2
    fi
    for file in "$dir"/*.lock; do
        [[ -e "$file" ]] || continue
        printf '%s\n' "$file"
        found=0
    done
    return "$found"
}

avd_release_state() {
    # 0 = the AVD is provably free for a new instance, 1 = still occupied (a
    # process for this AVD, or a lock the crashed instance left behind),
    # 2 = freedom cannot be determined.  Both verdicts need evidence.
    local lock_rc=0
    emu_process_alive && return 1
    avd_lock_files >/dev/null || lock_rc=$?
    case "$lock_rc" in
        0) return 1 ;;
        1) return 0 ;;
        *) return 2 ;;
    esac
}

avd_lock_holder_pid() {
    # The pid of the process still holding the AVD, named in the failure message
    # only - it is never signalled from here.  Verified against its own command
    # line: a runtime pid file outlives its process and the kernel recycles pids.
    local pid matches count
    pid="$(emu_pid_from_runtime_files || true)"
    if [[ -n "$pid" ]] && pid_is_live "$pid" && pid_cmdline_is_this_avd "$pid"; then
        printf '%s' "$pid"
        return 0
    fi
    if command -v pgrep >/dev/null 2>&1; then
        # Bracketed first letter: an unbracketed pattern matches its own argv.
        matches="$(pgrep -f "[e]mulator.*-avd ${AVD}( |\$)" 2>/dev/null || true)"
        count="$(printf '%s' "$matches" | awk 'NF { n++ } END { print n + 0 }')"
        if [[ "$count" == "1" ]] && pid_is_live "$matches"; then
            printf '%s' "$matches"
            return 0
        fi
    fi
    return 1
}

avd_recorded_pids() {
    # Every pid the emulator's own runtime files record for this AVD, across all
    # runtime dirs.  A stale file survives a crash, so these pids are candidates
    # only - see avd_lock_is_stale for the ownership test they must pass.
    local dir file pid
    while IFS= read -r dir; do
        [[ -d "$dir" ]] || continue
        for file in "$dir"/pid_*.ini; do
            [[ -f "$file" ]] || continue
            grep -qxF -- "avd_name=$AVD" "$file" 2>/dev/null || continue
            pid="$(sed -n 's/^pid=\([0-9][0-9]*\)$/\1/p' "$file" 2>/dev/null | head -1)"
            [[ -n "$pid" ]] || continue
            printf '%s\n' "$pid"
        done
    done < <(emulator_runtime_dirs)
}

avd_lock_is_stale() {
    # True only when nothing owns this AVD's leftover lock, which is the single
    # condition under which deleting it is safe.  Two independent negatives are
    # required:
    #   1. no live process for this AVD (emu_process_alive accepts a runtime pid
    #      only when its cmdline names this emulator and this AVD, or exactly one
    #      pgrep match), and
    #   2. no pid recorded in a leftover pid_*.ini is live *and* still names this
    #      AVD: a live pid whose cmdline does not is a recycled pid, not an owner.
    # An unreadable or absent runtime dir yields no pids and stays a negative, so
    # "I cannot see an owner" never authorises a delete on its own.
    local pid
    emu_process_alive && return 1
    while IFS= read -r pid; do
        [[ -n "$pid" ]] || continue
        if pid_is_live "$pid" && pid_cmdline_is_this_avd "$pid"; then
            return 1
        fi
    done < <(avd_recorded_pids)
    return 0
}

clear_stale_avd_locks() {
    # Remove this AVD's leftover *.lock entries after avd_lock_is_stale proved
    # nothing owns them, and print each removed path.  The glob is confined to
    # <avd>.avd, so no other AVD's lock and never the AVD directory itself can be
    # touched.  multiinstance.lock is a *directory* on current emulator versions,
    # hence the two shapes.  rc 0 = at least one removed, 1 = nothing removed.
    local dir file removed=0
    dir="$(avd_instance_dir || true)"
    [[ -n "$dir" ]] || return 1
    [[ -d "$dir" ]] || return 1
    for file in "$dir"/*.lock; do
        [[ -e "$file" ]] || continue
        [[ "$file" == "$dir"/*.lock ]] || continue
        if [[ -d "$file" && ! -L "$file" ]]; then
            rm -rf -- "$file" 2>/dev/null || continue
        else
            rm -f -- "$file" 2>/dev/null || continue
        fi
        printf '%s\n' "$file"
        removed=$((removed + 1))
    done
    ((removed > 0))
}

wait_avd_released() {
    # A crashed emulator can outlive detect_new_serial's "died": the process
    # lingers for a moment, or its <avd>.avd/*.lock is never removed.  A second
    # emulator then dies instantly with "Running multiple emulators with the
    # same AVD", so the -accel off fallback would never get a chance to boot.
    # Wait (bounded) until neither the process nor a lock is left, polling twice
    # a second.  Sets AVD_RELEASE_STATE and RETRY_READ_ONLY.
    local deadline=$((SECONDS + AVD_RELEASE_WAIT_SECS))
    local state=2
    local announced=0
    local tick=0.5
    # GNU sleep takes fractions; where it does not, one second per poll keeps the
    # wait bounded instead of spinning on a rejected 0.5.
    sleep 0.01 2>/dev/null || tick=1
    AVD_RELEASE_STATE=2
    RETRY_READ_ONLY=0
    while :; do
        avd_release_state
        state=$?
        if ((state != 1)); then
            AVD_RELEASE_STATE=$state
            break
        fi
        if ((SECONDS >= deadline)); then
            AVD_RELEASE_STATE=1
            break
        fi
        if ((announced == 0)); then
            note "the previous attempt still holds AVD $AVD: waiting up to ${AVD_RELEASE_WAIT_SECS}s for the process and its *.lock to be released"
            announced=1
        fi
        sleep "$tick"
    done
    if ((AVD_RELEASE_STATE == 2)); then
        # Freedom cannot be proven; a lock left behind is exactly what makes the
        # second start fail, so start read-only rather than not at all.
        RETRY_READ_ONLY=1
    fi
}

emu_process_alive() {
    # True when a process plausibly belonging to this AVD is still running: the
    # pid recorded in the emulator's own runtime file (checked for liveness, not
    # handed out for signalling) or, failing that, a matching command line.
    local pid
    pid="$(emu_pid_from_runtime_files || true)"
    # A stale pid file plus a recycled pid answers kill -0 for an unrelated
    # process, so the pid is only counted when its cmdline is this emulator.
    if pid_is_live "$pid" && pid_cmdline_is_this_avd "$pid"; then
        return 0
    fi
    # `pgrep -f` matches the full command line of every process it scans,
    # including its own, whose argv holds the pattern text - so an unbracketed
    # pattern always matches itself and this check is a permanent true.  The
    # bracketed first letter still matches a real `.../emulator -avd <AVD>`
    # cmdline while the pattern text can no longer match its own argv.
    if command -v pgrep >/dev/null 2>&1 \
       && pgrep -f "[e]mulator.*-avd ${AVD}( |\$)" >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

emu_provably_dead() {
    # True only with evidence, because the retry it gates starts a second
    # emulator.  Evidence: a resolved pid (resolve_emu_pid only hands out live
    # pids) that is now gone, a device that vanished while booting, a segfault in
    # the log, a crash database this attempt wrote, or no emulator process left
    # for this AVD at all.  A dead $! is NOT evidence (setsid may fork and leave
    # a wrapper that exited while the emulator is fine) and neither is an
    # unresolved pid on its own.
    if [[ -n "${EMU_PID:-}" ]] && ! pid_is_live "$EMU_PID"; then
        return 0
    fi
    [[ "${BOOT_WAIT_FAILURE:-}" == "died" ]] && return 0
    log_has_segfault "$EMU_LOG" && return 0
    crash_db_new >/dev/null && return 0
    emu_process_alive || return 0
    return 1
}

emulator_runtime_dirs() {
    # Where the emulator advertises its running instances: $XDG_RUNTIME_DIR when
    # set, /tmp/android-<user> otherwise.
    [[ -n "${XDG_RUNTIME_DIR:-}" ]] && printf '%s\n' "$XDG_RUNTIME_DIR/avd/running"
    printf '%s\n' "/tmp/android-${USER:-user}/avd/running"
    printf '%s\n' "/tmp/android-${UID:-0}/avd/running"
}

emu_pid_from_runtime_files() {
    local dir file pid
    while IFS= read -r dir; do
        [[ -d "$dir" ]] || continue
        for file in "$dir"/pid_*.ini; do
            [[ -f "$file" ]] || continue
            grep -qxF -- "avd_name=$AVD" "$file" 2>/dev/null || continue
            pid="$(sed -n 's/^pid=\([0-9][0-9]*\)$/\1/p' "$file" 2>/dev/null | head -1)"
            [[ -n "$pid" ]] || continue
            printf '%s' "$pid"
            return 0
        done
    done < <(emulator_runtime_dirs)
    return 1
}

resolve_emu_pid() {
    # The pid teardown is allowed to signal, or nothing at all.  Never guess: a
    # wrong pid here would be killed.  The pid file is authoritative (it carries
    # avd_name); pgrep is the fallback and is only trusted when it matches
    # exactly one process.
    local pid="" matches count
    pid="$(emu_pid_from_runtime_files || true)"
    if [[ -z "$pid" ]] && command -v pgrep >/dev/null 2>&1; then
        matches="$(pgrep -f "emulator.*-avd $AVD( |\$)" 2>/dev/null || true)"
        count="$(printf '%s' "$matches" | awk 'NF { n++ } END { print n + 0 }')"
        if [[ "$count" == "1" ]]; then
            pid="$matches"
        fi
    fi
    if [[ -n "$pid" ]] && pid_is_live "$pid"; then
        printf '%s' "$pid"
        return 0
    fi
    return 1
}

boot_target_alive() {
    # True while the thing we are waiting on still exists.  With a resolved
    # emulator pid that pid is authoritative; without one (unresolvable pid, or
    # a --serial device we did not start) the adb device table is the only
    # signal available.
    local serial="$1"
    if [[ -n "${EMU_PID:-}" ]]; then
        pid_is_live "$EMU_PID"
        return
    fi
    list_serials | grep -qxF -- "$serial"
}

emu_still_running() {
    # True only with positive evidence that the emulator this script started is
    # still alive: the resolved pid (pid_is_live rejects zombies), a live
    # process for this AVD, or the serial listed by `adb devices` in the
    # `device` state.  A serial alone is not evidence - boot_attempt keeps
    # SERIAL set when the device registered and the process died later, which is
    # exactly the state every failed boot exits through.
    local serial="${1:-}"
    if [[ -n "${EMU_PID:-}" ]] && pid_is_live "$EMU_PID"; then
        return 0
    fi
    if emu_process_alive; then
        return 0
    fi
    if [[ -n "$serial" ]]; then
        # `device` state only: a crashed emulator leaves an `offline` row that
        # survives until the adb server restarts, and an offline row is not a
        # running emulator.
        if "$ADB" devices 2>/dev/null | awk '$2 == "device" { print $1 }' | grep -qxF -- "$serial"; then
            return 0
        fi
    fi
    return 1
}

scrcpy_pid_is_this_serial() {
    # A pid is only accepted as "the scrcpy mirroring this device" when its own
    # /proc cmdline says so: a recycled pid, or another scrcpy attached to a
    # different device, must never be named as ours in the teardown note.
    local pid="${1:-}"
    local cmdline=""
    [[ -n "$pid" ]] || return 1
    [[ -r "/proc/$pid/cmdline" ]] || return 1
    cmdline="$( { tr '\0' ' ' < "/proc/$pid/cmdline"; } 2>/dev/null || true )"
    [[ -n "$cmdline" ]] || return 1
    [[ "$cmdline" == *scrcpy* ]] || return 1
    [[ "$cmdline" == *"--serial $SERIAL"* ]] || return 1
    return 0
}

resolve_scrcpy_pid() {
    # The scrcpy pid the teardown note may name, or nothing.  Teardown never
    # guesses, so a GONE process (empty match) or an ambiguous one (more than
    # one match, e.g. a wrapper plus the client) resolves to no pid and the note
    # falls back to the pkill command.
    local matches count
    [[ -n "${SERIAL:-}" ]] || return 1
    command -v pgrep >/dev/null 2>&1 || return 1
    # Bracketed first letter: an unbracketed pattern matches its own argv.
    matches="$(pgrep -f "[s]crcpy.*--serial ${SERIAL}( |\$)" 2>/dev/null || true)"
    count="$(printf '%s' "$matches" | awk 'NF { n++ } END { print n + 0 }')"
    if [[ "$count" == "1" ]] && pid_is_live "$matches" && scrcpy_pid_is_this_serial "$matches"; then
        printf '%s' "$matches"
        return 0
    fi
    return 1
}

scrcpy_still_running() {
    # True only with positive evidence that the mirroring client is alive: a
    # resolved pid whose /proc cmdline still names scrcpy for this serial, or a
    # single matching process in the table.  A pid we merely started ($!) is not
    # evidence - setsid may fork and leave a dead wrapper.
    if [[ -n "${SCRCPY_PID:-}" ]] && pid_is_live "$SCRCPY_PID" \
       && scrcpy_pid_is_this_serial "$SCRCPY_PID"; then
        return 0
    fi
    if [[ -n "${SERIAL:-}" ]] && command -v pgrep >/dev/null 2>&1 \
       && pgrep -f "[s]crcpy.*--serial ${SERIAL}( |\$)" >/dev/null 2>&1; then
        return 0
    fi
    return 1
}

launch_scrcpy_window() {
    # Mirror the device into a desktop window.  The emulator's own window is
    # unusable in this container (it dies in both KVM and TCG mode) while a
    # headless emulator boots in ~26s, so the run stays headless and the picture
    # comes from scrcpy.  Detached: the script still exits after launching.
    #
    # Being unable to mirror is never fatal on the default path - each case says
    # why the run stays headless.  --scrcpy already failed during validation in
    # exactly those two cases, so a forced window cannot silently not appear.
    if [[ "$SCRCPY_MODE" == "off" ]]; then
        note "--no-scrcpy: the device stays headless, no window is opened"
        return 0
    fi
    # With the emulator's own window on a host where that window works, a second
    # window mirroring the same screen is only confusing, so auto mode leaves it
    # to the emulator; --scrcpy still forces one.
    if ((WINDOW == 1)) && [[ "$SCRCPY_MODE" != "force" ]]; then
        note "--window was requested: the emulator draws its own window, so scrcpy is not started"
        return 0
    fi
    if [[ -z "$SCRCPY_BIN" ]]; then
        note "the device is headless: scrcpy is not installed (install it to get a window, e.g.: apt install scrcpy, or: dnf install scrcpy)"
        return 0
    fi
    if [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
        note "the device is headless: neither DISPLAY nor WAYLAND_DISPLAY is set, so scrcpy has no window to open"
        return 0
    fi

    SCRCPY_LOG="$LOG_DIR/scrcpy.log"
    scrcpy_args=( --serial "$SERIAL" --window-title "$SCRCPY_TITLE" --stay-awake )

    # Deterministic placement: with several monitors the compositor is free to
    # put the window anywhere, which is indistinguishable from "the emulator is
    # running but there is nothing to see".  Positioning is optional in scrcpy,
    # so the flag is only used when this build advertises it.
    if "$SCRCPY_BIN" --help 2>&1 | grep -q -- '--window-x'; then
        scrcpy_args+=( --window-x 80 --window-y 80 )
    fi
    : > "$SCRCPY_LOG" 2>/dev/null || true

    # Detached exactly like the emulator (see boot_attempt): nohup alone only
    # ignores SIGHUP and leaves scrcpy in this shell's process group, so setsid
    # is what makes the window outlive the script that started it.
    if command -v setsid >/dev/null 2>&1; then
        setsid nohup "$SCRCPY_BIN" "${scrcpy_args[@]}" >"$SCRCPY_LOG" 2>&1 </dev/null &
    elif command -v nohup >/dev/null 2>&1; then
        nohup "$SCRCPY_BIN" "${scrcpy_args[@]}" >"$SCRCPY_LOG" 2>&1 </dev/null &
    else
        "$SCRCPY_BIN" "${scrcpy_args[@]}" >"$SCRCPY_LOG" 2>&1 </dev/null &
    fi
    SCRCPY_WRAPPER_PID=$!
    SCRCPY_LAUNCHED=1
    disown "$SCRCPY_WRAPPER_PID" 2>/dev/null || true
    SCRCPY_PID="$(resolve_scrcpy_pid || true)"
    note "device mirrored in window \"$SCRCPY_TITLE\" (scrcpy runs detached; log: $SCRCPY_LOG)"
    return 0
}

boot_wait() {
    local serial="$1"
    local deadline=$((SECONDS + TIMEOUT))
    local prop
    BOOT_WAIT_FAILURE="timeout"
    printf '    waiting up to %ss for %s to finish booting ' "$TIMEOUT" "$serial"
    while ((SECONDS < deadline)); do
        prop="$("$ADB" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '[:space:]')"
        if [[ "$prop" == "1" ]]; then
            printf '\n'
            BOOT_WAIT_FAILURE=""
            return 0
        fi
        # A dead emulator never answers getprop, so without this the loop would
        # spin for the whole timeout against a device that no longer exists.
        if ! boot_target_alive "$serial"; then
            printf '\n'
            BOOT_WAIT_FAILURE="died"
            return 1
        fi
        printf '.'
        sleep 3
    done
    printf '\n'
    return 1
}

pm_ready() {
    local serial="$1"
    local i out
    for ((i = 0; i < 20; i++)); do
        out="$("$ADB" -s "$serial" shell pm path android 2>/dev/null | tr -d '[:space:]')"
        if [[ "$out" == package:* ]]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

build_emu_args() {
    # Reads TCG_MODE (0 accelerated / 1 software), HEADLESS, WIPE and
    # RETRY_READ_ONLY.
    emu_args=( -avd "$AVD"
               -no-snapshot
               -no-boot-anim
               -netdelay none
               -netspeed full
               -gpu "$GPU_MODE" )
    if ((HEADLESS == 1)); then
        # -no-audio keeps Linux hosts without a sound server from failing to start.
        emu_args+=( -no-window -no-audio )
    fi
    if ((WIPE == 1)); then
        emu_args+=( -wipe-data )
    fi
    if ((TCG_MODE == 1)); then
        # Software CPU.  Required where KVM is reported usable by
        # `emulator -accel-check` yet the accelerated CPU init crashes: nested
        # virtualisation and containers.
        emu_args+=( -accel off )
    fi
    if ((RETRY_READ_ONLY == 1)); then
        # Last resort only (see wait_avd_released): used when the AVD cannot be
        # proven free, never on a normal start, because a read-only instance
        # cannot persist user data.
        emu_args+=( -read-only )
    fi
}

boot_attempt() {
    # One start + boot attempt.  $1 = log path, $2 = label for the notes.
    # Returns 0 once sys.boot_completed=1 was seen; otherwise 1 with
    # BOOT_WAIT_FAILURE set to "died", "no-serial" or "timeout".  The log tail
    # is left to the caller so it is printed once, by the failure report.
    local log="$1"
    local label="$2"
    local previous_serial="$SERIAL"
    local detect_rc=0

    # A dead emulator from a previous attempt may still sit in the adb device
    # table as offline.  Give the server a moment to drop it and keep it out of
    # the "new serial" set either way, so this attempt can only find its own.
    if [[ -n "$previous_serial" ]]; then
        if ! wait_serial_gone "$previous_serial"; then
            note "the previous attempt's serial $previous_serial is still listed; excluding it"
        fi
        before_serials="$("$ADB" devices 2>/dev/null | awk '$2 == "device" { print $1 }')"
        before_serials="$(printf '%s\n%s\n' "$before_serials" "$previous_serial")"
    fi

    EMU_LOG="$log"
    EMU_PID=""
    SERIAL=""
    BOOT_WAIT_FAILURE=""
    CRASH_DB_BASELINE="$(crash_db_list)"

    : > "$EMU_LOG" || die "cannot write the emulator log" "check the permissions of $LOG_DIR"

    note "starting [$label]: $EMULATOR ${emu_args[*]}"
    note "emulator log: $EMU_LOG"

    # Detach the emulator from this shell.  `nohup` alone is NOT enough: it only
    # makes the process ignore SIGHUP.  It does not create a new session or
    # process group, so the emulator stays in this shell's process group and is
    # torn down with it when the caller's group is killed (harness/CI-ish
    # parent, closed terminal).  setsid makes it a session leader that outlives
    # the caller; nohup and a bare background job remain as fallbacks for hosts
    # without setsid.
    if command -v setsid >/dev/null 2>&1; then
        setsid nohup "$EMULATOR" "${emu_args[@]}" >"$EMU_LOG" 2>&1 </dev/null &
    elif command -v nohup >/dev/null 2>&1; then
        nohup "$EMULATOR" "${emu_args[@]}" >"$EMU_LOG" 2>&1 </dev/null &
    else
        "$EMULATOR" "${emu_args[@]}" >"$EMU_LOG" 2>&1 </dev/null &
    fi
    EMU_WRAPPER_PID=$!
    STARTED_BY_US=1
    disown "$EMU_WRAPPER_PID" 2>/dev/null || true

    # $! is only the wrapper when setsid forked, so resolve the emulator itself
    # before anything is allowed to signal a pid.
    EMU_PID="$(resolve_emu_pid || true)"
    if [[ -n "$EMU_PID" ]]; then
        note "emulator pid: $EMU_PID"
    else
        note "emulator pid could not be resolved: teardown will rely on \"$ADB -s <serial> emu kill\""
    fi

    SERIAL="$(detect_new_serial)" || detect_rc=$?
    if ((detect_rc != 0)); then
        if ((detect_rc == 2)); then
            BOOT_WAIT_FAILURE="died"
        else
            BOOT_WAIT_FAILURE="no-serial"
        fi
        SERIAL=""
        return 1
    fi
    note "emulator registered as $SERIAL"

    # One retry now that the emulator is definitely up: the pid file and the
    # process name are both in place by the time adb sees the device.
    if [[ -z "$EMU_PID" ]]; then
        EMU_PID="$(resolve_emu_pid || true)"
        if [[ -n "$EMU_PID" ]]; then
            note "emulator pid: $EMU_PID"
        fi
    fi

    say "waiting for $SERIAL to finish booting"
    if boot_wait "$SERIAL"; then
        return 0
    fi
    BOOT_WAIT_FAILURE="${BOOT_WAIT_FAILURE:-timeout}"
    return 1
}

boot_failure_reason() {
    case "${BOOT_WAIT_FAILURE:-}" in
        died)      printf 'the emulator process died' ;;
        no-serial) printf 'the emulator never registered with adb' ;;
        timeout)   printf 'no sys.boot_completed=1 within %ss' "$TIMEOUT" ;;
        *)         printf 'the boot wait failed (%s)' "${BOOT_WAIT_FAILURE:-unknown}" ;;
    esac
}

emu_diagnostics() {
    # Fills DIAG with the evidence and the next-step lines of a failure report.
    local segfault=0 log db
    DIAG=()
    for log in "${ATTEMPT_LOGS[@]}"; do
        if log_has_segfault "$log"; then
            segfault=1
        fi
    done
    if ((segfault == 1)); then
        DIAG+=( "a 139 / 'Segmentation fault' line is in the log above: this host cannot" \
                "run the accelerated emulator (the accelerated CPU init crashes even" \
                "though 'emulator -accel-check' reports KVM usable - nested" \
                "virtualisation or a container without a usable CPU model)" )
    else
        DIAG+=( "a 139 exit / 'Segmentation fault' line in a log means the host cannot run" \
                "the accelerated emulator; there is none in the logs of these attempts" )
    fi
    if db="$(crash_db_new)"; then
        DIAG+=( "emulator crash database written by this run: $db" )
    elif db="$(crash_db_latest)"; then
        DIAG+=( "an older emulator crash database exists (not from this run): $db" )
    fi
    # The one failure that is not a failure: the emulator refused to start
    # because this AVD is already booted somewhere (or its lock outlived it).
    # Saying "never registered with adb" alone leaves the user with no remedy.
    local same_avd=0
    for log in "${ATTEMPT_LOGS[@]}"; do
        if grep -q 'Running multiple emulators with the same AVD' "$log" 2>/dev/null; then
            same_avd=1
            break
        fi
    done
    if ((same_avd == 1)); then
        DIAG+=( "AVD $AVD is already running (or its <avd>.avd/*.lock outlived it): this is" \
                "not a boot failure - the second start never got that far.  Reuse or stop it:" \
                "  \"$ADB\" devices -l             # find its serial" \
                "  bash tool/$PROG --serial <serial> --no-build" \
                "  \"$ADB\" -s <serial> emu kill    # then re-run for a fresh boot" )
    fi
    DIAG+=( "attempt logs: ${ATTEMPT_LOGS[*]}" )
    DIAG+=( "what to try next:" \
            "  - software CPU explicitly:    bash tool/$PROG --tcg --no-build" \
            "  - an already-running device:  bash tool/$PROG --serial <serial> --no-build" \
            "  - a host session outside this container, where KVM really works" )
}

report_scrcpy_teardown() {
    # The window this run opened, named once, with the command that closes it.
    # Nothing is claimed without positive liveness evidence, and a pid is only
    # named when its own /proc cmdline proves it is the scrcpy for this serial.
    ((SCRCPY_LAUNCHED == 1)) || return 0
    if ! scrcpy_still_running; then
        note "no scrcpy window left running"
        return 0
    fi
    if [[ -n "${SCRCPY_PID:-}" ]] && pid_is_live "$SCRCPY_PID" \
       && scrcpy_pid_is_this_serial "$SCRCPY_PID"; then
        note "scrcpy window \"$SCRCPY_TITLE\" left open (pid $SCRCPY_PID) — stop it with: kill $SCRCPY_PID"
    else
        note "scrcpy window \"$SCRCPY_TITLE\" left open — stop it with: pkill -f \"scrcpy --serial $SERIAL\""
    fi
    return 0
}

cleanup() {
    local rc=$?
    trap - EXIT
    if [[ -n "${EMU_WRAPPER_PID:-}" ]]; then
        disown "$EMU_WRAPPER_PID" 2>/dev/null || true
    fi
    if [[ -n "${SCRCPY_WRAPPER_PID:-}" ]]; then
        disown "$SCRCPY_WRAPPER_PID" 2>/dev/null || true
    fi
    if ((SHUTDOWN == 1)) && ((STARTED_BY_US == 1)) && [[ -n "$SERIAL" ]]; then
        note "shutting down emulator $SERIAL"
        "$ADB" -s "$SERIAL" emu kill >/dev/null 2>&1 || true
        local i
        for ((i = 0; i < 15; i++)); do
            list_serials | grep -qxF -- "$SERIAL" || break
            sleep 1
        done
        if list_serials | grep -qxF -- "$SERIAL"; then
            # Only the pid resolved for this AVD at start is ever signalled; when
            # it could not be resolved EMU_PID is empty and `adb emu kill` above
            # stays the only mechanism.
            [[ -n "${EMU_PID:-}" ]] && kill -TERM "$EMU_PID" 2>/dev/null || true
        fi
        if ((SCRCPY_LAUNCHED == 1)); then
            # scrcpy exits by itself when its device disappears; this only says so.
            note "the scrcpy window closes with the device it was mirroring"
        fi
    else
        if ((STARTED_BY_US == 1)) && [[ -n "$SERIAL" ]]; then
            # SERIAL alone does not mean the emulator is up: every failed boot
            # exits through die(), and it can leave SERIAL set from an attempt
            # whose process died afterwards.  Claiming "left running" (and
            # suggesting `emu kill`) is only truthful with positive liveness
            # evidence.  This is the only place the note is printed.
            if emu_still_running "$SERIAL"; then
                note "emulator $SERIAL left running — stop it with: $ADB -s $SERIAL emu kill"
            else
                note "no emulator left running"
            fi
        fi
        # Independent of who owns the device: a window this run opened is ours to
        # report (a --serial run never starts the emulator but does start scrcpy).
        report_scrcpy_teardown
    fi
    exit "$rc"
}

# --- argument parsing -------------------------------------------------------
((BASH_VERSINFO[0] >= 4)) || die "bash 4 or newer is required (found ${BASH_VERSION:-unknown})"

while (( $# > 0 )); do
    case "$1" in
        -h|--help)
            usage
            exit 0
            ;;
        --avd)
            require_value "$1" "${2:-}"
            AVD="$2"
            shift 2
            ;;
        --avd=*)
            AVD="${1#*=}"
            shift
            ;;
        --image)
            require_value "$1" "${2:-}"
            IMAGE="$2"
            shift 2
            ;;
        --image=*)
            IMAGE="${1#*=}"
            shift
            ;;
        --device)
            require_value "$1" "${2:-}"
            DEVICE="$2"
            shift 2
            ;;
        --device=*)
            DEVICE="${1#*=}"
            shift
            ;;
        --serial)
            require_value "$1" "${2:-}"
            SERIAL="$2"
            shift 2
            ;;
        --serial=*)
            SERIAL="${1#*=}"
            shift
            ;;
        --timeout)
            require_value "$1" "${2:-}"
            TIMEOUT="$2"
            shift 2
            ;;
        --timeout=*)
            TIMEOUT="${1#*=}"
            shift
            ;;
        --wipe)
            WIPE=1
            shift
            ;;
        --recreate)
            RECREATE=1
            shift
            ;;
        --window)
            WINDOW=1
            shift
            ;;
        --scrcpy)
            SCRCPY_MODE="force"
            shift
            ;;
        --no-scrcpy)
            SCRCPY_MODE="off"
            shift
            ;;
        --tcg)
            TCG=1
            shift
            ;;
        --no-tcg-fallback)
            NO_TCG_FALLBACK=1
            shift
            ;;
        --clean)
            CLEAN=1
            shift
            ;;
        --no-build)
            NO_BUILD=1
            shift
            ;;
        --shutdown)
            SHUTDOWN=1
            shift
            ;;
        --logcat)
            LOGCAT=1
            shift
            ;;
        --)
            shift
            break
            ;;
        -*)
            die "unknown option: $1" "run: bash tool/$PROG --help"
            ;;
        *)
            die "unexpected argument: $1" "run: bash tool/$PROG --help"
            ;;
    esac
done

(( $# == 0 )) || die "unexpected trailing arguments: $*" "run: bash tool/$PROG --help"

[[ "$AVD" =~ ^[A-Za-z0-9._-]+$ ]] || die "invalid --avd '$AVD'" "the name may only contain letters, digits, dot, underscore and dash"

if ! [[ "$TIMEOUT" =~ ^[0-9]+$ ]] || ((10#$TIMEOUT == 0)); then
    die "invalid --timeout '$TIMEOUT'" "expected a positive number of seconds, e.g. --timeout 300"
fi
# Normalise leading zeros so the later arithmetic never reads an octal literal.
TIMEOUT="$((10#$TIMEOUT))"

if ((CLEAN == 1)) && ((NO_BUILD == 1)); then
    die "--clean and --no-build contradict each other" "drop one of the two flags"
fi

if ((WINDOW == 1)) && [[ -z "${DISPLAY:-}" ]]; then
    die "--window was requested but DISPLAY is not set" "either export DISPLAY (or forward X11) or drop --window for the default headless run"
fi

# scrcpy is how the device becomes visible on this host: the emulator's own
# window dies here in both CPU modes.  Resolve the binary once, now, so a forced
# --scrcpy fails here instead of after a slow boot, and so the default path can
# say why it stayed headless.
SCRCPY_BIN="$(command -v scrcpy 2>/dev/null || true)"

if [[ "$SCRCPY_MODE" == "force" ]]; then
    if [[ -z "$SCRCPY_BIN" ]]; then
        die "--scrcpy was requested but scrcpy is not on PATH" \
            "install it (e.g. apt install scrcpy, or: dnf install scrcpy)" \
            "the default run mirrors the device automatically once scrcpy is available"
    fi
    if [[ -z "${DISPLAY:-}" && -z "${WAYLAND_DISPLAY:-}" ]]; then
        die "--scrcpy was requested but neither DISPLAY nor WAYLAND_DISPLAY is set" \
            "there is no display for the window to open on" \
            "export DISPLAY (or forward X11) or export WAYLAND_DISPLAY, or drop --scrcpy"
    fi
fi

# Headless unless --window.  A set-but-stale DISPLAY (containers, detached tmux,
# X11 forwarded from a dead host) must not silently turn this into a graphical
# start the host cannot serve.
if ((WINDOW == 1)); then
    HEADLESS=0
fi

if ((LOGCAT == 1)) && ((SHUTDOWN == 1)); then
    note "--logcat implies keeping the emulator: ignoring --shutdown"
    SHUTDOWN=0
fi

if [[ -n "$SERIAL" ]] && ((SHUTDOWN == 1)); then
    note "--serial was given, so no emulator is started here: ignoring --shutdown"
    SHUTDOWN=0
fi

if [[ -n "$SERIAL" ]] && { ((WIPE == 1)) || ((RECREATE == 1)); }; then
    note "--wipe/--recreate only apply when this script boots an emulator: ignoring (--serial given)"
    WIPE=0
    RECREATE=0
fi

if [[ -n "$SERIAL" ]] && { ((TCG == 1)) || ((NO_TCG_FALLBACK == 1)); }; then
    note "--tcg/--no-tcg-fallback only apply when this script boots an emulator: ignoring (--serial given)"
    TCG=0
    NO_TCG_FALLBACK=0
fi

if ((TCG == 1)) && ((NO_TCG_FALLBACK == 1)); then
    note "--no-tcg-fallback only affects the automatic retry; --tcg already starts in software mode"
fi

TCG_MODE="$TCG"

# --- 1. repo root + toolchain ----------------------------------------------
say "resolving the repository root and the JDK"

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" >/dev/null 2>&1 && pwd -P)" \
    || die "cannot resolve the directory of ${BASH_SOURCE[0]}"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." >/dev/null 2>&1 && pwd -P)" \
    || die "cannot resolve the repository root from $SCRIPT_DIR"

[[ -f "$REPO_ROOT/gradlew" ]] || die "$REPO_ROOT/gradlew is missing" "run this script from a Foglietto checkout (expected repo root: $REPO_ROOT)"

cd -- "$REPO_ROOT" || die "cannot cd into the repository root $REPO_ROOT"

GRADLEW="$REPO_ROOT/gradlew"
APK="$REPO_ROOT/androidApp/build/outputs/apk/debug/androidApp-debug.apk"
LOG_DIR="$REPO_ROOT/build"
EMU_LOG="$LOG_DIR/emulator.log"

note "repo root: $REPO_ROOT"

command -v java >/dev/null 2>&1 \
    || die "java not found on PATH (Gradle 9.1 / AGP 9 need JDK 17)" \
           "install a JDK 17 and put it on PATH, e.g.:" \
           "  export JAVA_HOME=/path/to/jdk-17" \
           "  export PATH=\"\$JAVA_HOME/bin:\$PATH\""

java_major="$(java -version 2>&1 | sed -n 's/.*version "\([0-9][0-9]*\).*/\1/p' | head -1)"
if [[ -n "$java_major" ]] && ((java_major < 17)); then
    die "java $java_major is too old: JDK 17 or newer is required" \
        "install a JDK 17 and select it, e.g. export JAVA_HOME=/path/to/jdk-17"
fi
note "java: ${java_major:-unknown} ($(command -v java))"

# --- 2. Android SDK ---------------------------------------------------------
say "resolving the Android SDK"

resolve_cmdline_tool() {
    local sdk="$1"
    local name="$2"
    local candidate
    for candidate in "$sdk/cmdline-tools/latest/bin/$name" "$sdk/tools/bin/$name"; do
        if [[ -x "$candidate" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    for candidate in "$sdk"/cmdline-tools/*/bin/"$name"; do
        if [[ -x "$candidate" ]]; then
            printf '%s' "$candidate"
            return 0
        fi
    done
    return 1
}

sdk_candidates=()
[[ -n "${ANDROID_HOME:-}" ]] && sdk_candidates+=("$ANDROID_HOME")
[[ -n "${ANDROID_SDK_ROOT:-}" ]] && sdk_candidates+=("$ANDROID_SDK_ROOT")
[[ -n "${HOME:-}" ]] && sdk_candidates+=("$HOME/Android/Sdk")
sdk_candidates+=("/opt/android-sdk" "/usr/lib/android-sdk")

SDK=""
ADB=""
EMULATOR=""
AVDMANAGER=""
SDKMANAGER=""
sdk_report=""

for candidate in "${sdk_candidates[@]}"; do
    if [[ ! -d "$candidate" ]]; then
        sdk_report="${sdk_report}  $candidate (not a directory)"$'\n'
        continue
    fi
    adb_try="$candidate/platform-tools/adb"
    emu_try="$candidate/emulator/emulator"
    avd_try="$(resolve_cmdline_tool "$candidate" avdmanager || true)"
    sdk_try="$(resolve_cmdline_tool "$candidate" sdkmanager || true)"

    missing=""
    [[ -x "$adb_try" ]] || missing="${missing} platform-tools/adb"
    [[ -x "$emu_try" ]] || missing="${missing} emulator/emulator"
    [[ -n "$avd_try" ]] || missing="${missing} cmdline-tools/<ver>/bin/avdmanager"
    [[ -n "$sdk_try" ]] || missing="${missing} cmdline-tools/<ver>/bin/sdkmanager"

    if [[ -z "$missing" ]]; then
        SDK="$candidate"
        ADB="$adb_try"
        EMULATOR="$emu_try"
        AVDMANAGER="$avd_try"
        SDKMANAGER="$sdk_try"
        break
    fi
    sdk_report="${sdk_report}  $candidate (exists, missing:${missing})"$'\n'
done

if [[ -z "$SDK" ]]; then
    printf '    SDK locations tried:\n%s' "$sdk_report" >&2
    die "no usable Android SDK found" \
        "point ANDROID_HOME at a complete SDK, or install the missing pieces:" \
        "  sdkmanager 'platform-tools' 'emulator' 'platforms;android-36' 'build-tools;36.0.0'"
fi

export ANDROID_HOME="$SDK"
export ANDROID_SDK_ROOT="$SDK"

note "SDK: $SDK"
note "adb: $ADB"
note "emulator: $EMULATOR"
note "avdmanager: $AVDMANAGER"

mkdir -p "$LOG_DIR" || die "cannot create $LOG_DIR" "check the permissions of $REPO_ROOT"

"$ADB" start-server >/dev/null 2>&1 \
    || die "adb start-server failed" "check that it runs at all: \"$ADB\" version"
# Snapshot only healthy (`device` state) serials: a stale `offline` line left by
# a previously killed emulator must not mask the one we are about to start.
before_serials="$("$ADB" devices 2>/dev/null | awk '$2 == "device" { print $1 }')"
trap cleanup EXIT

# --- 3. AVD ----------------------------------------------------------------
avd_exists() {
    local name="$1"
    "$AVDMANAGER" list avd -c 2>/dev/null | grep -qxF -- "$name"
}

if [[ -n "$SERIAL" ]]; then
    say "using the existing device $SERIAL (no AVD is created or booted)"
else
    say "aligning the AVD home used by avdmanager and the emulator"

    # avdmanager and the emulator do not agree by default: on hosts where the
    # XDG config dir exists avdmanager writes $XDG_CONFIG_HOME/.android/avd while
    # the emulator only searches $ANDROID_AVD_HOME, $ANDROID_SDK_HOME/avd and
    # $HOME/.android/avd.  Resolve one directory, export it for both tools, and
    # prove in the boot section below that the AVD really landed there.
    if [[ -n "${ANDROID_AVD_HOME:-}" ]]; then
        note "ANDROID_AVD_HOME already set: $ANDROID_AVD_HOME"
    else
        [[ -n "${HOME:-}" ]] || die "HOME is not set, so the AVD home cannot be resolved" \
            "export ANDROID_AVD_HOME to the directory holding <name>.avd/<name>.ini (or export HOME)" \
            "run: bash tool/$PROG --help"
        xdg_avd_home="${XDG_CONFIG_HOME:-$HOME/.config}/.android/avd"
        classic_avd_home="$HOME/.android/avd"
        if [[ -d "$xdg_avd_home" ]]; then
            ANDROID_AVD_HOME="$xdg_avd_home"
            note "reusing the existing XDG AVD home (where avdmanager writes): $ANDROID_AVD_HOME"
        else
            ANDROID_AVD_HOME="$classic_avd_home"
            note "using the classic AVD home: $ANDROID_AVD_HOME"
        fi
    fi

    export ANDROID_AVD_HOME

    # Strip a trailing slash so the parent (ANDROID_USER_HOME) resolves correctly.
    while [[ "$ANDROID_AVD_HOME" == */ ]]; do
        ANDROID_AVD_HOME="${ANDROID_AVD_HOME%/}"
    done
    [[ -n "$ANDROID_AVD_HOME" ]] || die "ANDROID_AVD_HOME resolves to an empty path" \
        "set it to a real directory, e.g. export ANDROID_AVD_HOME=\"\$HOME/.android/avd\""
    export ANDROID_AVD_HOME

    resolved_user_home="$(dirname -- "$ANDROID_AVD_HOME")"
    if [[ -n "${ANDROID_USER_HOME:-}" && "$ANDROID_USER_HOME" != "$resolved_user_home" ]]; then
        note "keeping your ANDROID_USER_HOME=$ANDROID_USER_HOME (it does not match the AVD home parent)"
    else
        ANDROID_USER_HOME="$resolved_user_home"
        export ANDROID_USER_HOME
    fi

    mkdir -p "$ANDROID_AVD_HOME" \
        || die "cannot create the AVD home $ANDROID_AVD_HOME" \
               "check the permissions of $(dirname -- "$ANDROID_AVD_HOME")"

    note "ANDROID_AVD_HOME: $ANDROID_AVD_HOME"
    note "ANDROID_USER_HOME: $ANDROID_USER_HOME"

    say "ensuring the AVD $AVD exists"

    "$AVDMANAGER" list avd -c >/dev/null 2>&1 \
        || die "avdmanager cannot list AVDs" \
               "run it by hand to see the error: \"$AVDMANAGER\" list avd"

    image_rel="$(printf '%s' "$IMAGE" | tr ';' '/')"
    image_dir="$SDK/$image_rel"
    [[ -d "$image_dir" ]] || die "system image '$IMAGE' is not installed in $SDK" \
        "install it with:" \
        "  \"$SDKMANAGER\" \"$IMAGE\""

    if ((RECREATE == 1)); then
        if avd_exists "$AVD"; then
            note "deleting AVD $AVD (--recreate)"
            "$AVDMANAGER" delete avd -n "$AVD" >/dev/null 2>&1 \
                || die "avdmanager delete avd -n $AVD failed" \
                       "list the AVDs with: \"$AVDMANAGER\" list avd"
        fi
    fi

    if avd_exists "$AVD"; then
        note "reusing AVD $AVD"
    else
        note "creating AVD $AVD ($IMAGE on $DEVICE)"
        printf 'no\n' | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d "$DEVICE" \
            || die "avdmanager create avd failed for $AVD" \
                   "run it by hand to see the full error:" \
                   "  printf 'no\\n' | \"$AVDMANAGER\" create avd -n $AVD -k \"$IMAGE\" -d $DEVICE"
        avd_exists "$AVD" || die "AVD $AVD is still not listed after creation" \
            "list the AVDs with: \"$AVDMANAGER\" list avd"
    fi

    if ((WIPE == 1)); then
        note "--wipe: booting with -wipe-data (user data reset to factory)"
    fi
fi

# --- 4. boot ----------------------------------------------------------------
say "booting the emulator"

# An instance of this AVD that is already up is adopted instead of starting a
# second one: the second start exits with "Running multiple emulators with the
# same AVD", which reads like a boot failure even though the device behind the
# lock is healthy.  --wipe is the exception: it promises fresh user data, which
# cannot be applied to a live instance, so there it is a clear refusal instead.
ADOPTED=0
if [[ -z "$SERIAL" ]]; then
    running_serial="$(running_serial_for_avd "$AVD" || true)"
    if [[ -n "$running_serial" ]] && ((WIPE == 1)); then
        die "cannot apply --wipe: AVD $AVD is already running as $running_serial" \
            "stop that emulator first, then re-run:" \
            "  \"$ADB\" -s $running_serial emu kill" \
            "or drop --wipe to reuse the running instance as it is"
    fi
    if [[ -n "$running_serial" ]]; then
        SERIAL="$running_serial"
        ADOPTED=1
        STARTED_BY_US=0
        note "AVD $AVD is already running as $SERIAL: reusing it, no second emulator is started"
        note "stop it for a fresh boot with: \"$ADB\" -s $SERIAL emu kill"
    fi
fi

if [[ -n "$SERIAL" ]]; then
    if ((ADOPTED == 1)); then
        say "waiting for $SERIAL to finish booting"
        boot_wait "$SERIAL" || die "the emulator already running as $SERIAL ($AVD) did not finish booting" \
            "stop it and start again for a clean boot:" \
            "  \"$ADB\" -s $SERIAL emu kill" \
            "  bash tool/$PROG"
        note "boot completed on the reused emulator"
    else
        note "not starting an emulator: --serial $SERIAL"
        list_serials | grep -qxF -- "$SERIAL" \
            || die "no device with serial '$SERIAL' is attached" \
                   "list the attached devices with:" \
                   "  \"$ADB\" devices -l"
    fi
else
    # The AVD must be exactly where the emulator looks, not merely listed by
    # avdmanager: the two tools disagree on the default home (see the AVD
    # section).  This is the cheap, definitive check of that agreement.
    avd_ini="$ANDROID_AVD_HOME/$AVD.ini"
    if [[ ! -f "$avd_ini" ]]; then
        die "the AVD definition is not where the emulator looks: $avd_ini is missing" \
            "resolved ANDROID_AVD_HOME: $ANDROID_AVD_HOME" \
            "avdmanager wrote elsewhere; set ANDROID_AVD_HOME explicitly to that directory," \
            "or re-run with --recreate so the AVD is created inside the resolved home"
    fi
    note "AVD definition: $AVD.ini"

    [[ -x "$EMULATOR" ]] || die "the emulator binary is not executable: $EMULATOR" \
        "reinstall it with: \"$SDKMANAGER\" emulator"

    note "GPU: $GPU_MODE ($GPU_REASON)"

    if ((TCG_MODE == 1)); then
        note "CPU: software (TCG), -accel off, as requested by --tcg"
    else
        note "CPU: accelerated (KVM); if that start dies before booting, it is retried once"
        note "with -accel off (--no-tcg-fallback disables the retry)"
    fi

    # At most two iterations: the fallback below is gated on this being the first
    # attempt, and anything after it exits through die().  There is no way back
    # into a third start.
    while :; do
        if ((TCG_MODE == 1)); then
            attempt_log="$LOG_DIR/emulator-tcg.log"
            attempt_label="software CPU, -accel off"
            note "the first software-emulated boot is slow: allow several minutes"
            note "(the budget is --timeout, currently ${TIMEOUT}s)"
        else
            attempt_log="$LOG_DIR/emulator.log"
            attempt_label="accelerated CPU, KVM"
        fi

        build_emu_args

        if boot_attempt "$attempt_log" "$attempt_label"; then
            break
        fi

        ATTEMPT_LOGS+=( "$attempt_log" )
        ATTEMPT_LABELS+=( "$attempt_label" )

        if (( ${#ATTEMPT_LOGS[@]} == 1 )) && ((TCG_MODE == 0)) \
           && ((NO_TCG_FALLBACK == 0)) && emu_provably_dead; then
            printf '\n[WARN] the accelerated emulator died before it booted: %s\n' \
                "$(boot_failure_reason)" >&2
            # The dead instance can still hold the AVD for a moment (a lingering
            # process, or the <avd>.avd/*.lock it never removed), and a second
            # emulator then refuses to start - so the fallback would never boot.
            # Prove the AVD is free first, and keep "the retry could not start"
            # apart from "the retry started and failed to boot".
            wait_avd_released
            if ((AVD_RELEASE_STATE == 1)); then
                lock_report=()
                while IFS= read -r lock_path; do
                    [[ -n "$lock_path" ]] && lock_report+=( "$lock_path" )
                done < <(avd_lock_files 2>/dev/null || true)

                # A killed emulator leaves <avd>.avd/*.lock behind with nothing
                # owning it, and that blocks every later start.  Removing it is
                # safe only with both negatives (no live process for this AVD, no
                # live pid in the emulator's own runtime files), so a lock that
                # still has an owner keeps the hard stop below.  With a cleared
                # lock the retry starts normally, i.e. without -read-only.
                stale_cleared=0
                if (( ${#lock_report[@]} > 0 )) && avd_lock_is_stale; then
                    if removed_locks="$(clear_stale_avd_locks)"; then
                        while IFS= read -r removed_lock; do
                            [[ -n "$removed_lock" ]] || continue
                            note "cleared a stale AVD lock (no live process or pid owns it): $removed_lock"
                        done <<< "$removed_locks"
                        stale_cleared=1
                    fi
                fi

                if ((stale_cleared == 0)); then
                    if (( ${#lock_report[@]} > 0 )); then
                        occupancy_report="lock still present after ${AVD_RELEASE_WAIT_SECS}s: ${lock_report[*]}"
                    else
                        avd_dir_now="$(avd_instance_dir || true)"
                        [[ -n "$avd_dir_now" ]] || avd_dir_now="<ANDROID_AVD_HOME>/$AVD.avd"
                        occupancy_report="no *.lock is left in $avd_dir_now, but a process for this AVD is still running after ${AVD_RELEASE_WAIT_SECS}s"
                    fi
                    holder="$(avd_lock_holder_pid || true)"
                    if [[ -n "$holder" ]]; then
                        holder_report="the AVD is still held by pid $holder"
                        kill_hint="kill $holder"
                    else
                        holder_report="no pid for this AVD could be resolved: the lock may be stale"
                        kill_hint="kill <pid>     # only for a pid you identified as the leftover emulator"
                    fi
                    show_log_tail 20
                    die "the retry could not start: the AVD was still locked by the previous attempt" \
                        "$occupancy_report" \
                        "$holder_report" \
                        "no second emulator was started, so -accel off was never tried: this is not a boot failure" \
                        "free the AVD, then re-run:" \
                        "  \"$ADB\" devices -l              # find the leftover emulator's serial" \
                        "  \"$ADB\" -s <serial> emu kill" \
                        "  $kill_hint"
                fi
                note "the AVD is free again, so the retry starts normally (no -read-only)"
            fi
            if ((RETRY_READ_ONLY == 1)); then
                note "the AVD could not be proven free: the retry gets -read-only, so user data"
                note "will not persist and -wipe-data cannot apply"
            fi
            printf '[WARN] retrying once with software emulation (-accel off): slow but functional\n' >&2
            TCG_MODE=1
            SERIAL=""
            continue
        fi

        show_log_tail 40
        emu_diagnostics
        if (( ${#ATTEMPT_LOGS[@]} > 1 )); then
            die "the emulator failed to boot in ${#ATTEMPT_LOGS[@]} attempts ($(boot_failure_reason)): ${ATTEMPT_LABELS[*]}" \
                "${DIAG[@]}"
        fi
        die "the emulator did not finish booting ($(boot_failure_reason))" \
            "${DIAG[@]}"
    done
fi

# --- 5. package manager ------------------------------------------------------
# The boot wait itself lives in boot_attempt, so the retry could re-run it.
pm_ready "$SERIAL" || die "the package manager is not responding on $SERIAL" \
    "the system booted but PM is wedged: retry with --wipe, or inspect $EMU_LOG"

note "boot completed, package manager responsive"

# --- 6. build ---------------------------------------------------------------
say "building the debug APK"

if ((NO_BUILD == 1)); then
    note "skipping Gradle (--no-build)"
else
    gradle_args=( :androidApp:assembleDebug --console=plain )
    if ((CLEAN == 1)); then
        gradle_args=( clean "${gradle_args[@]}" )
    fi

    note "running: ./gradlew ${gradle_args[*]}"
    if [[ -x "$GRADLEW" ]]; then
        "$GRADLEW" "${gradle_args[@]}" \
            || die "Gradle failed: ./gradlew ${gradle_args[*]}" \
                   "rerun with a stack trace to see the failure:" \
                   "  ./gradlew :androidApp:assembleDebug --stacktrace"
    else
        bash "$GRADLEW" "${gradle_args[@]}" \
            || die "Gradle failed: bash ./gradlew ${gradle_args[*]}" \
                   "the wrapper is not executable; fix it with: chmod +x gradlew"
    fi
fi

[[ -f "$APK" ]] || die "debug APK not found at $APK" \
    "build it with:" \
    "  ./gradlew :androidApp:assembleDebug" \
    "and re-run without --no-build"

note "APK: $APK"

# --- 7. install + launch ----------------------------------------------------
say "installing and launching $COMPONENT on $SERIAL"

install_out="$("$ADB" -s "$SERIAL" install -r -t "$APK" 2>&1)"
install_rc=$?
printf '%s\n' "$install_out" | sed 's/^/    /'
if ((install_rc != 0)) || [[ "$install_out" != *Success* ]]; then
    die "adb install failed on $SERIAL" \
        "common causes:" \
        "  INSTALL_FAILED_INSUFFICIENT_STORAGE -> re-run with --wipe" \
        "  device offline                       -> \"$ADB\" -s $SERIAL get-state"
fi

start_out="$("$ADB" -s "$SERIAL" shell am start -n "$COMPONENT" 2>&1)"
printf '%s\n' "$start_out" | sed 's/^/    /'
if [[ "$start_out" == *Error* ]] || [[ "$start_out" == *"does not exist"* ]]; then
    die "am start failed for $COMPONENT on $SERIAL" \
        "check that the APK really carries that activity:" \
        "  \"$SDK/build-tools\"/*/aapt2 dump xmltree \"$APK\" --file AndroidManifest.xml"
fi

printf '\n==> app launched on %s (%s)\n' "$SERIAL" "$COMPONENT"

# --- 8. device window -------------------------------------------------------
say "device window (scrcpy)"
launch_scrcpy_window

# --- 9. tail / teardown -----------------------------------------------------
if ((LOGCAT == 1)); then
    say "streaming logcat for $APP_ID (Ctrl-C to stop)"
    app_pid="$("$ADB" -s "$SERIAL" shell pidof "$APP_ID" 2>/dev/null | tr -d '[:space:]')"
    if [[ -n "$app_pid" ]]; then
        note "pid $app_pid"
        "$ADB" -s "$SERIAL" logcat --pid="$app_pid" || true
    else
        note "could not resolve the pid; streaming the whole logcat instead"
        "$ADB" -s "$SERIAL" logcat || true
    fi
fi

# Nothing is printed here: cleanup() runs on EXIT and is the single place the
# "left running" note is emitted, for the emulator and the scrcpy window alike.
exit 0

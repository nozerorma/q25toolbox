#!/system/bin/sh
# Hides the mount points the JL10 (John Launcher) QuickStep module leaves in the mount table.
#
# The module makes JL10 a privileged app by bind-mounting a copy of three stock folders over
# themselves: /product/overlay, /system/etc/permissions and /system/priv-app. Apps can read those
# mounts from /proc/self/mountinfo. Same fix as the Vector dex2oat hide: a Hybrid Mount VFS rule per
# file JL10 added, then the bind mounts are lazily unmounted, leaving no mount records anywhere.
#
# Usage: jl10_hide.sh plan | apply | revert | status
# Exit:  0 ok / already done, 2 module or VFS missing, 3 nothing to hide, 4 a rule failed (mounts restored),
#        5 files not visible after unmounting (mounts restored)

MOD=/data/adb/modules/bb10launcher_quickstep
HM=/data/adb/modules/hybrid_mount/hybrid-mount
DIR=/data/adb/q25_hotfixes
STATE=$DIR/jl10_mounts   # "source|mountpoint", one per mount we took down (needed to put them back)
RULES=$DIR/jl10_rules    # "virtual|real", one per file we redirected

# Everything is checked from init's mount namespace (what system_server and zygote start from).
command -v nsenter >/dev/null 2>&1 && NS="nsenter -t 1 -m --" || NS=""
in_ns() { if [ -n "$NS" ]; then $NS "$@"; else "$@"; fi; }

# Mounts of the module's folders, as "source|mountpoint". mountinfo's root (field 4) is relative to
# the /data filesystem.
exposed() { awk '$4 ~ /bb10launcher_quickstep/ {print "/data" $4 "|" $5}' /proc/self/mountinfo | sort -u; }

# Rules for what the module added to each mounted folder: its top-level entries named bb10/jl10
# (the launcher's folder, its overlay folder, its privapp-permissions file), one rule per file. The
# VFS creates the missing parent folders itself.
build_rules() {
  echo "$1" | while IFS='|' read -r src mp; do
    [ -d "$src" ] || continue
    ls -1 "$src" | grep -i -e bb10 -e jl10 | while read -r name; do
      find "$src/$name" -type f | while read -r f; do
        echo "$mp/${f#$src/}|$f"
      done
    done
  done
}

drop_rules() {
  [ -s "$RULES" ] || return 0
  while IFS='|' read -r v f; do "$HM" vfs rule del "$v" >/dev/null 2>&1; done < "$RULES"
}

rebind() {
  [ -s "$STATE" ] || return 0
  while IFS='|' read -r src mp; do in_ns mount -o bind "$src" "$mp" 2>/dev/null; done < "$STATE"
}

plan() {
  mounts=$(exposed)
  echo "mounts:"; echo "$mounts"
  echo "rules:"; build_rules "$mounts"
}

apply() {
  [ -d "$MOD" ] && [ -x "$HM" ] || { echo "JL10 module or Hybrid Mount missing"; return 2; }
  "$HM" vfs load >/dev/null 2>&1
  mounts=$(exposed)
  if [ -z "$mounts" ]; then
    [ -s "$RULES" ] && { echo "already hidden"; return 0; }
    echo "nothing mounted"; return 3
  fi
  rules=$(build_rules "$mounts")
  [ -n "$rules" ] || { echo "no JL10 files found in the mounted folders"; return 3; }
  mkdir -p "$DIR"
  printf '%s\n' "$mounts" > "$STATE"
  printf '%s\n' "$rules" > "$RULES"

  # 1. Take the bind mounts down, here and as init sees them. This has to come BEFORE the rules: a rule
  #    added while a bind mount still covers its path is accepted but never takes effect (verified on
  #    device), so adding first and unmounting after leaves the files invisible.
  while IFS='|' read -r src mp; do
    umount -l "$mp" 2>/dev/null
    in_ns umount -l "$mp" 2>/dev/null
  done < "$STATE"

  # 2. Redirect every file. Any failure: put the mounts back and take back what was added.
  ok=1
  while IFS='|' read -r v f; do
    out=$("$HM" vfs rule add "$v" "$f" 2>&1)
    case "$out" in *verified*) ;; *) ok=0; echo "rule failed: $v" ;; esac
  done < "$RULES"
  [ "$ok" = 1 ] || { drop_rules; rebind; rm -f "$STATE" "$RULES"; return 4; }

  # 3. Every redirected file must be readable from init's namespace and no mount may remain.
  #    If not, put the module's mounts back exactly as they were.
  bad=0
  while IFS='|' read -r v f; do
    in_ns test -s "$v" || { bad=1; echo "not visible: $v"; }
  done < "$RULES"
  [ -z "$(exposed)" ] || { bad=1; echo "a mount is still there"; }
  if [ "$bad" = 1 ]; then drop_rules; rebind; rm -f "$STATE" "$RULES"; return 5; fi
  echo "hidden"
  return 0
}

revert() {
  [ -s "$STATE" ] || { echo "nothing to restore"; return 0; }
  drop_rules # while the path is uncovered, as when it was added
  rebind
  rm -f "$STATE" "$RULES"
  echo "restored"
}

status() {
  echo "exposed=$(exposed | grep -c .)"
  echo "rules=$(grep -c . "$RULES" 2>/dev/null)"
}

case "$1" in
  plan) plan ;;
  apply) apply ;;
  revert) revert ;;
  status) status ;;
  *) echo "usage: $0 plan|apply|revert|status"; exit 1 ;;
esac

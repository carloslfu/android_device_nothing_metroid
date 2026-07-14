#!/usr/bin/env bash
# Populate vendor_dlkm + system_dlkm (LOS builds them EMPTY) with the 339 stock prebuilt modules,
# stage B4.1 pvmfw, and rebuild coherent super + child vbmeta images. Host-side only.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOP="${ANDROID_BUILD_TOP:-$(cd "$SCRIPT_DIR/../../../.." && pwd)}"
OUT="${OUT:-$TOP/out/target/product/metroid}"
TOOLS="${ANDROID_HOST_OUT:-$TOP/out/host/linux-x86}/bin"
MK="${METROID_KERNEL_PREBUILTS:-$TOP/device/nothing/metroid-kernel}"
AVB=$TOOLS/avbtool
VDLKM=$MK/vendor_dlkm.img      # populated 57.8MB (339 .ko)
SDLKM=$MK/system_dlkm.img      # populated 7.6MB
PVMFW=$MK/pvmfw.img            # exact B4.1 protected-VM firmware
BOOT=$MK/boot.img              # exact B4.1 GKI container; boot ramdisk is empty
DTBO=$MK/dtbo.img              # exact B4.1 device-tree overlays
INIT_BOOT=$MK/init_boot.img    # exact B4.1 generic ramdisk
VENDOR_BOOT=$MK/vendor_boot.img # exact B4.1 vendor ramdisk + first-stage fstab
VDLKM_OUT=$OUT/vendor_dlkm.img
SDLKM_OUT=$OUT/system_dlkm.img
PVMFW_OUT=$OUT/pvmfw.img
DTBO_OUT=$OUT/dtbo.img
INIT_BOOT_OUT=$OUT/init_boot.img
VENDOR_BOOT_OUT=$OUT/vendor_boot.img
STOCK_VBMETA_SYSTEM=${METROID_STOCK_VBMETA_SYSTEM:-$MK/vbmeta_system.stock.img}
STOCK_VBMETA_VENDOR=${METROID_STOCK_VBMETA_VENDOR:-$MK/vbmeta_vendor.stock.img}
KEY=$TOP/external/avb/test/data/testkey_rsa2048.pem
WORK=$(mktemp -d "${TMPDIR:-/tmp}/metroid-dlkmfix.XXXXXX")
trap 'rm -rf "$WORK"' EXIT
PUBKEY=$WORK/testkey_rsa2048.avbpubkey

require_sha256() {
  file=$1
  expected=$2
  actual=$(sha256sum "$file" | awk '{print $1}')
  if [ "$actual" != "$expected" ]; then
    echo "!! B4.1 hash mismatch: $file" >&2
    echo "   expected $expected" >&2
    echo "   actual   $actual" >&2
    exit 1
  fi
}

cd "$TOP"
echo "=== [1/5] sanity: inputs exist ==="
for f in "$AVB" "$TOOLS/build_super_image" "$TOOLS/unpack_bootimg" "$KEY" "$STOCK_VBMETA_SYSTEM" "$STOCK_VBMETA_VENDOR" "$OUT/system.img" "$OUT/vendor.img" "$OUT/product.img" "$OUT/system_ext.img" "$OUT/odm.img" "$OUT/vbmeta.img" "$OUT/vbmeta_system.img" "$OUT/boot.img" "$OUT/recovery.img" "$BOOT" "$DTBO" "$INIT_BOOT" "$VENDOR_BOOT" "$VDLKM" "$SDLKM" "$PVMFW"; do
  [ -f "$f" ] || { echo "!! MISSING: $f"; exit 1; }
  printf "  ok  %-10s  %s\n" "$(numfmt --to=iec "$(stat -c%s "$f")")" "$f"
done
printf '  vendor_dlkm sha256: '
sha256sum "$VDLKM" | cut -d' ' -f1
printf '  system_dlkm sha256: '
sha256sum "$SDLKM" | cut -d' ' -f1
printf '  pvmfw sha256: '
sha256sum "$PVMFW" | cut -d' ' -f1
require_sha256 "$VDLKM" 7324ed035103264933f1315bb3bf8db7724cd38c11709ef834dc5e6d371cf5ec
require_sha256 "$SDLKM" 5422a1b8369121b1153d44c3dbabc61a1b60e3fe3c52a9fb023787397793c5ea
require_sha256 "$PVMFW" 94d31f6d056be08ba5da59da6c15ec9a2e569e66c7f21d2b7c6590f3d690d095
require_sha256 "$BOOT" 02e1e78b12f40e734a26516da8db0953c5db567869e83a46f8589a48fc3b1689
require_sha256 "$DTBO" 5ab869ef8b202c5881378851d768793c613cfe7c422a0fd81d36a04a92c09584
require_sha256 "$INIT_BOOT" 940a06b1b6be16e27f0be891f6f872fe4b748e577cd1ea6ca7ee2e4fdaf7ba55
require_sha256 "$VENDOR_BOOT" 660cefc2a32d6220c5f9393d0fd2748087ba7651277d236c2417042d2d1ebad0
require_sha256 "$STOCK_VBMETA_SYSTEM" 6a69203f0bfc9119bb95fc49b424ed8334783e9625b90d69b5c3f57397bfc1ef
require_sha256 "$STOCK_VBMETA_VENDOR" 85b2a234a8742606a6c30cdd71b0b98e1562b2e3fc58c114bc69f1a898221d6b
BUILT_SYSTEM_ROLLBACK=$("$AVB" info_image --image "$OUT/vbmeta_system.img" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
STOCK_SYSTEM_ROLLBACK=$("$AVB" info_image --image "$STOCK_VBMETA_SYSTEM" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
VENDOR_ROLLBACK=$("$AVB" info_image --image "$STOCK_VBMETA_VENDOR" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
TOP_ROLLBACK=$("$AVB" info_image --image "$OUT/vbmeta.img" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
BUILT_BOOT_ROLLBACK=$("$AVB" info_image --image "$OUT/boot.img" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
STOCK_BOOT_ROLLBACK=$("$AVB" info_image --image "$BOOT" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
if [ -z "$BUILT_SYSTEM_ROLLBACK" ] || [ -z "$STOCK_SYSTEM_ROLLBACK" ] || [ -z "$VENDOR_ROLLBACK" ] || [ -z "$TOP_ROLLBACK" ] || [ -z "$BUILT_BOOT_ROLLBACK" ] || [ -z "$STOCK_BOOT_ROLLBACK" ]; then
  echo "!! Could not read the built/stock AVB rollback indexes." >&2
  exit 1
fi
if [ "$BUILT_BOOT_ROLLBACK" -lt "$STOCK_BOOT_ROLLBACK" ]; then
  echo "!! boot rollback index is below the B4.1 input." >&2
  echo "   built $BUILT_BOOT_ROLLBACK" >&2
  echo "   stock $STOCK_BOOT_ROLLBACK" >&2
  exit 1
fi
SYSTEM_ROLLBACK=$STOCK_SYSTEM_ROLLBACK
if [ "$BUILT_SYSTEM_ROLLBACK" -gt "$STOCK_SYSTEM_ROLLBACK" ]; then
  SYSTEM_ROLLBACK=$BUILT_SYSTEM_ROLLBACK
fi
echo "  vbmeta_system rollback index: $SYSTEM_ROLLBACK"
echo "  vbmeta_vendor rollback index: $VENDOR_ROLLBACK"
echo "  top-level vbmeta rollback index: $TOP_ROLLBACK"
echo "  boot rollback index: $BUILT_BOOT_ROLLBACK (B4.1 floor $STOCK_BOOT_ROLLBACK)"
if ! "$AVB" info_image --image "$OUT/vbmeta.img" | grep -A4 'Chain Partition descriptor:' | grep -q 'Partition Name:.*vbmeta_vendor'; then
  echo "!! Top-level vbmeta does not chain vbmeta_vendor." >&2
  exit 1
fi
echo "  top-level vbmeta chains vbmeta_vendor"
"$AVB" extract_public_key --key "$KEY" --output "$PUBKEY"
mkdir "$WORK/stock-boot" "$WORK/built-boot"
"$TOOLS/unpack_bootimg" --boot_img "$BOOT" --out "$WORK/stock-boot" >/dev/null
"$TOOLS/unpack_bootimg" --boot_img "$OUT/boot.img" --out "$WORK/built-boot" >/dev/null
if [ -s "$WORK/stock-boot/ramdisk" ] || [ -s "$WORK/built-boot/ramdisk" ]; then
  echo "!! metroid boot.img must have an empty ramdisk; init_boot owns the generic ramdisk." >&2
  exit 1
fi
cmp "$WORK/stock-boot/kernel" "$WORK/built-boot/kernel"
echo "  boot kernel matches B4.1 and both boot ramdisks are empty"
echo "=== [2/5] stage B4.1 boot inputs + coherent vbmeta_system ==="
install -m 0644 "$DTBO" "$DTBO_OUT"
install -m 0644 "$INIT_BOOT" "$INIT_BOOT_OUT"
install -m 0644 "$VENDOR_BOOT" "$VENDOR_BOOT_OUT"
install -m 0644 "$PVMFW" "$PVMFW_OUT"
"$AVB" make_vbmeta_image --algorithm SHA256_RSA2048 --key "$KEY" --padding_size 4096 --rollback_index "$SYSTEM_ROLLBACK" \
  --include_descriptors_from_image "$OUT/system.img" \
  --include_descriptors_from_image "$OUT/system_ext.img" \
  --include_descriptors_from_image "$OUT/product.img" \
  --include_descriptors_from_image "$PVMFW_OUT" \
  --output "$OUT/vbmeta_system.img"
truncate -s 65536 "$OUT/vbmeta_system.img"
echo "  vbmeta_system.img = $(stat -c%s "$OUT/vbmeta_system.img") bytes"
if ! "$AVB" info_image --image "$OUT/vbmeta_system.img" | grep -q 'Partition Name:.*pvmfw'; then
  echo "!! vbmeta_system does not contain the B4.1 pvmfw descriptor." >&2
  exit 1
fi
echo "=== [3/5] coherent vbmeta_vendor (LOS vendor + populated dlkm + odm) ==="
install -m 0644 "$VDLKM" "$VDLKM_OUT"
install -m 0644 "$SDLKM" "$SDLKM_OUT"
"$AVB" make_vbmeta_image --algorithm SHA256_RSA2048 --key "$KEY" --padding_size 4096 --rollback_index "$VENDOR_ROLLBACK" \
  --include_descriptors_from_image "$OUT/vendor.img" \
  --include_descriptors_from_image "$VDLKM_OUT" \
  --include_descriptors_from_image "$SDLKM_OUT" \
  --include_descriptors_from_image "$OUT/odm.img" \
  --output "$OUT/vbmeta_vendor.img"
truncate -s 65536 "$OUT/vbmeta_vendor.img"
echo "  vbmeta_vendor.img = $(stat -c%s "$OUT/vbmeta_vendor.img") bytes"
"$AVB" info_image --image "$OUT/vbmeta_vendor.img" 2>&1 | grep -iE "Partition Name|Rollback" | head
echo "=== [4/5] coherent root vbmeta (B4.1 boot inputs + both child chains) ==="
"$AVB" make_vbmeta_image --algorithm SHA256_RSA2048 --key "$KEY" --padding_size 4096 --rollback_index "$TOP_ROLLBACK" \
  --chain_partition "boot:3:$PUBKEY" \
  --chain_partition "recovery:1:$PUBKEY" \
  --chain_partition "vbmeta_system:2:$PUBKEY" \
  --chain_partition "vbmeta_vendor:4:$PUBKEY" \
  --include_descriptors_from_image "$DTBO_OUT" \
  --include_descriptors_from_image "$INIT_BOOT_OUT" \
  --include_descriptors_from_image "$VENDOR_BOOT_OUT" \
  --output "$OUT/vbmeta.img"
truncate -s 65536 "$OUT/vbmeta.img"
echo "=== [5/5] build super_dlkmfix.img (LOS + populated dlkm) ==="
MI=$OUT/obj/PACKAGING/super_dlkmfix_intermediates; mkdir -p "$MI"; INFO=$MI/misc_info.txt
cat > "$INFO" <<EOF
use_dynamic_partitions=true
lpmake=lpmake
build_super_partition=true
super_metadata_device=super
super_block_devices=super
super_super_device_size=9126805504
dynamic_partition_list=odm product system system_dlkm system_ext vendor vendor_dlkm
super_partition_groups=qualcomm_dynamic_partitions
super_qualcomm_dynamic_partitions_group_size=9122611200
super_qualcomm_dynamic_partitions_partition_list=system_ext system vendor vendor_dlkm system_dlkm odm product
super_partition_size=9126805504
virtual_ab=true
virtual_ab_cow_version=3
ab_update=true
system_ext_image=$OUT/system_ext.img
system_image=$OUT/system.img
vendor_image=$OUT/vendor.img
vendor_dlkm_image=$VDLKM_OUT
system_dlkm_image=$SDLKM_OUT
odm_image=$OUT/odm.img
product_image=$OUT/product.img
EOF
PATH="$TOOLS:$PATH" "$TOOLS/build_super_image" -v "$INFO" "$OUT/super_dlkmfix.img"
echo "  super_dlkmfix.img = $(numfmt --to=iec "$(stat -c%s "$OUT/super_dlkmfix.img")")"
"$AVB" verify_image --image "$OUT/boot.img" --key "$KEY"
"$AVB" verify_image --image "$OUT/recovery.img" --key "$KEY"
"$AVB" verify_image --image "$OUT/vbmeta_vendor.img" --key "$KEY"
"$AVB" verify_image --image "$OUT/vbmeta_system.img" --key "$KEY"
"$AVB" verify_image \
  --image "$OUT/vbmeta.img" \
  --key "$KEY" \
  --expected_chain_partition "boot:3:$PUBKEY" \
  --expected_chain_partition "recovery:1:$PUBKEY" \
  --expected_chain_partition "vbmeta_system:2:$PUBKEY" \
  --expected_chain_partition "vbmeta_vendor:4:$PUBKEY"
echo "=== BUILD DONE — ready for the phone.md freeze guard ==="

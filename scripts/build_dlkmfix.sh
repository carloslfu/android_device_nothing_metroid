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
VDLKM_OUT=$OUT/vendor_dlkm.img
SDLKM_OUT=$OUT/system_dlkm.img
PVMFW_OUT=$OUT/pvmfw.img
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
echo "=== [1/4] sanity: inputs exist ==="
for f in "$AVB" "$TOOLS/build_super_image" "$KEY" "$STOCK_VBMETA_SYSTEM" "$STOCK_VBMETA_VENDOR" "$OUT/system.img" "$OUT/vendor.img" "$OUT/product.img" "$OUT/system_ext.img" "$OUT/odm.img" "$OUT/vbmeta.img" "$OUT/vbmeta_system.img" "$OUT/boot.img" "$OUT/recovery.img" "$OUT/init_boot.img" "$OUT/vendor_boot.img" "$OUT/dtbo.img" "$VDLKM" "$SDLKM" "$PVMFW"; do
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
require_sha256 "$STOCK_VBMETA_SYSTEM" 6a69203f0bfc9119bb95fc49b424ed8334783e9625b90d69b5c3f57397bfc1ef
require_sha256 "$STOCK_VBMETA_VENDOR" 85b2a234a8742606a6c30cdd71b0b98e1562b2e3fc58c114bc69f1a898221d6b
BUILT_SYSTEM_ROLLBACK=$("$AVB" info_image --image "$OUT/vbmeta_system.img" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
STOCK_SYSTEM_ROLLBACK=$("$AVB" info_image --image "$STOCK_VBMETA_SYSTEM" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
VENDOR_ROLLBACK=$("$AVB" info_image --image "$STOCK_VBMETA_VENDOR" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
if [ -z "$BUILT_SYSTEM_ROLLBACK" ] || [ -z "$STOCK_SYSTEM_ROLLBACK" ] || [ -z "$VENDOR_ROLLBACK" ]; then
  echo "!! Could not read the built/stock vbmeta rollback indexes." >&2
  exit 1
fi
SYSTEM_ROLLBACK=$STOCK_SYSTEM_ROLLBACK
if [ "$BUILT_SYSTEM_ROLLBACK" -gt "$STOCK_SYSTEM_ROLLBACK" ]; then
  SYSTEM_ROLLBACK=$BUILT_SYSTEM_ROLLBACK
fi
echo "  vbmeta_system rollback index: $SYSTEM_ROLLBACK"
echo "  vbmeta_vendor rollback index: $VENDOR_ROLLBACK"
if ! "$AVB" info_image --image "$OUT/vbmeta.img" | grep -A4 'Chain Partition descriptor:' | grep -q 'Partition Name:.*vbmeta_vendor'; then
  echo "!! Top-level vbmeta does not chain vbmeta_vendor." >&2
  exit 1
fi
echo "  top-level vbmeta chains vbmeta_vendor"
echo "=== [2/4] coherent vbmeta_system (LOS system + B4.1 pvmfw) ==="
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
echo "=== [3/4] coherent vbmeta_vendor (LOS vendor + populated dlkm + odm) ==="
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
echo "=== [4/4] build super_dlkmfix.img (LOS + populated dlkm) ==="
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
"$AVB" extract_public_key --key "$KEY" --output "$PUBKEY"
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

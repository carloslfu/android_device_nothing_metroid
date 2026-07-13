#!/usr/bin/env bash
# Populate vendor_dlkm + system_dlkm (LOS builds them EMPTY) with the 339 stock prebuilt modules,
# rebuild a coherent super + vbmeta_vendor. Host-side only (no device flash here).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOP="${ANDROID_BUILD_TOP:-$(cd "$SCRIPT_DIR/../../../.." && pwd)}"
OUT="${OUT:-$TOP/out/target/product/metroid}"
TOOLS="${ANDROID_HOST_OUT:-$TOP/out/host/linux-x86}/bin"
MK="${METROID_KERNEL_PREBUILTS:-$TOP/device/nothing/metroid-kernel}"
AVB=$TOOLS/avbtool
VDLKM=$MK/vendor_dlkm.img      # populated 57.8MB (339 .ko)
SDLKM=$MK/system_dlkm.img      # populated 7.6MB
VDLKM_OUT=$OUT/vendor_dlkm.img
SDLKM_OUT=$OUT/system_dlkm.img
STOCK_VBMETA_VENDOR=${METROID_STOCK_VBMETA_VENDOR:-$MK/vbmeta_vendor.stock.img}
KEY=$TOP/external/avb/test/data/testkey_rsa2048.pem
cd "$TOP"
echo "=== [1/3] sanity: inputs exist ==="
for f in "$AVB" "$TOOLS/build_super_image" "$KEY" "$STOCK_VBMETA_VENDOR" "$OUT/system.img" "$OUT/vendor.img" "$OUT/product.img" "$OUT/system_ext.img" "$OUT/odm.img" "$OUT/vbmeta.img" "$OUT/vbmeta_system.img" "$OUT/boot.img" "$OUT/recovery.img" "$OUT/init_boot.img" "$OUT/vendor_boot.img" "$OUT/dtbo.img" "$VDLKM" "$SDLKM"; do
  [ -f "$f" ] || { echo "!! MISSING: $f"; exit 1; }
  printf "  ok  %-10s  %s\n" "$(numfmt --to=iec "$(stat -c%s "$f")")" "$f"
done
printf '  vendor_dlkm sha256: '
sha256sum "$VDLKM" | cut -d' ' -f1
printf '  system_dlkm sha256: '
sha256sum "$SDLKM" | cut -d' ' -f1
ROLLBACK_INDEX=$("$AVB" info_image --image "$STOCK_VBMETA_VENDOR" | awk '$1 == "Rollback" && $2 == "Index:" { print $3; exit }')
if [ -z "$ROLLBACK_INDEX" ]; then
  echo "!! Could not read the stock vbmeta_vendor rollback index." >&2
  exit 1
fi
echo "  vbmeta_vendor rollback index: $ROLLBACK_INDEX"
if ! "$AVB" info_image --image "$OUT/vbmeta.img" | grep -A4 'Chain Partition descriptor:' | grep -q 'Partition Name:.*vbmeta_vendor'; then
  echo "!! Top-level vbmeta does not chain vbmeta_vendor." >&2
  exit 1
fi
echo "  top-level vbmeta chains vbmeta_vendor"
echo "=== [2/3] coherent vbmeta_vendor (LOS vendor + populated dlkm + odm) ==="
install -m 0644 "$VDLKM" "$VDLKM_OUT"
install -m 0644 "$SDLKM" "$SDLKM_OUT"
"$AVB" make_vbmeta_image --algorithm SHA256_RSA2048 --key "$KEY" --padding_size 4096 --rollback_index "$ROLLBACK_INDEX" \
  --include_descriptors_from_image "$OUT/vendor.img" \
  --include_descriptors_from_image "$VDLKM_OUT" \
  --include_descriptors_from_image "$SDLKM_OUT" \
  --include_descriptors_from_image "$OUT/odm.img" \
  --output "$OUT/vbmeta_vendor.img"
truncate -s 65536 "$OUT/vbmeta_vendor.img"
echo "  vbmeta_vendor.img = $(stat -c%s "$OUT/vbmeta_vendor.img") bytes"
"$AVB" info_image --image "$OUT/vbmeta_vendor.img" 2>&1 | grep -iE "Partition Name|Rollback" | head
echo "=== [3/3] build super_dlkmfix.img (LOS + populated dlkm) ==="
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
"$AVB" verify_image --image "$OUT/vbmeta_vendor.img" --key "$KEY"
echo "=== BUILD DONE — ready to flash super_dlkmfix.img + vbmeta_vendor.img ==="

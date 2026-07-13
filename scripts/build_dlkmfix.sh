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
KEY=$TOP/external/avb/test/data/testkey_rsa2048.pem
cd "$TOP"
echo "=== [1/3] sanity: inputs exist ==="
for f in "$AVB" "$TOOLS/build_super_image" "$KEY" "$OUT/system.img" "$OUT/vendor.img" "$OUT/product.img" "$OUT/system_ext.img" "$OUT/odm.img" "$VDLKM" "$SDLKM"; do
  [ -f "$f" ] || { echo "!! MISSING: $f"; exit 1; }
  printf "  ok  %-10s  %s\n" "$(numfmt --to=iec "$(stat -c%s "$f")")" "$f"
done
printf '  vendor_dlkm sha256: '
sha256sum "$VDLKM" | cut -d' ' -f1
printf '  system_dlkm sha256: '
sha256sum "$SDLKM" | cut -d' ' -f1
echo "=== [2/3] coherent vbmeta_vendor (LOS vendor + populated dlkm + odm) ==="
"$AVB" make_vbmeta_image --algorithm SHA256_RSA2048 --key "$KEY" --padding_size 4096 --rollback_index 1769904000 \
  --include_descriptors_from_image "$OUT/vendor.img" \
  --include_descriptors_from_image "$VDLKM" \
  --include_descriptors_from_image "$SDLKM" \
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
vendor_dlkm_image=$VDLKM
system_dlkm_image=$SDLKM
odm_image=$OUT/odm.img
product_image=$OUT/product.img
EOF
PATH="$TOOLS:$PATH" "$TOOLS/build_super_image" -v "$INFO" "$OUT/super_dlkmfix.img"
echo "  super_dlkmfix.img = $(numfmt --to=iec "$(stat -c%s "$OUT/super_dlkmfix.img")")"
"$AVB" verify_image --image "$OUT/vbmeta_vendor.img" --key "$KEY"
echo "=== BUILD DONE — ready to flash super_dlkmfix.img + vbmeta_vendor.img ==="

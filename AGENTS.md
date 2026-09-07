# AGENTS.md — READ THIS FIRST (metroid / Nothing Phone 3 / LineageOS 23)

**Current phone.md authority (September 7, 2026):** the tracked
`phone.md/AGENT-NATIVE-UX-PLAN.md` and `phone.md/build/metroid/README.md` own
current source, accepted ROM and release gates. The bring-up notes below are
historical constraints, not a current device-capability or acceptance report.
Use only the canonical guarded flash workflow when a physical run is authorized.

The additive `sdk_phone_md-bp2a-userdebug` target inherits Goldfish's `emu64a`
board and shares the phone.md launcher, locale controller, platform controller,
permission files and overlays from this tree. It never inherits metroid's
vendor/HAL/firmware product. Build it into `out-phone-md-emulator-api36`, separate
from physical output. Both SDK and physical Soong/Kati configuration checks
pass on September 7. Image compilation, API-36/native/host-mic and physical
acceptance stay separate and pending until recorded in phone.md.

The first API-36 emulator image compiled but failed its September 7 boot:
DisplayPolicy queried LineageSettings without its core provider installed.
The SDK target must include LineageSettingsProvider as well as the SDK library;
the provider module brings its own system_ext privileged-permission whitelist.
A successful build graph does not prove runtime package closure or boot.
The second image reached Android boot-complete but SystemUI crash-looped:
the SDK product lacked LINEAGE_BUILD, so the core build omitted the standard
Lineage SELinux service contexts. Keep LINEAGE_BUILD non-empty for this product
and retain enforcing policy; a boot property alone is not runtime acceptance.

**STATUS: THE DEVICE BOOTS.** LineageOS 23 reaches `sys.boot_completed=1`, the
real launcher (QuickstepLauncher), with **touch working**. This is a hard-won
known-good state. Git tag: **`known-good-boot-20260708`** (this repo).

> If you break the boot, `git checkout known-good-boot-20260708` here, restore
> the vendor tree, rebuild, reflash. See "RESTORE" below.

---

## ⛔ DO NOT DO THESE — they each break the boot (learned the hard way)

1. **Do NOT declare unserved HALs in VINTF.** `PRODUCT_ENFORCE_VINTF_MANIFEST := true`
   is on. If you add RIL / camera / NFC / secure_element / etc. to
   `DEVICE_MANIFEST_FILE` (e.g. via `configs/hidl/manifest_qti_hals.xml`) but those
   HALs don't actually run, **system_server blocks forever waiting for them → boot
   fails / reboots to recovery.** Keep `DEVICE_MANIFEST_FILE` MINIMAL:
   ```
   DEVICE_MANIFEST_FILE := device/nothing/metroid/configs/hidl/manifest.xml \
       hardware/qcom-caf/sm8750/audio/primary-hal/hal/core/manifest_audiocoreservices_qti.xml
   ```
   Only add a HAL to VINTF once its service is actually serving.

2. **Do NOT touch `rootdir/etc/init.target.rc`** except with extreme care. The
   INSTALLED init.target.rc must be the **232-line** version (contains the
   `OPUS_NTLOG_KEEP` marker, does **NOT** contain any `OPUS-USB-BRINGUP` block).
   - The `OPUS-USB-BRINGUP` block writes `a600000.dwc3/mode peripheral` directly —
     this **stomps early-adb's dwc3 dance** and you lose adb during boot.
   - A bloated init.target.rc also pulled in `critical` services that fatal ~78s →
     `init_fatal_reboot_target=recovery` → recovery loop.
   - **Landmine:** there are DUP install rules for init.target.rc (device rootdir
     AND vendor proprietary) and the build **staging goes stale**. If you change it,
     force-clean: `rm $OUT/vendor/etc/init/hw/init.target.rc` and the matching
     `out/soong/.intermediates/**/init.target.rc`, then rebuild vendorimage.

3. **USB Gadget HAL and early-adb are integrated in a hybrid config.**
   The QTI USB gadget HAL (`vendor.usbgadget-hal`) is enabled to allow dynamic
   USB composition switching (MTP, PTP, MIDI, tethering) via the LineageOS UI.
   To avoid conflicts and retain early boot observability, `init.qcom.usb.rc`
   sets up a hybrid configuration: it initializes and mounts all configfs
   functions (MTP, PTP, adb, etc.) early under `on fs` and triggers static
   early-adb at boot, before handing over to the dynamic USB gadget HAL
   (triggered by `sys.usb.configfs=2` in late boot).

4. **Do NOT revert the two owned `frameworks/base` fixes** pinned by phone.md's manifest.
   They are required to reach boot_completed:
   - SoundTrigger `ExternalCaptureStateTracker` LOG_ALWAYS_FATAL → non-fatal.
   - `UsbGadgetAidl.isServicePresent` isDeclared → checkService (non-blocking).

5. **Do NOT change `ro.hw_timeout_multiplier=4`** (in `device.mk`
   `PRODUCT_SYSTEM_PROPERTIES`). It must be in **/system/build.prop** (not vendor) —
   it gives the 240s Watchdog needed to survive the slow imageless first boot.

6. **`boot.img` must keep the B4.1 zero-ramdisk layout.** This device loads the
   generic ramdisk from `init_boot`. Declaring only `BOARD_PREBUILT_INIT_BOOT_IMAGE`
   makes AOSP treat init_boot as not being built and silently puts a second generic
   ramdisk in the generated boot image. The result passes AVB checks but fails before
   early adb. Keep `BOARD_PREBUILT_BOOTIMAGE` pointed at the exact B4.1 container;
   AOSP re-signs it with the development key. `build_dlkmfix.sh` unpacks both copies
   and rejects a non-empty boot ramdisk or kernel mismatch.

---

## RESTORE (if boot breaks)
```
cd "$ANDROID_BUILD_TOP"
# Re-sync the device/framework/vendor commits pinned by phone.md's
# build/manifest/phone_md_lineage.xml, then rebuild the full target.
device/nothing/metroid/scripts/build_los23.sh
device/nothing/metroid/scripts/build_dlkmfix.sh
```

## BUILD / FLASH
- Product-config changes need `scripts/build_los23.sh {systemimage|vendorimage}` (Kati regen), not bare Ninja.
- Framework `.java/.cpp` → `systemimage`. init rc / VINTF / PRODUCT_PACKAGES → `vendorimage`.
- Repack super + coherent vbmeta_vendor: `scripts/build_dlkmfix.sh`.
- Flash from the attached Mac with the guarded `phone.md/scripts/flash-metroid-full-rom.sh`. The old hardcoded server-side flash/capture scripts have been removed.
- Bounce Android/recovery → fastboot with `adb reboot bootloader` (no button-holds).
- LANDMINES: slot **a** only; `fastboot set_active a` + `fastboot erase misc` before every flash;
  NEVER `--flags 3` on the ROOT vbmeta; vendor must be ext4; vendor_boot page_size 0x1000;
  `fastboot boot` hangs.

## OBSERVABILITY (use it — do not guess)
Early-adb gives `adb` ~30s into boot, before failures. `adb logcat -b all`, `dmesg`,
tombstones (F DEBUG in logcat), `getprop sys.system_server.start_count` (climbing =
crash loop). Classify: Watchdog kill vs native SIGABRT vs Java FATAL are 3 different bugs.

## HOW IT BOOTS (full write-up: BRINGUP_GUIDE.md)
Module-set (339 .ko into empty vendor_dlkm) → sepolicy wired → early-adb →
hw_timeout_multiplier=4 → audio core HAL packaged → 2 framework patches.

## POST-BOOT CHECKS (verify on the pinned phone.md build)

The upstream notes and README disagree about several services. Treat the
following as unproved until the pinned phone.md image supplies its own logs:

- **Audio**: no ALSA sound card (`/proc/asound/cards` empty). ADSP audio-DSP path down:
  `vendor.adsprpcd` crash-loops exit 114 / `fastrpc_wait_for_secure_device: Poll timeout`.
  Fix ADSP/fastRPC → sound card registers → PAL/AGM/ACDB → real audio.
- **Faster boot**: currently imageless (odsign/keystore BootLevel key fails). Either fix the
  keystore boot-level key, or dexpreopt the boot image into /system
  (`WITH_DEXPREOPT := true` + `PRODUCT_USES_DEFAULT_ART_CONFIG` + `DEX_PREOPT_WITH_UPDATABLE_BCP`).
- **RIL + camera**: `CANNOT LINK` on `android.hardware.radio-V3-ndk.so` /
  `android.frameworks.cameraservice.common-V1-ndk.so` (libs present in /vendor/lib64 →
  linker-namespace / `ld.config.txt` issue). NOTE: fix the LINKING, and only THEN declare
  the HAL in VINTF (see landmine #1).
- **NFC / SE / fingerprint**: HALs down; not boot-blocking.

## GIT / PUSH
- Device tree remote: `carloslfu/android_device_nothing_metroid`, branch `phone-md-23.0`. The imported upstream commit stays recorded in the phone.md manifest.
- Vendor tree (`android_vendor_nothing_metroid`) holds **proprietary blobs** — do NOT push to a
  public repo (DMCA). The important vendor *config* (early-adb, nt_kmsg, gadget-disabled) is
  captured as patches in `patches/vendor_nothing_metroid/`.

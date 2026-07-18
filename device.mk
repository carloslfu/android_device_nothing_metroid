LOCAL_PATH := device/nothing/metroid

# A/B
$(call inherit-product, $(SRC_TARGET_DIR)/product/virtual_ab_ota.mk)

# Generic ramdisk - build init_boot from source (userdebug/permissive), per onyx
$(call inherit-product, $(SRC_TARGET_DIR)/product/generic_ramdisk.mk)

# Qualcomm remote-file-system links. The modem TFTP service resolves MPSS
# read/write state and firmware through /vendor/rfs; without these installed
# symlinks DMS fails RF software initialization before Android can power radio.
$(call inherit-product, hardware/qcom-caf/common/common.mk)

# Dalvik heap must land in /system/build.prop: on this device /vendor/build.prop is not loaded
# before zygote, so vendor-hosted dalvik.vm.heapsize never reaches app_process and zygote runs on
# the AndroidRuntime -Xmx16m default -> OutOfMemoryError at class preload. PRODUCT_SYSTEM_PROPERTIES
# targets the system partition only; PRODUCT_PROPERTY_OVERRIDES also feeds vendor/build.prop and the
# prop gets claimed by vendor (stripped from system).
PRODUCT_SYSTEM_PROPERTIES += \
    dalvik.vm.heapstartsize=16m \
    dalvik.vm.heapgrowthlimit=256m \
    dalvik.vm.heapsize=512m \
    dalvik.vm.heaptargetutilization=0.5 \
    dalvik.vm.heapminfree=8m \
    dalvik.vm.heapmaxfree=32m

# This is a dedicated development device. Lineage keeps ro.debuggable=0 on
# userdebug builds, so secure ADB must be selected explicitly instead of
# depending on the insecure-adb post-processing side effect. Authentication
# remains enabled by vendor/lineage through ro.adb.secure=1.
PRODUCT_SYSTEM_DEFAULT_PROPERTIES += \
    persist.sys.usb.config=adb

# Boot control
PRODUCT_PACKAGES += \
    update_engine \
    update_engine_sideload \
    update_verifier

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_system=true \
    POSTINSTALL_PATH_system=system/bin/otapreopt_script \
    FILESYSTEM_TYPE_system=erofs \
    POSTINSTALL_OPTIONAL_system=true

AB_OTA_POSTINSTALL_CONFIG += \
    RUN_POSTINSTALL_vendor=true \
    POSTINSTALL_PATH_vendor=bin/checkpoint_gc \
    FILESYSTEM_TYPE_vendor=erofs \
    POSTINSTALL_OPTIONAL_vendor=true

PRODUCT_PACKAGES += \
    checkpoint_gc \
    otapreopt_script



# fastbootd
PRODUCT_PACKAGES += \
    android.hardware.fastboot@1.1-impl-mock \
    fastbootd

# Health (AIDL — HIDL @2.1 is deprecated at FCM 202404 and fails VINTF)
PRODUCT_PACKAGES += \
    android.hardware.health-service.qti \
    android.hardware.health-service.qti_recovery

# Partitions
PRODUCT_USE_DYNAMIC_PARTITIONS := true
PRODUCT_BUILD_VENDOR_BOOT_IMAGE := false

# Soong namespaces
PRODUCT_SOONG_NAMESPACES += \
    $(LOCAL_PATH)

# Recovery init scripts
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/etc/init.recovery.qcom.rc:recovery/root/init.recovery.qcom.rc

# === b19: QCOM HAL services whose .rc is shipped as a Nothing blob but whose BINARY is
# NOT in proprietary-files.txt (init had a dangling .rc -> service never started).
# Build these from the same QCOM/AOSP sources onyx (same SoC, sun/sm8750) builds.
# (Verified absent in proprietary-files.txt: bin/hw/{thermal,usb,usb.gadget,vibrator,wifi}-service, bin/vndservicemanager.)

# Thermal HAL (hardware/qcom-caf/thermal is its own soong namespace)
PRODUCT_SOONG_NAMESPACES +=     hardware/qcom-caf/thermal

# Display HALs (b20): build from source (hardware/qcom-caf/sm8750/display is its own soong namespace)
PRODUCT_SOONG_NAMESPACES += hardware/qcom-caf/sm8750/display

PRODUCT_PACKAGES +=     android.hardware.thermal-service.qti

# USB + USB gadget HAL (vendor/qcom/opensource/usb/hal, default namespace)
PRODUCT_PACKAGES +=     android.hardware.usb-service.qti     android.hardware.usb.gadget-service.qti

# Vibrator HAL (vendor/qcom/opensource/vibrator/aidl, default namespace)
PRODUCT_PACKAGES +=     vendor.qti.hardware.vibrator.service

# Sensors use Nothing's B4.1 AIDL V2 multihal binary and stock sub-HAL set.
# The Android 16 source service implements a different AIDL revision.
PRODUCT_PACKAGES +=     android.hardware.sensors-service.multihal.metroid

# Vendor servicemanager (frameworks/native) - vndservice_contexts users need it
PRODUCT_PACKAGES +=     vndservicemanager

# WiFi supplicant (external/wpa_supplicant_8). The wifi HAL service is the stock
# `android.hardware.wifi-service.metroid` prebuilt: the AOSP generic service has no vendor
# impl here ("failed to open /vendor/etc/wifi/vendor_hals") -> IWifi start fails code 9.
# Stock service + libwifi-hal{,-qcom,-ctrl} + wcn7750 WCNSS_qcom_cfg.ini (icnss2 probe needs
# it or wlan0 never appears). Verified live on device 2026-07-09 (enable + multi-band scan).
# libxml2.vendor: was only installed as a dep of the removed AOSP wifi-service; qseecomd
# (libdrmfs.so) hard-requires it -> without it TEE dies -> keymint -> gatekeeper -> system_server
# crash-loop (BiometricService "Gatekeeper service not available"). Found 2026-07-09.
PRODUCT_PACKAGES +=     libxml2.vendor
PRODUCT_PACKAGES +=     wpa_supplicant     wpa_cli \
                        android.hardware.wifi.hostapd-V2-ndk \
                        android.hardware.wifi-service.metroid \
                        libwifi-hal-qcom.metroid \
                        libwifi-hal-ctrl.metroid \
                        firmware_wlanmdsp.otaupdate_symlink \
                        firmware_wlan_mac.bin_symlink \
                        firmware_WCNSS_qcom_cfg.ini_symlink
# Display HALs from source (b20): ~23s reset fix. Module names mirror onyx un_dt device.mk
# (same SoC sun/sm8750); composer-service added (essential, buildable in sm8750/display tree).
# Several stock camera/display blobs still link both allocator AIDL V1 and V2.
# V2 arrives through the display stack; keep the source-built V1 vendor variant
# explicit so a dependency hidden behind the narrow prebuilt ELF exception cannot
# disappear from a clean product build.
PRODUCT_PACKAGES +=     android.hardware.graphics.allocator-V1-ndk.vendor \
                        vendor.qti.hardware.display.composer-service     vendor.qti.hardware.display.allocator-service     vendor.qti.hardware.display.demura-service     vendor.qti.hardware.display.snapalloc-impl     android.hardware.graphics.mapper@4.0-impl-qti-display     android.hardware.graphics.composer3-V3-ndk.vendor     vendor.qti.hardware.display.composer3-V1-ndk.vendor     vendor.qti.hardware.display.config-V12-ndk.vendor     vendor.qti.hardware.display.aiqe-V2-ndk.vendor

# Boot control HAL from source (b20): mirror onyx device.mk
PRODUCT_PACKAGES +=     android.hardware.boot-service.qti     android.hardware.boot-service.qti.recovery \
                        libkeymaster_messages \
                        librmnetctl \
                        rmnetcli \
                        libcodec2_aidl \
                        android.hardware.radio.sim-V3-ndk \
                        android.hardware.bluetooth.finder-V1-ndk \
                        android.hardware.health-V1-ndk \
                        android.frameworks.cameraservice.common-V1-ndk \
                        android.hardware.sensors@2.1.vendor \
                        libcodec2_vndk \
                        android.hardware.radio-V2-ndk \
                        android.hardware.radio.data-V2-ndk \
                        android.hardware.radio.network-V2-ndk \
                        android.hardware.radio@1.2.vendor \
                        android.hardware.radio@1.3.vendor \
                        android.hardware.radio@1.4.vendor \
                        android.media.audio.common.types-V2-cpp


# Inherit the proprietary vendor blobs (generated by setup-makefiles.sh)
$(call inherit-product-if-exists, vendor/nothing/metroid/metroid-vendor.mk)

# Load the stock TIPC kernel module before NICM starts. NICM gates the DSI
# ready callback used by both cellular-data RIL instances.
PRODUCT_COPY_FILES += $(LOCAL_PATH)/rootdir/etc/init.metroid.data.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/init.metroid.data.rc

# Bring-up build23: copy fstab.qcom to vendor partition and ramdisks (first-stage mount)
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR)/etc/fstab.qcom \
    $(LOCAL_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_RAMDISK)/fstab.qcom \
    $(LOCAL_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_RAMDISK)/first_stage_ramdisk/fstab.qcom \
    $(LOCAL_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/fstab.qcom \
    $(LOCAL_PATH)/rootdir/etc/fstab.qcom:$(TARGET_COPY_OUT_VENDOR_RAMDISK)/first_stage_ramdisk/fstab.qcom

# Audio policy configurations
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/configs/audio/audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_configuration.xml \
    $(LOCAL_PATH)/configs/audio/audio_module_config_primary.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_module_config_primary.xml \
    vendor/nothing/metroid/proprietary/vendor/etc/audio/sku_tuna/r_submix_audio_policy_configuration.xml:$(TARGET_COPY_OUT_VENDOR)/etc/r_submix_audio_policy_configuration.xml \
    vendor/nothing/metroid/proprietary/vendor/etc/audio/sku_tuna/audio_policy_volumes.xml:$(TARGET_COPY_OUT_VENDOR)/etc/audio_policy_volumes.xml \
    vendor/nothing/metroid/proprietary/vendor/etc/audio/sku_tuna/default_volume_tables.xml:$(TARGET_COPY_OUT_VENDOR)/etc/default_volume_tables.xml


# gralloc mapper VINTF fragment (standalone, matches stock layout — NOT inlined into
# manifest.xml, since AOSP libui's openDeclaredPassthroughHal only resolves standalone
# fragments under etc/vintf/manifest/, per PLAYBOOK_FROM_OPUS_20260706_MAPPER_VINTF_FIX.md).
# AOSP build rules forbid installing vintf/manifest/* via PRODUCT_COPY_FILES (build/make/core/
# Makefile hard-errors on it) — must go through the vintf_fragment soong module instead.
# mapper.qti.xml dropped 2026-07-10: qcom-caf gralloc source (mapper.qti) now provides the
# same fragment -> fsgen packaging conflict; the standalone-fragment fix was proven ineffective
# anyway (see metroid-mapper-vintf-fix-attempt-20260706).
PRODUCT_PACKAGES += hal_batch1.metroid.xml hal_batch2.metroid.xml hal_batch3.metroid.xml camera_provider.metroid.xml
# batch 4 (2026-07-10, live-verified): radio HAL declarations. The source
# ClearKey and QSPA services each own their fragment; metroid must not install
# second copies at those paths.
PRODUCT_PACKAGES += android.hardware.radio.config.metroid4.xml android.hardware.radio.data.metroid4.xml android.hardware.radio.messaging.metroid4.xml android.hardware.radio.modem.metroid4.xml android.hardware.radio.network.metroid4.xml android.hardware.radio.sim.metroid4.xml android.hardware.radio.voice.metroid4.xml

# Nothing's CNE binary serves these AIDL interfaces, while DPM consumes the
# MWQEM adapter. The stock declarations were missing from the imported tree,
# so both processes stayed alive but servicemanager rejected the contracts.
PRODUCT_PACKAGES += \
    vendor.qti.data.factoryservice.metroid.xml \
    vendor.qti.hardware.mwqemadapteraidlservice.metroid.xml \
    vendor.qti.memory.pasrmanager-service.metroid.xml \
    qms-saidl.metroid.xml \
    qti_radio_extensions.metroid.xml

# Own the complete IPACM runtime. Its source module is not pulled into the
# product merely because the proprietary diagnostic companion is present.
# IPACM also links these two source-built libraries at process start; keeping
# all three explicit prevents clean-build staging from hiding a missing vendor
# install and the resulting data-path restart loop.
PRODUCT_PACKAGES += \
    ipacm \
    IPACM_cfg.xml \
    IPACM_Filter_cfg.xml \
    libipanat \
    liboffloadhal

# Force copy missing proprietary files
$(call inherit-product-if-exists, device/nothing/metroid/proprietary_force_copy.mk)


# Force boot-time sepolicy recompile from CIL (stock prebuilt init does not honor the LOS odm precompiled)
PRODUCT_PRECOMPILED_SEPOLICY := false

# OPUS bring-up: EARLY (system build.prop, before zygote) props — /vendor/build.prop loads too late here.
# ro.hw_timeout_multiplier=4 -> framework Watchdog 60s*4=240s to survive the slow imageless first boot
# (odsign fails -> no boot.art -> imageless -> system_server main thread >60s -> Watchdog kill loop).
# boot_level_key.strategy: TEE keymint rejects EARLY_BOOT_ONLY (odsign Status(-8)); use MAX_USES_PER_BOOT.
# Match Nothing B4.1's shipping dual-SIM default. Mode 26 is
# NR_LTE_GSM_WCDMA; omitting it silently seeds new data as 2G/3G-only.
PRODUCT_SYSTEM_PROPERTIES += \
    ro.hw_timeout_multiplier=4 \
    ro.keystore.boot_level_key.strategy=TRUSTED_ENVIRONMENT:MAX_USES_PER_BOOT \
    ro.telephony.default_network=26,26

# These paths are mount targets for the firmware partitions. EROFS drops empty
# directories, so keep a harmless symlink in each path until init mounts over it.
# install_symlink is used because fsgen mistakes firmware_mnt for a subdirectory
# of its built-in firmware prebuilt type and rejects the resulting ../ path.
PRODUCT_PACKAGES += \
    firmware_mnt.mountpoint_symlink \
    dsp.mountpoint_symlink \
    bt_firmware.mountpoint_symlink

# audiohalservice.qti loads every interface below with dlopen(), so Soong cannot
# infer them from normal shared-library dependencies. Keep the XML contract
# explicit here: a missing mandatory library makes the service exit at boot.
# The AOSP audio helpers must come from this Android 16 tree. B4.1's copies use
# older C++ and AIDL layouts and crash the current core implementation.
PRODUCT_PACKAGES += \
    audiohalservice.qti \
    libagmipcservice \
    libagm_mixer_plugin \
    libagm_pcm_plugin \
    libagm_compress_plugin \
    libsndcardparser \
    libpalipcservice \
    libpaleventnotifier \
    libaudiocorehal.qti \
    libaudiocorehal.default \
    qtiaudiohalvendorextn \
    libaudioserviceexampleimpl \
    android.hardware.bluetooth.audio-impl \
    libalsautilsv2.vendor \
    libaudioaidlcommon.vendor \
    libaudio_aidl_conversion_common_ndk.vendor \
    libbluetooth_audio_session_aidl \
    libmediautils_vendor.vendor \
    libnbaio_mono \
    libtinyalsav2.vendor \
    liblistensoundmodelaidl \
    libaudioeffecthal.qti \
    libsoundtriggerhal.qti \
    libqasr

DEVICE_PACKAGE_OVERLAYS += device/nothing/metroid/overlay

# The stock NFC service is disabled until this factory-persisted completion
# property becomes 1. Fresh phone.md data has no factory property store, and
# B4.1 ships no nqnfcinfo helper that could set it again. Seed the same state
# so the stock HAL starts on clean custom-OS installs.
PRODUCT_VENDOR_PROPERTIES += persist.vendor.nfc_getcplc_completed=1

# Camera: skip Morpho EIS init in GME node (SIGILL in libmorpho_video_stabilizer on first
# frame; forces QTIGMEWrapper instead). Verified live 2026-07-10 — camera preview + capture work.
PRODUCT_VENDOR_PROPERTIES += persist.vendor.morpho.eis.force_gmenode=1

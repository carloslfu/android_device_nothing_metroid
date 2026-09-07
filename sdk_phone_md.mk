# API-36 phone.md emulator using the same launcher and checked platform services
# as metroid. Goldfish owns every board/vendor/HAL choice; never inherit the
# physical device product or its proprietary firmware into this virtual device.

# Leave room for the full SDK image plus the launcher and its offline runtimes.
BOARD_EMULATOR_DYNAMIC_PARTITIONS_SIZE := 3221225472
$(call inherit-product, device/generic/goldfish/64bitonly/product/sdk_phone64_arm64.mk)

# The shared Lineage Soong plugins parse their generator variables even when
# the SDK product does not build a kernel. Export the standard configuration;
# Goldfish still selects its own kernel and every board/vendor implementation.
include vendor/lineage/config/BoardConfigSoong.mk

# The Lineage framework needs its paired SDK jar, resources and permissions.
include vendor/lineage/config/lineage_sdk_common.mk
# DisplayPolicy reads LineageSettings during system_server startup. The SDK
# library alone does not install this core provider; omitting it crash-loops
# before PackageManager/WindowManager become available on a fresh emulator.
PRODUCT_PACKAGES += LineageSettingsProvider

PRODUCT_NAME := sdk_phone_md
PRODUCT_DEVICE := emu64a
# build/make/core/config.mk includes the standard Lineage service policy only
# for a non-empty LINEAGE_BUILD, after board configuration has finished. The
# SDK uses the Lineage framework too: without these labels, system_server
# cannot publish lineageglobalactions and SystemUI repeatedly crashes.
LINEAGE_BUILD := phone_md_emulator
PRODUCT_BRAND := phone.md
PRODUCT_MODEL := phone.md
PRODUCT_MANUFACTURER := phone.md

PRODUCT_SOONG_NAMESPACES += device/nothing/metroid

# Goldfish's inherited AOSP product owns its APN copy. The unrelated Lineage
# APN prebuilt would otherwise define the same destination during Make parsing.
PRODUCT_SOURCE_ROOT_DIRS += -vendor/apn
PRODUCT_PACKAGES += AppStore PhoneMdLauncher PhoneMdLocaleController PhoneMdPlatformControl

PRODUCT_COPY_FILES += \
    device/nothing/metroid/permissions/phone-md-default-permissions.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/phone-md-default-permissions.xml \
    device/nothing/metroid/permissions/privapp-permissions-phone-md-locale-controller.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-phone-md-locale-controller.xml \
    device/nothing/metroid/permissions/privapp-permissions-phone-md-platform-control.xml:$(TARGET_COPY_OUT_SYSTEM)/etc/permissions/privapp-permissions-phone-md-platform-control.xml

# Provisioning, default HOME-adjacent capabilities and Telecom integration are
# shared source. Keep secure keyguard available; no ro.lockscreen.disable flag.
PRODUCT_PACKAGE_OVERLAYS += device/nothing/metroid/phone_md_overlay
PRODUCT_SYSTEM_PROPERTIES += ro.setupwizard.mode=DISABLED

# These explicit phone.md system modules are intentional additions to the
# generic SDK system partition. Keep artifact path enforcement everywhere else.
PRODUCT_ARTIFACT_PATH_REQUIREMENT_ALLOWED_LIST += \
    system/etc/permissions/privapp-permissions-phone-md-locale-controller.xml \
    system/etc/permissions/privapp-permissions-phone-md-platform-control.xml \
    system/priv-app/PhoneMdLauncher/% \
    system/priv-app/PhoneMdLocaleController/% \
    system/priv-app/PhoneMdPlatformControl/% \
    system/media/bootanimation.zip

PRODUCT_COPY_FILES += \
    device/nothing/metroid/bootanimation/bootanimation.zip:$(TARGET_COPY_OUT_SYSTEM)/media/bootanimation.zip

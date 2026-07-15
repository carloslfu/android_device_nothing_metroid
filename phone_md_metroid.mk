# phone.md full-device product for the Nothing Phone 3.

$(call inherit-product, device/nothing/metroid/lineage_metroid.mk)

PRODUCT_NAME := lineage_phone_md_metroid
PRODUCT_DEVICE := metroid
PRODUCT_MANUFACTURER := Nothing
PRODUCT_BRAND := Nothing
PRODUCT_MODEL := Phone (3)

# PRODUCT_DEVICE must be assigned by this top-level product. Product
# inheritance does not carry the inherited product's identity into the lunch
# target early enough for BoardConfig.mk discovery. Without this line, Soong
# configures a generic target and never exports metroid's prebuilt-kernel
# variables.

# Onboarding belongs to the realtime phone.md agent. The first file is empty;
# the agent builds it through edit_phone_md as the conversation progresses.
# PhoneMdLauncher's module overrides remove LineageSetupWizard and Provision;
# PRODUCT_PACKAGES subtraction cannot remove an inherited product package.
PRODUCT_PACKAGES += PhoneMdLauncher

# The ROM, not an onboarding wizard, owns the ordinary runtime grants needed by
# its only HOME and voice agent. Platform/signature authorities stay ungranted
# until the enforcing SELinux + secure-adb control-service gate.
PRODUCT_COPY_FILES += \
    $(LOCAL_PATH)/permissions/phone-md-default-permissions.xml:$(TARGET_COPY_OUT_PRODUCT)/etc/default-permissions/phone-md-default-permissions.xml

# There is no scripted setup wizard in phone.md. Seed SettingsProvider as
# provisioned on fresh data so SystemUI and HOME start normally while the agent
# handles onboarding in conversation.
PRODUCT_PACKAGE_OVERLAYS += device/nothing/metroid/phone_md_overlay

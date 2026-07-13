# phone.md full-device product for the Nothing Phone 3.

$(call inherit-product, device/nothing/metroid/lineage_metroid.mk)

PRODUCT_NAME := phone_md_metroid

# Onboarding belongs to the realtime phone.md agent. The first file is empty;
# the agent builds it through edit_phone_md as the conversation progresses.
# PhoneMdLauncher's module overrides remove LineageSetupWizard and Provision;
# PRODUCT_PACKAGES subtraction cannot remove an inherited product package.
PRODUCT_PACKAGES += PhoneMdLauncher

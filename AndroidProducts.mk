PRODUCT_MAKEFILES := \
    $(LOCAL_DIR)/lineage_metroid.mk \
    lineage_phone_md_metroid:$(LOCAL_DIR)/phone_md_metroid.mk \
    sdk_phone_md:$(LOCAL_DIR)/sdk_phone_md.mk

COMMON_LUNCH_CHOICES := \
    lineage_metroid-bp2a-user \
    lineage_metroid-bp2a-userdebug \
    lineage_metroid-bp2a-eng \
    lineage_phone_md_metroid-bp2a-userdebug \
    sdk_phone_md-bp2a-userdebug

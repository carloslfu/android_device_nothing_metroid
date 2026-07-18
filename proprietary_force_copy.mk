# Generated makefile to force copy missing proprietary blobs

# B4.1's ImsService is the framework endpoint for the Qualcomm IMS HALs that
# run on metroid. QtiTelephonyService forwards the modem call-state and VSID
# values that start the PAL voice-call stream. QtiTelephony and
# qcrilmsgtunnel carry the Android-to-modem readiness path that enables SMS
# delivery after SIM and carrier config loading. The IMS APK stays presigned;
# all three QTI phone-process APKs are signed with this ROM's platform key.
PRODUCT_PACKAGES += \
    QtiTelephony \
    QtiTelephonyService \
    ims \
    ims-ext-common \
    ims_ext_common.xml \
    metroid_ims_libimscamera_jni_symlink \
    metroid_ims_libimsmedia_jni_symlink \
    qcrilmsgtunnel

PRODUCT_COPY_FILES += \
    vendor/nothing/metroid/proprietary/vendor/etc/aidl/le_audio/aidl_audio_set_configurations.json:$(TARGET_COPY_OUT_VENDOR)/etc/aidl/le_audio/aidl_audio_set_configurations.json \
    vendor/nothing/metroid/proprietary/vendor/etc/aidl/le_audio/aidl_audio_set_scenarios.json:$(TARGET_COPY_OUT_VENDOR)/etc/aidl/le_audio/aidl_audio_set_scenarios.json \
    vendor/nothing/metroid/proprietary/vendor/etc/richtapresources/notification/oi!.he:$(TARGET_COPY_OUT_VENDOR)/etc/richtapresources/notification/oi!.he \
    vendor/nothing/metroid/proprietary/vendor/product/media/audio/notifications/01_oi.ogg:$(TARGET_COPY_OUT_VENDOR)/product/media/audio/notifications/01_oi.ogg

# Nothing's WCN7750 calibration is device-specific and has no source owner.
PRODUCT_COPY_FILES += \
    vendor/nothing/metroid/proprietary/vendor/etc/wifi/wcn7750/WCNSS_qcom_cfg.ini:$(TARGET_COPY_OUT_VENDOR)/etc/wifi/wcn7750/WCNSS_qcom_cfg.ini

# The source tree has no Qualcomm implementation for this device's Bluetooth HAL.
# The extracted service must start normally because the VINTF manifest declares it.
PRODUCT_COPY_FILES += \
    vendor/nothing/metroid/proprietary/vendor/etc/init/android.hardware.bluetooth@aidl-service-qti.rc:$(TARGET_COPY_OUT_VENDOR)/etc/init/android.hardware.bluetooth@aidl-service-qti.rc

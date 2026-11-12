#
# SPDX-FileCopyrightText: The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

# Inherit from the custom device configuration.
$(call inherit-product, device/nothing/Aerodactyl/device-Pacman.mk)

# Inherit from the LineageOS configuration.
$(call inherit-product, vendor/voltage/config/common_full_phone.mk)

PRODUCT_BRAND := Nothing
PRODUCT_DEVICE := Pacman
PRODUCT_MANUFACTURER := Nothing
PRODUCT_MODEL := A142
PRODUCT_NAME := voltage_Pacman

PRODUCT_MANUFACTURER_FOR_ATTESTATION := Nothing 
PRODUCT_BRAND_FOR_ATTESTATION :=  Nothing 
PRODUCT_DEVICE_FOR_ATTESTATION :=  Pacman
PRODUCT_NAME_FOR_ATTESTATION := Nothing A142
PRODUCT_MODEL_FOR_ATTESTATION := A142

# VoltageOS flags
PRODUCT_NO_CAMERA:= true
EXTRA_UDFPS_ANIMATIONS:= true

PRODUCT_GMS_CLIENTID_BASE := android-nothing

PRODUCT_BUILD_PROP_OVERRIDES += \
    BuildDesc="sys_mssi_64_ww_armv82-user 16 BP2A.250605.031.A3 2606091925 release-keys" \
    BuildFingerprint=Nothing/Pacman/Pacman:16/BP2A.250605.031.A3/2606091925:user/release-keys \
    DeviceName=Pacman \
    DeviceProduct=Pacman \
    SystemDevice=Pacman \
    SystemName=Pacman

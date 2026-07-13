#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

from extract_utils.main import (
    ExtractUtils,
    ExtractUtilsModule,
)
from extract_utils.fixups_blob import (
    blob_fixup,
    blob_fixups_user_type,
)

namespace_imports = [
    'hardware/qcom-caf/sm8750',
    'hardware/qcom-caf/wlan',
    'vendor/qcom/opensource/commonsys/display',
    'vendor/qcom/opensource/commonsys-intf/display',
    'vendor/qcom/opensource/dataservices',
    'vendor/qcom/opensource/display',
    'device/nothing/metroid',
]

blob_fixups: blob_fixups_user_type = {
    # The stock blob imports AHardwareBuffer functions without naming their
    # provider. Make the runtime dependency explicit instead of relying on the
    # camera process to load libnativewindow first.
    'vendor/lib64/libntcamskia.so': blob_fixup()
        .add_needed('libnativewindow.so'),
}  # fmt: skip

module = ExtractUtilsModule(
    'metroid',
    'nothing',
    blob_fixups=blob_fixups,
    namespace_imports=namespace_imports,
)

if __name__ == '__main__':
    utils = ExtractUtils.device(module)
    utils.run()

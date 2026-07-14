#!/usr/bin/env -S PYTHONPATH=../../../tools/extract-utils python3
#
# SPDX-FileCopyrightText: 2026 The LineageOS Project
# SPDX-License-Identifier: Apache-2.0
#

import re

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

CIT_POST_FS_DATA_BLOCK = '\n'.join([
    '#add for cit PSN FSN color',
    'on post-fs-data',
    '    mkdir /data/config 0777 root system',
    '    chmod 0644 /mnt/vendor/persist/FSN.txt',
    '    copy /mnt/vendor/persist/FSN.txt /data/config/FSN.txt',
    '    chmod 0444 /data/config/FSN.txt',
    '    chown system system /data/config/FSN.txt',
    '    chmod 0644 /mnt/vendor/persist/PSN.txt',
    '    copy /mnt/vendor/persist/PSN.txt /data/config/PSN.txt',
    '    chmod 0444 /data/config/PSN.txt',
    '    chown system system /data/config/PSN.txt',
    '    copy /mnt/vendor/persist/color /data/config/color',
    '    chmod 0666 /data/config/color',
    '    chown system system /data/config/color',
    '',
])

blob_fixups: blob_fixups_user_type = {
    # The stock blob imports AHardwareBuffer functions without naming their
    # provider. Make the runtime dependency explicit instead of relying on the
    # camera process to load libnativewindow first.
    'vendor/lib64/libntcamskia.so': blob_fixup()
        .add_needed('libnativewindow.so'),
    # Android 16 requires an encryption policy for new top-level /data
    # directories. Nothing's factory/CIT hook creates /data/config as 0777,
    # copies per-unit identifiers into it, then forces init into recovery when
    # vendor_init cannot apply that policy. phone.md does not use this factory
    # identifier export, so remove it whenever the stock blobs are re-extracted.
    'vendor/etc/init/init.nt_cit.rc': blob_fixup()
        .regex_replace(
            re.escape(CIT_POST_FS_DATA_BLOCK),
            '# phone.md: factory identifier export to /data/config removed.\n',
        ),
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

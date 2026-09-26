import os as _os
_IPA = _os.path.dirname(_os.path.dirname(_os.path.abspath(__file__)))
import re, sys

ROOT = _IPA + r'\CarDash'

plist = open(ROOT + r'\ios\Info.plist', encoding='utf-8').read()
swift = open(ROOT + r'\ios\CarDash\UI\SettingsSheet.swift', encoding='utf-8').read()
proj = open(ROOT + r'\ios\project.yml', encoding='utf-8').read()
wf = open(ROOT + r'\.github\workflows\ios-ipa.yml', encoding='utf-8').read()

D = '$'

checks = [
    ('Info.plist 引用 MARKETING_VERSION',
     D + '(MARKETING_VERSION)' in plist),
    ('Info.plist 引用 CURRENT_PROJECT_VERSION',
     D + '(CURRENT_PROJECT_VERSION)' in plist),
    ('Info.plist 已无写死的 1.0.0',
     '<string>1.0.0</string>' not in plist),
    ('Info.plist 已无写死的 CFBundleVersion=<string>1',
     not re.search(r'CFBundleVersion</key>\s*<string>1</string>', plist)),

    ('project.yml MARKETING_VERSION',
     re.search(r'MARKETING_VERSION:\s*"([\d.]+)"', proj) is not None),
    ('project.yml 版本号 = ' +
     (re.search(r'MARKETING_VERSION:\s*"([\d.]+)"', proj).group(1)
      if re.search(r'MARKETING_VERSION:\s*"([\d.]+)"', proj) else '?'),
     True),

    ('CI 读 MARKETING_VERSION', 'MARKETING_VERSION' in wf and 'sed' in wf),
    ('CI 注入 CURRENT_PROJECT_VERSION',
     'CURRENT_PROJECT_VERSION="${{ github.run_number }}"' in wf),
    ('CI Release 名字用动态版本',
     'IPA ' + D + '{{ steps.ver.outputs.marketing }}' in wf),
    ('CI Release 里已无写死的 1.1.0', '1.1.0' not in wf),

    ('Swift: appVersion 读 CFBundleShortVersionString',
     'CFBundleShortVersionString' in swift),
    ('Swift: 显示 "版本 (构建号)"', r'\(v) (\(b))' in swift),
    ('Swift: bridgeVersion 区分「旧版 APK」',
     '旧版 APK（无版本标识）' in swift),
    ('Swift: 版本行用 Text(bridgeVersion)', 'Text(bridgeVersion)' in swift),
    ('Swift: 已无旧写法 model.car?.src?["apkVer"]',
     'model.car?.src?["apkVer"]' not in swift),
]

bad = 0
for name, ok in checks:
    if not ok:
        bad += 1
    print('%-46s %s' % (name, 'OK' if ok else '缺失 <<<'))

print()
print('全部通过' if bad == 0 else ('有 %d 项没通过' % bad))
sys.exit(1 if bad else 0)

import os as _os
_IPA = _os.path.dirname(_os.path.dirname(_os.path.abspath(__file__)))
import os
import plistlib
import sys
import xml.etree.ElementTree as ET

ROOT = _IPA + r'\CarDash'
FAILED = []


def bad(m):
    FAILED.append(m)
    print('  !! ' + m)


print('=' * 58)
print('1) 工作流 YAML')
try:
    import yaml
    for p in ('.github/workflows/ios-ipa.yml',
              'ios/project.yml'):
        with open(os.path.join(ROOT, p), encoding='utf-8') as f:
            yaml.safe_load(f)
        print('  OK  ' + p)
except ImportError:
    print('  跳过（无 pyyaml）')
except Exception as e:
    bad('YAML: %s' % e)

print('=' * 58)
print('2) Info.plist')
try:
    with open(os.path.join(ROOT, 'ios', 'Info.plist'), 'rb') as f:
        pl = plistlib.load(f)
    print('  OK  键数 =', len(pl))
    for k in ('UISupportedInterfaceOrientations', 'NSLocalNetworkUsageDescription',
              'NSLocationWhenInUseUsageDescription', 'UIRequiresFullScreen'):
        if k not in pl:
            bad('Info.plist 缺少 ' + k)
except Exception as e:
    bad('Info.plist: %s' % e)

print('=' * 58)
print('3) 注入组件：源码 / 清单脚本 / 桥接常量三方一致')
java_dir = os.path.join(ROOT, 'inject', 'src', 'com', 'cardash', 'inject')
injector = open(os.path.join(ROOT, 'tools', 'inject_manifest.py'), encoding='utf-8').read()
for cls in ('BootProvider', 'BridgeService', 'NavListenerService'):
    if not os.path.isfile(os.path.join(java_dir, cls + '.java')):
        bad('缺少源码 ' + cls + '.java')
    if ('com.cardash.inject.' + cls) not in injector:
        bad('inject_manifest.py 缺少组件 ' + cls)
    print('  OK  ' + cls)

if 'com.deepalhome.launcher' not in injector:
    bad('inject_manifest.py 里没有包名')
if 'android.uid.system' not in open(os.path.join(java_dir, 'BridgeRuntime.java'),
                                    encoding='utf-8').read() + \
        open(os.path.join(java_dir, 'BridgeRuntime.java'), encoding='utf-8').read():
    pass

bl = open(os.path.join(ROOT, 'tools/build_local.py'), encoding='utf-8').read()
for need in ('EXPECTED_CERT_SHA256',
             'c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8',
             'inject_manifest', '--roundtrip', 'zipalign', 'apksigner', 'd8'):
    if need not in bl:
        bad('build_local.py 缺少 ' + need)
print('  OK  本地构建脚本关键步骤齐全')

wf_ios = open(os.path.join(ROOT, '.github/workflows/ios-ipa.yml'), encoding='utf-8').read()
for need in ('macos', 'xcodegen', 'xcodebuild', 'iphoneos', 'Payload'):
    if need.lower() not in wf_ios.lower():
        bad('ios-ipa.yml 缺少 ' + need)
if 'patch-deepal' in wf_ios:
    bad('ios-ipa.yml 里残留 APK 相关引用')
print('  OK  iOS 工作流齐全')

if os.path.isfile(os.path.join(ROOT, '.github/workflows/patch-deepal-apk.yml')):
    bad('APK 工作流应当已被移除')
print('  OK  APK 工作流已移除（APK 只在本地构建）')

print('=' * 58)
print('4) tools 目录')
tools = os.listdir(os.path.join(ROOT, 'tools'))
for f in ('axml.py', 'inject_manifest.py', 'build_local.py', 'setup_env.py'):
    if f not in tools:
        bad('tools 缺少 ' + f)
print('  tools:', sorted(tools))

print('=' * 58)
print('5) iOS 源文件')
ios_dir = os.path.join(ROOT, 'ios', 'CarDash')
count = 0
for base, _, files in os.walk(ios_dir):
    count += len([f for f in files if f.endswith('.swift')])
print('  Swift 文件 %d 个' % count)
for f in ('App/CarDashApp.swift', 'Model/VehicleState.swift',
          'Source/DashboardModel.swift', 'Source/LocalSensors.swift',
          'UI/DashboardView.swift', 'UI/Panels.swift',
          'UI/BackgroundScene.swift', 'UI/SettingsSheet.swift'):
    if not os.path.isfile(os.path.join(ios_dir, f)):
        bad('缺少 ios/CarDash/' + f)
print('  8 个源文件齐全')

print()
print('=' * 58)
if FAILED:
    print('结果: 有 %d 项问题' % len(FAILED))
    for f in FAILED:
        print('  - ' + f)
    sys.exit(1)
print('结果: 全部通过')

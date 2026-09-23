// 高德导航 SDK 的 Swift 桥接头文件。
//
// ⚠️ 为什么必须有它：AMapNaviKit 的 module umbrella 不完整，
// Swift 直接 `import AMapNaviKit` 只能看到 AMapNaviDriveManager 等一部分类，
// AMapNaviView / AMapNaviViewOptions 会报 cannot find type。
// 所以这里在 umbrella 之外，再显式把这两个头文件单独 import 进来。
//
// 如果这里报 'AMapNaviKit/AMapNaviView.h' file not found，
// 就说明当前 SDK 版本里这个类已经被移除，得换回旧版本 SDK。
#import <AMapNaviKit/AMapNaviKit.h>
#import <AMapNaviKit/AMapNaviView.h>
#import <AMapNaviKit/AMapNaviViewOptions.h>
#import <AMapNaviKit/MAMapKit.h>
#import <AMapFoundationKit/AMapFoundationKit.h>

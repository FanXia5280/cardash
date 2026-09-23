// 高德导航 SDK 的 Swift 桥接头文件。
//
// ⚠️ 为什么必须有它：AMapNaviKit 的 module umbrella 不完整，
// Swift 直接 `import AMapNaviKit` 只能看到 AMapNaviDriveManager 等一部分类，
// AMapNaviView / AMapNaviViewOptions 会报 cannot find type。
// 高德官方 Swift Guide 的做法就是用 bridging header 显式导入头文件，
// 把 umbrella 里漏掉的类型也暴露给 Swift。
#import <AMapNaviKit/AMapNaviKit.h>
#import <AMapNaviKit/MAMapKit.h>
#import <AMapFoundationKit/AMapFoundationKit.h>

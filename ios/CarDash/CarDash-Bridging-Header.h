// 高德导航 SDK 的 Swift 桥接头文件。
//
// AMapNaviKit 的 module umbrella 不完整，Swift 直接 import 只能看到
// 一部分类。高德官方 Swift Guide 的做法就是用 bridging header 显式
// 导入头文件，把 umbrella 里漏掉的类型也暴露给 Swift。
//
// ⚠️ iOS 的导航视图类叫 AMapNaviDriveView（AMapNaviView 是 Android 的），
// 别再写成 AMapNaviView —— 头文件不存在会报 file not found。
#import <AMapNaviKit/AMapNaviKit.h>
#import <AMapNaviKit/AMapNaviDriveView.h>
#import <AMapNaviKit/MAMapKit.h>
#import <AMapFoundationKit/AMapFoundationKit.h>

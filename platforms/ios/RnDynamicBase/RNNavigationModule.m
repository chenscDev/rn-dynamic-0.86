/**
 * 导出 RNNavigationModule 给 React Native（与 Swift 实现配对）
 */
#import <React/RCTBridgeModule.h>

@interface RCT_EXTERN_MODULE(RNNavigationModule, NSObject)

RCT_EXTERN_METHOD(switchTab:(NSString *)tabId)
RCT_EXTERN_METHOD(finishContainer)
RCT_EXTERN_METHOD(openBundle:(NSString *)bundleKey
                  title:(NSString *)title
                  props:(NSDictionary *)props)

+ (BOOL)requiresMainQueueSetup
{
  return YES;
}

@end

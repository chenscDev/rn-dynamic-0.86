/**
 * 分包内路由：基于 React Navigation Native Stack
 * 每个业务分包可独立定义页面栈，原生仍按 Mode A 整页打开该分包根入口
 */
import React, { type ComponentType } from 'react';
import { NavigationContainer } from '@react-navigation/native';
import {
  createNativeStackNavigator,
  type NativeStackNavigationOptions,
} from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { enableScreens } from 'react-native-screens';

// 启用原生屏幕优化（全局一次即可）
enableScreens(true);

type RouteName<ParamList extends Record<string, object | undefined>> =
  Extract<keyof ParamList, string>;

export type PackageScreenConfig<
  ParamList extends Record<string, object | undefined>,
> = {
  name: RouteName<ParamList>;
  /** 路由组件 props 由 React Navigation 注入，这里使用宽松类型便于业务注册 */
  component: ComponentType<any>;
  options?: NativeStackNavigationOptions;
};

export type CreatePackageAppOptions<
  ParamList extends Record<string, object | undefined>,
> = {
  /** 初始路由名 */
  initialRouteName: RouteName<ParamList>;
  /** 分包内页面列表 */
  screens: Array<PackageScreenConfig<ParamList>>;
  /** 栈默认 options */
  screenOptions?: NativeStackNavigationOptions;
};

/**
 * 创建带内部路由的分包根组件，供 registerPage 注册
 */
export function createPackageApp<
  ParamList extends Record<string, object | undefined>,
>(
  options: CreatePackageAppOptions<ParamList>,
): ComponentType<Record<string, unknown>> {
  const Stack = createNativeStackNavigator<ParamList>();
  const { initialRouteName, screens, screenOptions } = options;

  if (!screens.length) {
    throw new Error('[rn-dynamic] createPackageApp: screens 不能为空');
  }

  function PackageAppRoot(props: Record<string, unknown>): React.JSX.Element {
    return (
      <SafeAreaProvider>
        <NavigationContainer>
          <Stack.Navigator
            initialRouteName={initialRouteName}
            screenOptions={{
              headerBackTitle: '返回',
              ...screenOptions,
            }}>
            {screens.map(screen => (
              <Stack.Screen
                key={screen.name}
                name={screen.name}
                component={screen.component}
                options={screen.options}
                initialParams={
                  screen.name === initialRouteName
                    ? (props as never)
                    : undefined
                }
              />
            ))}
          </Stack.Navigator>
        </NavigationContainer>
      </SafeAreaProvider>
    );
  }

  return PackageAppRoot;
}

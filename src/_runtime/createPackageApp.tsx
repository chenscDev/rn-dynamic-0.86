/**
 * 分包内路由：基于 React Navigation Native Stack
 * 返回逻辑：栈内 pop；栈底且来自原生入口则关闭容器回原生
 */
import React, { type ComponentType } from 'react';
import { Pressable, Text } from 'react-native';
import { NavigationContainer } from '@react-navigation/native';
import {
  createNativeStackNavigator,
  type NativeStackNavigationOptions,
} from '@react-navigation/native-stack';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { enableScreens } from 'react-native-screens';
import { finishNativeContainer } from './navigationBridge';

enableScreens(true);

type RouteName<ParamList extends Record<string, object | undefined>> =
  Extract<keyof ParamList, string>;

type NativeEntryParams = {
  fromNative?: string;
};

export type PackageScreenConfig<
  ParamList extends Record<string, object | undefined>,
> = {
  name: RouteName<ParamList>;
  component: ComponentType<any>;
  options?: NativeStackNavigationOptions;
};

export type CreatePackageAppOptions<
  ParamList extends Record<string, object | undefined>,
> = {
  initialRouteName: RouteName<ParamList>;
  screens: Array<PackageScreenConfig<ParamList>>;
  screenOptions?: NativeStackNavigationOptions;
};

function resolveFromNative(
  routeParams: unknown,
  rootFromNative?: string,
): string | undefined {
  const params = routeParams as NativeEntryParams | undefined;
  return params?.fromNative ?? rootFromNative;
}

function createHeaderBackToNative(tintColor?: string): React.JSX.Element {
  return (
    <Pressable
      onPress={finishNativeContainer}
      hitSlop={8}
      style={{ paddingHorizontal: 8, paddingVertical: 4 }}>
      <Text style={{ color: tintColor ?? '#007AFF', fontSize: 17 }}>返回</Text>
    </Pressable>
  );
}

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
    const rootFromNative =
      typeof props.fromNative === 'string' ? props.fromNative : undefined;

    return (
      <SafeAreaProvider>
        <NavigationContainer>
          <Stack.Navigator
            initialRouteName={initialRouteName}
            screenOptions={({ navigation, route }) => {
              const fromNative = resolveFromNative(route.params, rootFromNative);
              const canGoBack = navigation.canGoBack();
              const base: NativeStackNavigationOptions = {
                headerBackTitle: '返回',
                gestureEnabled: true,
                fullScreenGestureEnabled: true,
                ...screenOptions,
              };

              if (canGoBack) {
                // 分包内二级页：默认返回键 pop 到上一 RN 页
                return base;
              }

              if (fromNative) {
                // 栈底且从原生入口打开：导航栏返回关闭容器
                return {
                  ...base,
                  headerLeft: ({ tintColor }) =>
                    createHeaderBackToNative(tintColor),
                };
              }

              return base;
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

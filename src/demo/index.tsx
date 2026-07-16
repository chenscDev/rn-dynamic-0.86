/**
 * 示例分包：demo（含分包内路由）
 */
import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { createPackageApp, PageShell, registerPage } from '../_runtime';

type DemoStackParamList = {
  DemoMain: { message?: string } | undefined;
  DemoSecond: undefined;
};

type MainProps = NativeStackScreenProps<DemoStackParamList, 'DemoMain'>;
type SecondProps = NativeStackScreenProps<DemoStackParamList, 'DemoSecond'>;

function DemoMainScreen({ navigation, route }: MainProps): React.JSX.Element {
  return (
    <PageShell pageKey="demo">
      <View style={styles.card}>
        <Text style={styles.text}>用于验证按文件夹独立打包与加载。</Text>
        <Text style={styles.meta}>
          {route.params?.message ?? 'hello from demo'}
        </Text>
        <Pressable
          style={styles.button}
          onPress={() => navigation.navigate('DemoSecond')}>
          <Text style={styles.buttonText}>下一页</Text>
        </Pressable>
      </View>
    </PageShell>
  );
}

function DemoSecondScreen({ navigation }: SecondProps): React.JSX.Element {
  return (
    <PageShell pageKey="demo">
      <View style={styles.card}>
        <Text style={styles.text}>demo 分包内第二页。</Text>
        <Pressable style={styles.button} onPress={() => navigation.goBack()}>
          <Text style={styles.buttonText}>返回</Text>
        </Pressable>
      </View>
    </PageShell>
  );
}

const DemoApp = createPackageApp<DemoStackParamList>({
  initialRouteName: 'DemoMain',
  screens: [
    {
      name: 'DemoMain',
      component: DemoMainScreen,
      options: { title: 'Demo' },
    },
    {
      name: 'DemoSecond',
      component: DemoSecondScreen,
      options: { title: 'Demo Second' },
    },
  ],
});

registerPage({ key: 'demo' }, DemoApp);

export default DemoApp;

const styles = StyleSheet.create({
  card: {
    marginTop: 12,
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#FFFFFF',
  },
  text: { fontSize: 16, lineHeight: 24, color: '#222' },
  meta: { marginTop: 12, fontSize: 13, color: '#666' },
  button: {
    marginTop: 16,
    alignSelf: 'flex-start',
    paddingHorizontal: 14,
    paddingVertical: 10,
    borderRadius: 8,
    backgroundColor: '#1F6FEB',
  },
  buttonText: { color: '#FFF', fontWeight: '600' },
});

/**
 * 示例分包：home（含分包内路由）
 */
import React from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { createPackageApp, PageShell, registerPage } from '../_runtime';
import { PlatformTag } from './PlatformTag';

type HomeStackParamList = {
  HomeMain: { fromNative?: string } | undefined;
  HomeAbout: undefined;
};

type MainProps = NativeStackScreenProps<HomeStackParamList, 'HomeMain'>;
type AboutProps = NativeStackScreenProps<HomeStackParamList, 'HomeAbout'>;

function HomeMainScreen({ navigation, route }: MainProps): React.JSX.Element {
  return (
    <PageShell pageKey="home">
      <View style={styles.card}>
        <Text style={styles.text}>
          整页替换 Mode A：原生打开 home 分包后，内部可再路由切换。
        </Text>
        <Text style={styles.meta}>
          原生传入: {route.params?.fromNative ?? '(无)'}
        </Text>
        <PlatformTag />
        <Pressable
          style={styles.button}
          onPress={() => navigation.navigate('HomeAbout')}>
          <Text style={styles.buttonText}>进入 About 页</Text>
        </Pressable>
      </View>
    </PageShell>
  );
}

function HomeAboutScreen({ navigation }: AboutProps): React.JSX.Element {
  return (
    <PageShell pageKey="home">
      <View style={styles.card}>
        <Text style={styles.text}>这是 home 分包内的二级页面。</Text>
        <Pressable style={styles.button} onPress={() => navigation.goBack()}>
          <Text style={styles.buttonText}>返回</Text>
        </Pressable>
      </View>
    </PageShell>
  );
}

const HomeApp = createPackageApp<HomeStackParamList>({
  initialRouteName: 'HomeMain',
  screens: [
    {
      name: 'HomeMain',
      component: HomeMainScreen,
      options: { title: 'Home' },
    },
    {
      name: 'HomeAbout',
      component: HomeAboutScreen,
      options: { title: 'About' },
    },
  ],
});

registerPage({ key: 'home' }, HomeApp);

export default HomeApp;

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

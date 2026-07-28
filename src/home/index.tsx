/**
 * 宿主 Metro 调试用 home 占位：正式业务在 rn-biz-0.86/src/home
 * 保持可注册，避免本地 Metro 缺页。
 */
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { createPackageApp, PageShell, registerPage } from '../_runtime';

function HomeMainScreen(): React.JSX.Element {
  return (
    <PageShell pageKey="home">
      <View style={styles.card}>
        <Text style={styles.title}>AI 短视频（宿主占位）</Text>
        <Text style={styles.text}>
          完整创作 UI 在 rn-biz-0.86 的 home 分包。请用业务仓 Metro / CDN 分包调试。
        </Text>
      </View>
    </PageShell>
  );
}

const HomeApp = createPackageApp({
  initialRouteName: 'HomeMain',
  screens: [
    {
      name: 'HomeMain',
      component: HomeMainScreen,
      options: { title: 'AI 短视频' },
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
  title: { fontSize: 18, fontWeight: '700', color: '#0F172A', marginBottom: 8 },
  text: { fontSize: 15, lineHeight: 22, color: '#334155' },
});

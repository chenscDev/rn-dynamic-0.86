/**
 * 基座壳页面：本地用 Metro 默认入口时展示说明
 * 正式整页打开业务时，原生应直接加载对应分包 bundle（moduleName = key）
 */
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { PageShell } from '../src/_runtime';

function App(): React.JSX.Element {
  return (
    <PageShell pageKey="base" title="RN Dynamic Base 0.86">
      <View style={styles.card}>
        <Text style={styles.text}>
          目录：src 业务页 · packages 打包 · native SDK · platforms 本地壳 ·
          project 配置/文档/产物 · tooling 工程配置。
        </Text>
        <Text style={styles.meta}>
          整页打开请使用原生入口加载对应 key（如 home / order）。
        </Text>
        <Text style={styles.meta}>
          每个分包入口可用 createPackageApp 做 RN 内部路由切换。
        </Text>
      </View>
    </PageShell>
  );
}

export default App;

const styles = StyleSheet.create({
  card: {
    marginTop: 12,
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#FFFFFF',
  },
  text: {
    fontSize: 16,
    lineHeight: 24,
    color: '#222',
  },
  meta: {
    marginTop: 10,
    fontSize: 13,
    lineHeight: 20,
    color: '#666',
  },
});

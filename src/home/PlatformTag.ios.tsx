/**
 * iOS 专用 UI 片段（仅 iOS 打包 / 运行会包含此文件）
 */
import React from 'react';
import { StyleSheet, Text, View } from 'react-native';

export function PlatformTag(): React.JSX.Element {
  return (
    <View style={styles.tag}>
      <Text style={styles.text}>iOS 专用模块</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  tag: {
    marginTop: 10,
    alignSelf: 'flex-start',
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 6,
    backgroundColor: '#E8F0FE',
  },
  text: {
    fontSize: 12,
    color: '#1F6FEB',
    fontWeight: '600',
  },
});

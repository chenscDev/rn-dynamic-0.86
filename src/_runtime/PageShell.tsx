/**
 * 分包页面通用根组件包装：提供安全区与错误边界
 */
import React, { Component, type ErrorInfo, type ReactNode } from 'react';
import {
  SafeAreaView,
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  useColorScheme,
  View,
} from 'react-native';

type ErrorBoundaryProps = {
  children: ReactNode;
  pageKey: string;
};

type ErrorBoundaryState = {
  error: Error | null;
};

class PageErrorBoundary extends Component<
  ErrorBoundaryProps,
  ErrorBoundaryState
> {
  state: ErrorBoundaryState = { error: null };

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { error };
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    // 预留：可上报到原生日志通道
    console.error(`[rn-dynamic] page=${this.props.pageKey}`, error, info);
  }

  render(): ReactNode {
    if (this.state.error) {
      return (
        <View style={styles.errorBox}>
          <Text style={styles.errorTitle}>页面加载失败</Text>
          <Text style={styles.errorKey}>key: {this.props.pageKey}</Text>
          <Text style={styles.errorMsg}>{this.state.error.message}</Text>
        </View>
      );
    }
    return this.props.children;
  }
}

export type PageShellProps = {
  pageKey: string;
  title?: string;
  children: ReactNode;
};

/**
 * 整页替换模式下的页面外壳
 */
export function PageShell({
  pageKey,
  title,
  children,
}: PageShellProps): React.JSX.Element {
  const isDark = useColorScheme() === 'dark';

  return (
    <PageErrorBoundary pageKey={pageKey}>
      <SafeAreaView
        style={[styles.root, isDark ? styles.rootDark : styles.rootLight]}>
        <StatusBar barStyle={isDark ? 'light-content' : 'dark-content'} />
        {title ? (
          <View style={styles.header}>
            <Text style={[styles.title, isDark && styles.textLight]}>
              {title}
            </Text>
            <Text style={styles.sub}>分包 key: {pageKey}</Text>
          </View>
        ) : null}
        <ScrollView
          contentContainerStyle={styles.body}
          keyboardShouldPersistTaps="handled">
          {children}
        </ScrollView>
      </SafeAreaView>
    </PageErrorBoundary>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  rootLight: { backgroundColor: '#F7F8FA' },
  rootDark: { backgroundColor: '#0F1115' },
  header: {
    paddingHorizontal: 20,
    paddingTop: 12,
    paddingBottom: 8,
  },
  title: {
    fontSize: 22,
    fontWeight: '700',
    color: '#111',
  },
  textLight: { color: '#F5F5F5' },
  sub: {
    marginTop: 4,
    fontSize: 13,
    color: '#888',
  },
  body: {
    paddingHorizontal: 20,
    paddingBottom: 40,
  },
  errorBox: {
    flex: 1,
    justifyContent: 'center',
    padding: 24,
    backgroundColor: '#FFF5F5',
  },
  errorTitle: {
    fontSize: 18,
    fontWeight: '700',
    color: '#C62828',
    marginBottom: 8,
  },
  errorKey: { color: '#666', marginBottom: 8 },
  errorMsg: { color: '#333', lineHeight: 20 },
});

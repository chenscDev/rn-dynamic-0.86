/**
 * 分包页面通用根组件：基础布局用 StyleSheet 保证真机可见
 */
import React, { Component, type ErrorInfo, type ReactNode } from 'react';
import {
  ScrollView,
  StatusBar,
  StyleSheet,
  Text,
  useColorScheme,
  View,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';

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
    console.error(`[rn-dynamic] page=${this.props.pageKey}`, error, info);
  }

  render(): ReactNode {
    if (this.state.error) {
      return (
        <View style={styles.errorBox}>
          <Text style={styles.errorTitle}>页面加载失败</Text>
          <Text style={styles.errorMeta}>key: {this.props.pageKey}</Text>
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

export function PageShell({
  pageKey,
  title,
  children,
}: PageShellProps): React.JSX.Element {
  const isDark = useColorScheme() === 'dark';

  return (
    <PageErrorBoundary pageKey={pageKey}>
      <SafeAreaView
        style={[styles.root, isDark ? styles.rootDark : styles.rootLight]}
        edges={['top', 'left', 'right', 'bottom']}>
        <StatusBar barStyle={isDark ? 'light-content' : 'dark-content'} />
        {title ? (
          <View style={styles.header}>
            <Text style={[styles.title, isDark && styles.textLight]}>{title}</Text>
            <Text style={styles.subtitle}>分包 key: {pageKey}</Text>
          </View>
        ) : null}
        <ScrollView
          style={styles.scroll}
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled">
          {children}
        </ScrollView>
      </SafeAreaView>
    </PageErrorBoundary>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  rootLight: { backgroundColor: '#F5F5F5' },
  rootDark: { backgroundColor: '#111111' },
  header: { paddingHorizontal: 20, paddingTop: 12, paddingBottom: 8 },
  title: { fontSize: 22, fontWeight: '700', color: '#111111' },
  textLight: { color: '#F5F5F5' },
  subtitle: { marginTop: 4, fontSize: 12, color: '#888888' },
  scroll: { flex: 1 },
  scrollContent: { paddingHorizontal: 20, paddingBottom: 40 },
  errorBox: {
    flex: 1,
    justifyContent: 'center',
    padding: 24,
    backgroundColor: '#FFEBEE',
  },
  errorTitle: { fontSize: 18, fontWeight: '700', color: '#B00020' },
  errorMeta: { marginTop: 8, fontSize: 13, color: '#666666' },
  errorMsg: { marginTop: 8, fontSize: 14, color: '#333333' },
});

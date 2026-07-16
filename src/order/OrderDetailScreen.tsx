/**
 * 订单详情页（分包内二级路由）
 */
import React, { useMemo } from 'react';
import { StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { PageShell } from '../_runtime';
import { MOCK_ORDERS, STATUS_LABEL, type OrderStackParamList } from './types';

type Props = NativeStackScreenProps<OrderStackParamList, 'OrderDetail'>;

export function OrderDetailScreen({ route }: Props): React.JSX.Element {
  const order = useMemo(
    () => MOCK_ORDERS.find(item => item.id === route.params.orderId),
    [route.params.orderId],
  );

  if (!order) {
    return (
      <PageShell pageKey="order">
        <View style={styles.card}>
          <Text style={styles.title}>订单不存在</Text>
          <Text style={styles.meta}>id: {route.params.orderId}</Text>
        </View>
      </PageShell>
    );
  }

  return (
    <PageShell pageKey="order">
      <View style={styles.card}>
        <Text style={styles.label}>订单号</Text>
        <Text style={styles.value}>{order.id}</Text>

        <Text style={styles.label}>商品</Text>
        <Text style={styles.value}>{order.title}</Text>

        <Text style={styles.label}>金额</Text>
        <Text style={styles.amount}>¥{order.amount.toFixed(2)}</Text>

        <Text style={styles.label}>状态</Text>
        <Text style={styles.value}>{STATUS_LABEL[order.status]}</Text>
      </View>
    </PageShell>
  );
}

const styles = StyleSheet.create({
  card: {
    marginTop: 8,
    padding: 16,
    borderRadius: 12,
    backgroundColor: '#FFFFFF',
  },
  label: {
    marginTop: 12,
    fontSize: 12,
    color: '#888',
  },
  value: {
    marginTop: 4,
    fontSize: 16,
    color: '#222',
  },
  amount: {
    marginTop: 4,
    fontSize: 20,
    fontWeight: '700',
    color: '#111',
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
    color: '#C62828',
  },
  meta: {
    marginTop: 8,
    color: '#666',
  },
});

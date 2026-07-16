/**
 * 订单列表页
 */
import React, { useMemo, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { PageShell } from '../_runtime';
import {
  MOCK_ORDERS,
  STATUS_LABEL,
  type OrderStackParamList,
  type OrderStatus,
} from './types';

type Props = NativeStackScreenProps<OrderStackParamList, 'OrderList'>;

export function OrderListScreen({ navigation, route }: Props): React.JSX.Element {
  const [filter, setFilter] = useState<OrderStatus | 'all'>(
    route.params?.initialStatus ?? 'all',
  );

  const orders = useMemo(() => {
    if (filter === 'all') {
      return MOCK_ORDERS;
    }
    return MOCK_ORDERS.filter(item => item.status === filter);
  }, [filter]);

  const filters: Array<OrderStatus | 'all'> = [
    'all',
    'pending',
    'paid',
    'shipped',
    'done',
  ];

  return (
    <PageShell pageKey="order">
      <View style={styles.metaBox}>
        <Text style={styles.meta}>
          用户: {route.params?.userId ?? 'guest'} · 分包内路由示例
        </Text>
        <Text style={styles.hint}>点击订单进入详情（RN 内部路由）</Text>
      </View>

      <View style={styles.filterRow}>
        {filters.map(item => {
          const active = filter === item;
          const label = item === 'all' ? '全部' : STATUS_LABEL[item];
          return (
            <Pressable
              key={item}
              onPress={() => setFilter(item)}
              style={[styles.chip, active && styles.chipActive]}>
              <Text style={[styles.chipText, active && styles.chipTextActive]}>
                {label}
              </Text>
            </Pressable>
          );
        })}
      </View>

      {orders.map(order => (
        <Pressable
          key={order.id}
          style={styles.card}
          onPress={() =>
            navigation.navigate('OrderDetail', { orderId: order.id })
          }>
          <View style={styles.cardHeader}>
            <Text style={styles.orderId}>{order.id}</Text>
            <Text style={styles.status}>{STATUS_LABEL[order.status]}</Text>
          </View>
          <Text style={styles.title}>{order.title}</Text>
          <Text style={styles.amount}>¥{order.amount.toFixed(2)}</Text>
        </Pressable>
      ))}

      {orders.length === 0 ? (
        <View style={styles.empty}>
          <Text style={styles.emptyText}>当前筛选下暂无订单</Text>
        </View>
      ) : null}
    </PageShell>
  );
}

const styles = StyleSheet.create({
  metaBox: { marginBottom: 12 },
  meta: { fontSize: 13, color: '#666' },
  hint: { marginTop: 4, fontSize: 12, color: '#999' },
  filterRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 12,
  },
  chip: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    borderRadius: 16,
    backgroundColor: '#E8EBF0',
  },
  chipActive: { backgroundColor: '#1F6FEB' },
  chipText: { fontSize: 13, color: '#333' },
  chipTextActive: { color: '#FFF', fontWeight: '600' },
  card: {
    marginBottom: 10,
    padding: 14,
    borderRadius: 12,
    backgroundColor: '#FFFFFF',
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginBottom: 8,
  },
  orderId: { fontSize: 12, color: '#888' },
  status: { fontSize: 12, color: '#1F6FEB', fontWeight: '600' },
  title: { fontSize: 16, color: '#222', marginBottom: 6 },
  amount: { fontSize: 15, fontWeight: '700', color: '#111' },
  empty: { paddingVertical: 40, alignItems: 'center' },
  emptyText: { color: '#999' },
});

/**
 * 订单分包类型
 */
export type OrderStatus = 'pending' | 'paid' | 'shipped' | 'done';

export type OrderItem = {
  id: string;
  title: string;
  amount: number;
  status: OrderStatus;
};

export type OrderStackParamList = {
  OrderList: {
    userId?: string;
    initialStatus?: OrderStatus | 'all';
  };
  OrderDetail: {
    orderId: string;
  };
};

export const STATUS_LABEL: Record<OrderStatus, string> = {
  pending: '待支付',
  paid: '已支付',
  shipped: '配送中',
  done: '已完成',
};

export const MOCK_ORDERS: OrderItem[] = [
  { id: 'O20260716001', title: '春季外套', amount: 299, status: 'pending' },
  { id: 'O20260716002', title: '运动鞋', amount: 459, status: 'paid' },
  { id: 'O20260716003', title: '双肩包', amount: 189, status: 'shipped' },
  { id: 'O20260716004', title: '蓝牙耳机', amount: 129, status: 'done' },
];

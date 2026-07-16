/**
 * 业务分包：order
 * 入口注册 + 分包内路由（列表 → 详情）
 */
import { createPackageApp, registerPage } from '../_runtime';
import { OrderDetailScreen } from './OrderDetailScreen';
import { OrderListScreen } from './OrderListScreen';
import type { OrderStackParamList } from './types';

const OrderApp = createPackageApp<OrderStackParamList>({
  initialRouteName: 'OrderList',
  screens: [
    {
      name: 'OrderList',
      component: OrderListScreen,
      options: { title: '订单中心' },
    },
    {
      name: 'OrderDetail',
      component: OrderDetailScreen,
      options: { title: '订单详情' },
    },
  ],
});

registerPage({ key: 'order' }, OrderApp);

export default OrderApp;

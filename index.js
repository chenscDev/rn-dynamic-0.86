/**
 * @format
 * 基座默认入口（根目录保留，供 Metro / CLI 识别）
 * 业务分包入口：src/<key>/index.tsx
 */
import { AppRegistry } from 'react-native';
import App from './app/App';
import { name as appName } from './app/app.json';

AppRegistry.registerComponent(appName, () => App);

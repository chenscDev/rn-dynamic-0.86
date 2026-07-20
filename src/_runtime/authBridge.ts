/**
 * 原生登录态桥接（与 rn-biz packages/common 保持一致）
 */
import { NativeModules } from 'react-native';

type AuthHeaders = Record<string, string>;

type SessionInfo = {
  isLoggedIn: boolean;
  token?: string | null;
  userId?: string | null;
  nickname?: string | null;
};

const NativeAuth = NativeModules.RNAuthModule as
  | {
      getAuthHeaders: () => Promise<AuthHeaders>;
      getSession: () => Promise<SessionInfo>;
      getLoginReason: () => Promise<string | null>;
      login: (username: string, password: string) => Promise<SessionInfo>;
      changePassword: (oldPassword: string, newPassword: string) => Promise<void>;
      completeLoginNavigation: () => Promise<void>;
      openLogin: (reason?: string) => void;
      mockRequest: (path: string) => Promise<Record<string, unknown>>;
    }
  | undefined;

export async function getAuthHeaders(): Promise<AuthHeaders> {
  if (!NativeAuth?.getAuthHeaders) {
    return {};
  }
  return NativeAuth.getAuthHeaders();
}

export async function getSession(): Promise<SessionInfo> {
  if (!NativeAuth?.getSession) {
    return { isLoggedIn: false };
  }
  return NativeAuth.getSession();
}

export function openLogin(reason = 'session_expired'): void {
  NativeAuth?.openLogin?.(reason);
}

export async function getLoginReason(): Promise<string | null> {
  if (!NativeAuth?.getLoginReason) {
    return null;
  }
  return NativeAuth.getLoginReason();
}

export async function loginWithCredentials(
  username: string,
  password: string,
): Promise<SessionInfo> {
  if (!NativeAuth?.login) {
    throw new Error('RNAuthModule.login 不可用');
  }
  return NativeAuth.login(username, password);
}

export async function changePasswordWithBridge(
  oldPassword: string,
  newPassword: string,
): Promise<void> {
  if (!NativeAuth?.changePassword) {
    throw new Error('RNAuthModule.changePassword 不可用');
  }
  await NativeAuth.changePassword(oldPassword, newPassword);
}

export async function completeLoginNavigation(): Promise<void> {
  if (!NativeAuth?.completeLoginNavigation) {
    return;
  }
  await NativeAuth.completeLoginNavigation();
}

export async function authMockRequest<T extends Record<string, unknown>>(
  path: string,
): Promise<T> {
  if (!NativeAuth?.mockRequest) {
    throw new Error('RNAuthModule 不可用');
  }
  try {
    return (await NativeAuth.mockRequest(path)) as T;
  } catch (error: unknown) {
    const code =
      typeof error === 'object' && error !== null && 'code' in error
        ? String((error as { code?: string }).code)
        : '';
    if (code === 'UNAUTHORIZED') {
      openLogin('session_expired');
    }
    throw error;
  }
}

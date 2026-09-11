import type { Provider } from './types';

export const isTokenDanceProvider = (provider?: Provider | null) =>
  provider?.providerCode?.trim().toLowerCase() === 'tokendance';

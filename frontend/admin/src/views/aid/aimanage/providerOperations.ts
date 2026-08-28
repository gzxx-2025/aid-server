export interface ProviderOperationRequestScope {
  open: boolean;
  providerId: number | null;
}

export interface ProviderCapabilityScope {
  providerId: number;
  providerCode: string;
}

interface ProviderIdentity {
  id: number;
  providerCode?: string | null;
}

export function normalizeProviderCode(providerCode: string | null | undefined): string {
  return (providerCode || '').trim().toLowerCase();
}

export function createProviderCapabilityScope(provider: ProviderIdentity | null): ProviderCapabilityScope | null {
  if (!provider) return null;
  return { providerId: provider.id, providerCode: normalizeProviderCode(provider.providerCode) };
}

export function providerCapabilityScopeKey(scope: ProviderCapabilityScope | null): string {
  return scope ? `${scope.providerId}:${scope.providerCode}` : '';
}

export function ownsProviderCapabilities(
  scope: ProviderCapabilityScope | null,
  provider: ProviderIdentity | null
): boolean {
  if (!scope || !provider) return false;
  return providerCapabilityScopeKey(scope) === providerCapabilityScopeKey(createProviderCapabilityScope(provider));
}

export interface ProviderOperationRequestToken {
  epoch: number;
  providerId: number;
}

/** Prevents a stale provider request from updating the current modal state. */
export class ProviderOperationRequestGate {
  private epoch = 0;

  invalidate() {
    this.epoch += 1;
  }

  begin(providerId: number): ProviderOperationRequestToken {
    this.epoch += 1;
    return { epoch: this.epoch, providerId };
  }

  isCurrent(token: ProviderOperationRequestToken, scope: ProviderOperationRequestScope): boolean {
    return scope.open
      && scope.providerId === token.providerId
      && token.epoch === this.epoch;
  }
}

export interface ProviderTaskQuerySnapshot {
  startTime: number;
  endTime: number;
  limit: number;
  status?: string;
  productType?: string;
  searchType?: string;
  searchValue?: string;
}

interface ProviderTaskPayloadInput {
  cursor: string;
  exactSearch: string;
  searchType: string;
  snapshot: ProviderTaskQuerySnapshot;
}

/** Keeps cursor pages on the same upstream filter contract as their first page. */
export function buildProviderTaskPayload(input: ProviderTaskPayloadInput): Record<string, unknown> {
  const filters = {
    limit: input.snapshot.limit,
    status: input.snapshot.status,
    productType: input.snapshot.productType
  };
  if (input.cursor) {
    return { cursor: input.cursor, ...filters };
  }
  if (input.exactSearch) {
    return {
      ...filters,
      searchType: input.searchType,
      searchValue: input.exactSearch
    };
  }
  return {
    startTime: input.snapshot.startTime,
    endTime: input.snapshot.endTime,
    ...filters
  };
}

const TASK_SEARCH_TYPE_LABELS: Record<string, string> = {
  task_ids: '系统任务 ID',
  external_task_ids: '外部任务 ID'
};

export interface ProviderTaskSearchOption {
  value: string;
  label: string;
}

/** Builds the exact-search choices exposed by the selected provider only. */
export function buildProviderTaskSearchOptions(
  searchTypes: readonly string[] | null | undefined
): ProviderTaskSearchOption[] {
  const uniqueTypes = [...new Set((searchTypes || []).map((value) => value.trim()).filter(Boolean))];
  return uniqueTypes.map((value) => ({ value, label: TASK_SEARCH_TYPE_LABELS[value] || value }));
}

/** Keeps a supported selection, otherwise switches to the provider's first supported type. */
export function resolveProviderTaskSearchType(
  current: string,
  options: readonly ProviderTaskSearchOption[]
): string {
  return options.some((option) => option.value === current) ? current : (options[0]?.value || '');
}

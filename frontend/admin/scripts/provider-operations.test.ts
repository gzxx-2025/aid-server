import assert from 'node:assert/strict';
import {
  buildProviderTaskSearchOptions,
  buildProviderTaskPayload,
  createProviderCapabilityScope,
  ownsProviderCapabilities,
  providerCapabilityScopeKey,
  ProviderOperationRequestGate,
  resolveProviderTaskSearchType
} from '../src/views/aid/aimanage/providerOperations.ts';

const gate = new ProviderOperationRequestGate();
const oldProvider = gate.begin(1);
gate.invalidate();
const newProvider = gate.begin(2);

assert.equal(gate.isCurrent(oldProvider, { open: true, providerId: 2 }), false);
assert.equal(gate.isCurrent(newProvider, { open: true, providerId: 2 }), true);
assert.equal(gate.isCurrent(newProvider, { open: false, providerId: 2 }), false);

const superseded = gate.begin(2);
const latest = gate.begin(2);
assert.equal(gate.isCurrent(superseded, { open: true, providerId: 2 }), false);
assert.equal(gate.isCurrent(latest, { open: true, providerId: 2 }), true);

const raceGate = new ProviderOperationRequestGate();
const slowOldResponse = raceGate.begin(1);
raceGate.invalidate();
const fastNewResponse = raceGate.begin(2);
const applied: string[] = [];
if (raceGate.isCurrent(fastNewResponse, { open: true, providerId: 2 })) applied.push('new');
if (raceGate.isCurrent(slowOldResponse, { open: true, providerId: 2 })) applied.push('old');
assert.deepEqual(applied, ['new']);

const cursorPayload = buildProviderTaskPayload({
  cursor: 'page-2',
  exactSearch: 'task-1,task-2',
  searchType: 'task_ids',
  snapshot: {
    startTime: 1000,
    endTime: 2000,
    limit: 50,
    status: 'submitted,processing',
    productType: 'video'
  }
});
assert.deepEqual(cursorPayload, {
  cursor: 'page-2',
  limit: 50,
  status: 'submitted,processing',
  productType: 'video'
});
assert.equal('searchType' in cursorPayload, false);
assert.equal('searchValue' in cursorPayload, false);
assert.equal('startTime' in cursorPayload, false);
assert.equal('endTime' in cursorPayload, false);

const frozenSearchSnapshot = {
  startTime: 1000,
  endTime: 2000,
  limit: 20,
  status: 'submitted,processing',
  productType: 'video',
  searchType: 'task_ids',
  searchValue: 'frozen-task'
};
const firstSearchPayload = buildProviderTaskPayload({
  cursor: '',
  exactSearch: frozenSearchSnapshot.searchValue,
  searchType: frozenSearchSnapshot.searchType,
  snapshot: frozenSearchSnapshot
});
assert.deepEqual(firstSearchPayload, {
  limit: 20,
  status: 'submitted,processing',
  productType: 'video',
  searchType: 'task_ids',
  searchValue: 'frozen-task'
});

const minimaxSearchOptions = buildProviderTaskSearchOptions(['task_ids']);
assert.deepEqual(minimaxSearchOptions, [{ value: 'task_ids', label: '系统任务 ID' }]);
assert.equal(resolveProviderTaskSearchType('external_task_ids', minimaxSearchOptions), 'task_ids');
assert.equal(resolveProviderTaskSearchType('external_task_ids', []), '');

const klingSearchOptions = buildProviderTaskSearchOptions(['task_ids', 'external_task_ids']);
assert.equal(resolveProviderTaskSearchType('external_task_ids', klingSearchOptions), 'external_task_ids');
assert.deepEqual(buildProviderTaskSearchOptions([' task_ids ', 'task_ids']), minimaxSearchOptions);

const klingScope = createProviderCapabilityScope({ id: 21, providerCode: ' Kling ' });
assert.equal(providerCapabilityScopeKey(klingScope), '21:kling');
assert.equal(ownsProviderCapabilities(klingScope, { id: 21, providerCode: 'KLING' }), true);
assert.equal(ownsProviderCapabilities(klingScope, { id: 21, providerCode: 'vidu' }), false);
assert.equal(ownsProviderCapabilities(klingScope, { id: 22, providerCode: 'kling' }), false);
assert.equal(ownsProviderCapabilities(null, null), false);

console.log('provider operations request contract passed');

import assert from 'node:assert/strict';
import { normalizeRelativeEndpoint } from '../src/views/aid/aimanage/endpointPath.ts';

assert.equal(normalizeRelativeEndpoint('/api/v3/tasks', false), '/api/v3/tasks');
assert.equal(normalizeRelativeEndpoint('/tasks?task_ids=%s', true), '/tasks?task_ids=%s');
for (const invalid of ['https://evil.example/tasks', '//evil.example/tasks', '/api/../tasks',
  '/api/%2e%2e/tasks', '/api//tasks', '/tasks%0d%0aX-Test:true']) {
  assert.throws(() => normalizeRelativeEndpoint(invalid, false));
}
assert.throws(() => normalizeRelativeEndpoint('/tasks', true));
assert.throws(() => normalizeRelativeEndpoint('/tasks/%s/%s', true));

console.log('endpoint path tests passed');

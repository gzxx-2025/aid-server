import assert from 'node:assert/strict';
import {
  extractUnmanagedCapability,
  mergeManagedCapability
} from '../src/views/aid/aimanage/capabilityMerge.ts';

const original = {
  requiresConfiguredBilling: true,
  klingScenario: 'omni_edit',
  defaultAudio: false,
  audioModeOptions: ['off', 'original'],
  supportsElements: true,
  supportsVideoInput: true,
  referenceVideoRules: { maxVideoCharacterElements: 1 },
  sizeOptions: ['720P'],
  supportsAudio: true,
  sceneRules: {
    imageToVideo: { supportsAspectRatio: true },
    videoToVideo: { supportsAspectRatio: false }
  }
};

const preserved = extractUnmanagedCapability(original);
assert.equal(preserved.defaultAudio, false);
const roundTrip = mergeManagedCapability(preserved, {
  sizeOptions: ['1080P'],
  supportsAudio: false,
  sceneRules: { imageToVideo: { supportsAspectRatio: false } }
});

for (const key of [
  'requiresConfiguredBilling', 'klingScenario', 'defaultAudio', 'audioModeOptions',
  'supportsElements', 'supportsVideoInput', 'referenceVideoRules'
]) {
  assert.deepEqual(roundTrip[key], original[key], `unmanaged field lost: ${key}`);
}
assert.deepEqual(roundTrip.sceneRules.videoToVideo, original.sceneRules.videoToVideo);
assert.deepEqual(roundTrip.sizeOptions, ['1080P']);
assert.equal(roundTrip.supportsAudio, false);
assert.deepEqual(roundTrip.sceneRules.imageToVideo, { supportsAspectRatio: false });

console.log('capability round-trip contract passed');

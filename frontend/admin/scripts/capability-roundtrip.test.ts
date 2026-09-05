import assert from 'node:assert/strict';
import {
  buildInputSupportFields,
  extractUnmanagedCapability,
  mergeManagedCapability,
  parseInputModalities
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
assert.equal('supportsVideoInput' in preserved, false, 'managed field leaked into preserved capability');
const roundTrip = mergeManagedCapability(preserved, {
  sizeOptions: ['1080P'],
  supportsAudio: false,
  supportsVideoInput: false,
  sceneRules: { imageToVideo: { supportsAspectRatio: false } }
});

for (const key of [
  'requiresConfiguredBilling', 'klingScenario', 'defaultAudio', 'audioModeOptions',
  'supportsElements', 'referenceVideoRules'
]) {
  assert.deepEqual(roundTrip[key], original[key], `unmanaged field lost: ${key}`);
}
assert.equal(roundTrip.supportsVideoInput, false, 'managed field was not rebuilt from editor state');
assert.deepEqual(roundTrip.sceneRules.videoToVideo, original.sceneRules.videoToVideo);
assert.deepEqual(roundTrip.sizeOptions, ['1080P']);
assert.equal(roundTrip.supportsAudio, false);
assert.deepEqual(roundTrip.sceneRules.imageToVideo, { supportsAspectRatio: false });

const videoInputModalities = ['TEXT', 'VIDEO'];
const videoInputRoundTrip = mergeManagedCapability(extractUnmanagedCapability(original), {
  ...buildInputSupportFields(videoInputModalities)
});
assert.deepEqual(videoInputRoundTrip.inputModalities, ['TEXT', 'VIDEO']);
assert.equal(videoInputRoundTrip.supportsVideoInput, true, 'builder lost VIDEO input capability');

const legacyModalities = parseInputModalities({
  supportsImageInput: true,
  supportsVideoInput: true,
  supportsAudioInput: true,
  supportsDocumentInput: true
});
assert.deepEqual(legacyModalities, ['TEXT', 'IMAGE', 'VIDEO', 'AUDIO', 'DOCUMENT']);
const legacyRoundTrip = mergeManagedCapability(extractUnmanagedCapability(original), {
  ...buildInputSupportFields(legacyModalities)
});
for (const field of [
  'supportsImageInput',
  'supportsVideoInput',
  'supportsAudioInput',
  'supportsDocumentInput'
]) {
  assert.equal(legacyRoundTrip[field], true, `legacy modality lost during round-trip: ${field}`);
}

console.log('capability round-trip contract passed');

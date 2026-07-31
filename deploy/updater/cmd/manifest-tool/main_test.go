package main

import (
	"encoding/base64"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestSignedManifestCarriesVerifiedPayload(t *testing.T) {
	dir := t.TempDir()
	keyPath := filepath.Join(dir, "manifest.key")
	manifestPath := filepath.Join(dir, "latest.json")
	if err := generateKey(keyPath); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(manifestPath, []byte(`{"schemaVersion":2,"productVersion":"1.0.0","beta":{"productVersion":"1.1.0-rc.1"}}`), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := signManifest(keyPath, manifestPath); err != nil {
		t.Fatal(err)
	}
	if err := verifyManifest(keyPath, manifestPath); err != nil {
		t.Fatalf("signed manifest should verify: %v", err)
	}

	raw, err := os.ReadFile(manifestPath)
	if err != nil {
		t.Fatal(err)
	}
	var document map[string]any
	if err := json.Unmarshal(raw, &document); err != nil {
		t.Fatal(err)
	}
	signature := document["signature"].(map[string]any)
	payload, err := base64.StdEncoding.DecodeString(signature["payload"].(string))
	if err != nil {
		t.Fatal(err)
	}
	var payloadDocument map[string]any
	if err := json.Unmarshal(payload, &payloadDocument); err != nil {
		t.Fatal(err)
	}
	if payloadDocument["productVersion"] != "1.0.0" {
		t.Fatal("signature payload does not contain the manifest data")
	}

	payloadDocument["productVersion"] = "9.9.9"
	tamperedPayload, _ := json.Marshal(payloadDocument)
	signature["payload"] = base64.StdEncoding.EncodeToString(tamperedPayload)
	tampered, _ := json.Marshal(document)
	if err := os.WriteFile(manifestPath, tampered, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := verifyManifest(keyPath, manifestPath); err == nil {
		t.Fatal("tampered payload should fail verification")
	}
}

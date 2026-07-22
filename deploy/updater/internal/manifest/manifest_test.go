package manifest

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"testing"
)

func TestSecureURL(t *testing.T) {
	for _, raw := range []string{"http://example.com/latest.json", "https://user:pass@example.com/latest.json", "https:///latest.json"} {
		if isSecureURL(raw) {
			t.Fatalf("expected URL to be rejected: %s", raw)
		}
	}
	if !isSecureURL("https://example.com/latest.json") {
		t.Fatal("expected HTTPS URL to be accepted")
	}
}

func TestVerifySignedManifest(t *testing.T) {
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	previous := trustedPublicKey
	trustedPublicKey = base64.StdEncoding.EncodeToString(publicKey)
	defer func() { trustedPublicKey = previous }()

	document := map[string]any{"productVersion": "1.2.3", "packageUrl": "https://example.com/pkg"}
	canonical, _ := json.Marshal(document)
	document["signature"] = map[string]any{
		"algorithm": "Ed25519",
		"value":     base64.StdEncoding.EncodeToString(ed25519.Sign(privateKey, canonical)),
	}
	raw, _ := json.Marshal(document)
	if err := Verify(raw); err != nil {
		t.Fatalf("expected valid signature: %v", err)
	}
	document["productVersion"] = "9.9.9"
	tampered, _ := json.Marshal(document)
	if err := Verify(tampered); err == nil {
		t.Fatal("expected tampered manifest to fail verification")
	}
}

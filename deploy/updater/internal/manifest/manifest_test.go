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

func TestProductPackageMirrorsSupportsNewAndLegacyManifest(t *testing.T) {
	m := &Manifest{
		ProductVersion: "1.2.3",
		PackageURL:     "https://primary.example/pkg",
		PackageMirrors: []string{"https://mirror.example/pkg"},
		PackageSHA256:  "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
	}
	mirrors, ok := m.ProductPackageMirrors("1.2.3", m.PackageURL, m.PackageSHA256)
	if !ok || len(mirrors) != 1 || mirrors[0] != "https://mirror.example/pkg" {
		t.Fatalf("unexpected mirrors: %#v, matched=%v", mirrors, ok)
	}

	m.PackageMirrors = nil
	mirrors, ok = m.ProductPackageMirrors("1.2.3", m.PackageURL, m.PackageSHA256)
	if !ok || len(mirrors) != 0 {
		t.Fatalf("legacy manifest should match without mirrors: %#v, matched=%v", mirrors, ok)
	}
}

func TestMatchProductVersionSupportsStableAndBeta(t *testing.T) {
	m := &Manifest{
		ProductVersion: "1.0.0",
		SourceBuild:    true,
		Beta:           &ChannelRelease{ProductVersion: "1.1.0-beta.1", SourceBuild: true},
	}
	if !m.MatchProductVersion("1.0.0") {
		t.Fatal("expected stable version to match")
	}
	if !m.MatchProductVersion("1.1.0-beta.1") {
		t.Fatal("expected beta version to match")
	}
	if m.MatchProductVersion("1.2.0") || m.MatchProductVersion(" ") {
		t.Fatal("unexpected unsigned version match")
	}
	if !m.MatchSourceBuildVersion("1.0.0") || !m.MatchSourceBuildVersion("1.1.0-beta.1") {
		t.Fatal("expected source-build versions to match")
	}
	m.Beta.SourceBuild = false
	if m.MatchSourceBuildVersion("1.1.0-beta.1") || m.MatchSourceBuildVersion("1.2.0") {
		t.Fatal("unexpected source-build version match")
	}
}

func TestSelectSourceBuilderForVersion(t *testing.T) {
	stableBuilder := &SourceBuilderArtifact{
		URL:        "https://gitee.example/project/raw/v1.0.0/builder.sh",
		Mirrors:    []string{"https://github.example/project/raw/v1.0.0/builder.sh"},
		SHA256:     "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
		Capability: SourceBuilderCapability,
	}
	betaBuilder := &SourceBuilderArtifact{
		URL:        "https://gitee.example/project/raw/v1.1.0-beta.1/builder.sh",
		SHA256:     "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
		Capability: SourceBuilderCapability,
	}
	m := &Manifest{
		ProductVersion: "1.0.0",
		SourceBuild:    true,
		SourceBuilder:  stableBuilder,
		Beta: &ChannelRelease{
			ProductVersion: "1.1.0-beta.1",
			SourceBuild:    true,
			SourceBuilder:  betaBuilder,
		},
	}

	selected, err := m.SelectSourceBuilderForVersion("1.1.0-beta.1")
	if err != nil {
		t.Fatalf("expected beta builder: %v", err)
	}
	if selected.URL != betaBuilder.URL || selected == betaBuilder {
		t.Fatalf("unexpected or non-copied builder: %#v", selected)
	}

	m.Beta.SourceBuilder = nil
	if _, err := m.SelectSourceBuilderForVersion("1.1.0-beta.1"); err == nil {
		t.Fatal("source-build release without signed builder unexpectedly accepted")
	}
	m.SourceBuilder.Capability = "legacy"
	if _, err := m.SelectSourceBuilderForVersion("1.0.0"); err == nil {
		t.Fatal("legacy builder capability unexpectedly accepted")
	}
	m.SourceBuilder.Capability = SourceBuilderCapability
	m.SourceBuilder.URL = "http://insecure.example/builder.sh"
	if _, err := m.SelectSourceBuilderForVersion("1.0.0"); err == nil {
		t.Fatal("insecure builder URL unexpectedly accepted")
	}
}

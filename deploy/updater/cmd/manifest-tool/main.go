package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"strings"
)

func main() {
	mode := flag.String("mode", "", "keygen, public, sign or verify")
	keyPath := flag.String("key", "", "private key file")
	manifestPath := flag.String("manifest", "", "manifest JSON file")
	flag.Parse()

	var err error
	switch *mode {
	case "keygen":
		err = generateKey(*keyPath)
	case "public":
		err = printPublicKey(*keyPath)
	case "sign":
		err = signManifest(*keyPath, *manifestPath)
	case "verify":
		err = verifyManifest(*keyPath, *manifestPath)
	default:
		err = fmt.Errorf("mode must be keygen, public, sign or verify")
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func verifyManifest(keyPath, manifestPath string) error {
	privateKey, err := loadPrivateKey(keyPath)
	if err != nil {
		return err
	}
	raw, err := os.ReadFile(manifestPath)
	if err != nil {
		return err
	}
	var document map[string]any
	if err := json.Unmarshal(raw, &document); err != nil {
		return err
	}
	signatureObject, ok := document["signature"].(map[string]any)
	if !ok || signatureObject["algorithm"] != "Ed25519" {
		return fmt.Errorf("manifest signature is missing")
	}
	signatureText, _ := signatureObject["value"].(string)
	signature, err := base64.StdEncoding.DecodeString(signatureText)
	if err != nil {
		return err
	}
	delete(document, "signature")
	canonical, err := json.Marshal(document)
	if err != nil {
		return err
	}
	if !ed25519.Verify(privateKey.Public().(ed25519.PublicKey), canonical, signature) {
		return fmt.Errorf("manifest signature verification failed")
	}
	return nil
}

func generateKey(path string) error {
	if strings.TrimSpace(path) == "" {
		return fmt.Errorf("private key path is required")
	}
	if _, err := os.Stat(path); err == nil {
		return fmt.Errorf("private key already exists: %s", path)
	}
	publicKey, privateKey, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return err
	}
	if err := os.WriteFile(path, []byte(base64.StdEncoding.EncodeToString(privateKey)), 0o600); err != nil {
		return err
	}
	fmt.Println(base64.StdEncoding.EncodeToString(publicKey))
	return nil
}

func loadPrivateKey(path string) (ed25519.PrivateKey, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(string(raw)))
	if err != nil || len(decoded) != ed25519.PrivateKeySize {
		return nil, fmt.Errorf("invalid Ed25519 private key")
	}
	return ed25519.PrivateKey(decoded), nil
}

func printPublicKey(path string) error {
	privateKey, err := loadPrivateKey(path)
	if err != nil {
		return err
	}
	fmt.Println(base64.StdEncoding.EncodeToString(privateKey.Public().(ed25519.PublicKey)))
	return nil
}

func signManifest(keyPath, manifestPath string) error {
	privateKey, err := loadPrivateKey(keyPath)
	if err != nil {
		return err
	}
	raw, err := os.ReadFile(manifestPath)
	if err != nil {
		return err
	}
	var document map[string]any
	if err := json.Unmarshal(raw, &document); err != nil {
		return err
	}
	delete(document, "signature")
	canonical, err := json.Marshal(document)
	if err != nil {
		return err
	}
	document["signature"] = map[string]any{
		"algorithm": "Ed25519",
		"value":     base64.StdEncoding.EncodeToString(ed25519.Sign(privateKey, canonical)),
	}
	signed, err := json.MarshalIndent(document, "", "  ")
	if err != nil {
		return err
	}
	return os.WriteFile(manifestPath, signed, 0o644)
}

# PHONCOIN Wallet

Official Android wallet for the PHONCOIN mobile-native blockchain.

---

## 📦 Download

👉 **Latest signed APK release:**
https://github.com/Phoncoin/phoncoin-wallet/releases/latest

All production builds are cryptographically signed.

---

## 🔐 Security & Verification

Each release includes:

• Signed production APK  
• SHA256 checksum  
• Public signature fingerprint  

### Verify APK integrity (Windows)

```
certutil -hashfile PHONCOIN-0.5.0.apk SHA256
```

Compare the output with the SHA256 value published in the release page.

---

## 🔏 Signature Information

Release builds are signed using the PHONCOIN production key.

Signature scheme:
- APK Signature Scheme v2

Users can verify signatures with:

```
apksigner verify --verbose --print-certs PHONCOIN-0.5.0.apk
```

---

## 🌐 About PHONCOIN

PHONCOIN is a mobile-native blockchain secured by real smartphones via Proof-of-Phone Secure (PoP-S4).

Core principles:

• 1 real device = 1 participant  
• Energy-efficient consensus  
• Mobile-first architecture  
• Verifiable public releases  

---

## 🧠 Transparency

• Reference node implementation:
https://github.com/Phoncoin/phonchain-node

• Canonical protocol documentation:
https://github.com/Phoncoin/phoncoin

• Official website:
https://phoncoin.org

---

## ⚠ Important

• Never download APK files from unofficial sources  
• Always verify SHA256 before installation  
• The keystore is kept offline and never published  

---

PHONCOIN — Mobile-native security layer.

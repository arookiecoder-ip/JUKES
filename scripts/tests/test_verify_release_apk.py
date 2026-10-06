import unittest
from scripts.verify_release_apk import verify_pinned_certificate

PIN = "f95f9dc705dc7511be87c38e83f8f0aaf71fae61ad2541fbf864fe2ad2f4923c"


class SigningCertificateTests(unittest.TestCase):
    def test_v2_signer_label(self):
        verify_pinned_certificate(f"Signer #1 certificate SHA-256 digest: {PIN}\n", PIN)

    def test_verbose_v3_signer_label_with_sdk_bounds(self):
        verify_pinned_certificate(
            f"Signer (minSdkVersion=28, maxSdkVersion=2147483647) certificate SHA-256 digest: {PIN}\n", PIN)

    def test_sdk37_scheme_prefixed_labels(self):
        for label in ["V1 Signer:", "V2 Signer:", "V3.0 Signer:", "V3.1 Signer:", "V3.2 Hybrid Classical Signer:"]:
            with self.subTest(label=label):
                verify_pinned_certificate(f"{label} certificate SHA-256 digest: {PIN}\n", PIN)

    def test_same_certificate_in_multiple_signature_schemes(self):
        verify_pinned_certificate(
            f"Signer #1 certificate SHA-256 digest: {PIN}\n"
            f"Signer (minSdkVersion=28, maxSdkVersion=2147483647) certificate SHA-256 digest: {PIN}\n", PIN)

    def test_case_and_colon_format_do_not_change_certificate_identity(self):
        colon_digest = ":".join(PIN[i:i+2] for i in range(0, len(PIN), 2)).upper()
        verify_pinned_certificate(f"Signer #1 certificate SHA-256 digest: {colon_digest}\r\n", PIN)

    def test_different_key_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_pinned_certificate(f"Signer #1 certificate SHA-256 digest: {'0' * 64}\n", PIN)

    def test_unexpected_additional_signer_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_pinned_certificate(
                f"Signer #1 certificate SHA-256 digest: {PIN}\n"
                f"Signer #2 certificate SHA-256 digest: {'0' * 64}\n", PIN)

    def test_source_stamp_cannot_substitute_for_apk_signer(self):
        with self.assertRaises(ValueError):
            verify_pinned_certificate(f"Source Stamp Signer certificate SHA-256 digest: {PIN}\n", PIN)

    def test_public_key_hash_cannot_substitute_for_certificate(self):
        with self.assertRaises(ValueError):
            verify_pinned_certificate(f"Signer #1 public key SHA-256 digest: {PIN}\n", PIN)

    def test_missing_certificate_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_pinned_certificate("Verifies\nNumber of signers: 1\n", PIN)

    def test_invalid_pin_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_pinned_certificate(f"Signer #1 certificate SHA-256 digest: {PIN}\n", "invalid")

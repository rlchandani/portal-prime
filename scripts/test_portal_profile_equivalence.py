#!/usr/bin/env python3
# Copyright (c) 2026 Starbright Lab.
#
# This source code is licensed under the MIT license found in the
# LICENSE file in the root directory of this source tree.
"""Pure contract tests for canonical ART inventory equivalence.

Raw TSV bytes retain filename-sensitive profile_key aliases. The mapper has
already normalized those aliases in the canonical rows supplied to this API.
No SDK, captured evidence, files, subprocesses, or devices are needed here.
"""
import copy
import unittest

from portal_profile_validation import ValidationError, assert_profile_inventory_equivalent


APK_SHA256 = "a" * 64
COUNTS = {"methods": 3, "classes": 3, "appMethods": 2, "appClasses": 2}
METHOD_KEY = ("classes.dex", 7)
CLASS_KEY = ("classes.dex", 1)


def audit_fixture(kind, raw_digest="b"):
    """Hand-counted fixture: two hot app methods and one startup library method."""
    return (
        {
            "apkSha256": APK_SHA256,
            "decoder_status": "ok",
            "dex_mapping_status": "validated",
            "profileInput": {"kind": kind, "version": "010"},
            "profile_method_entries": 3,
            "profile_class_entries": 3,
            "profileMethodFlagCounts": {"H": 2, "S": 2, "P": 1},
            "appMethodFlagCounts": {"H": 2, "S": 1, "P": 1},
        },
        {"classes.dex": ("1234abcd", 128), "classes2.dex": ("5678ef01", 64)},
        {
            METHOD_KEY: ("Lcom/immortal/launcher/HomeActivity;", "onCreate",
                         "(Landroid/os/Bundle;)V", 3, "HS", True, True),
            ("classes.dex", 9): ("Lcom/immortal/launcher/Clock;", "tick",
                                 "()V", 5, "HP", True, True),
            ("classes2.dex", 2): ("Landroidx/compose/Runtime;", "start",
                                  "()V", 2, "S", True, False),
        },
        {
            CLASS_KEY: ("Lcom/immortal/launcher/HomeActivity;", True, True),
            ("classes.dex", 2): ("Lcom/immortal/launcher/Clock;", True, True),
            ("classes2.dex", 3): ("Landroidx/compose/Runtime;", True, False),
        },
        {"all-profile-methods.tsv": raw_digest * 64,
         "all-profile-classes.tsv": raw_digest * 64},
    )


class ProfileInventoryEquivalenceTest(unittest.TestCase):
    def setUp(self):
        self.bundled = audit_fixture("apk_bundled_profile")
        self.reference = audit_fixture("provided_art_profile", "c")

    def pair(self):
        return copy.deepcopy((self.bundled, self.reference))

    def assert_rejected(self, bundled, reference, **kwargs):
        with self.assertRaises(ValidationError):
            assert_profile_inventory_equivalent(bundled, reference, APK_SHA256, **kwargs)

    def test_equal_canonical_rows_accept_different_valid_raw_hashes(self):
        self.assertNotEqual(self.bundled[4], self.reference[4])
        self.assertEqual(COUNTS, assert_profile_inventory_equivalent(
            self.bundled, self.reference, APK_SHA256))

    def test_matching_raw_hashes_also_accept(self):
        self.reference[4].update(self.bundled[4])
        self.assertEqual(COUNTS, assert_profile_inventory_equivalent(
            self.bundled, self.reference, APK_SHA256))

    def test_inventory_dictionary_order_is_irrelevant(self):
        reference = list(self.reference)
        for index in (1, 2, 3):
            reference[index] = dict(reversed(list(reference[index].items())))
        self.assertEqual(COUNTS, assert_profile_inventory_equivalent(
            self.bundled, tuple(reference), APK_SHA256))

    def test_exact_expected_counts_accept_and_inputs_are_not_mutated(self):
        expected = dict(COUNTS)
        before = copy.deepcopy((self.bundled, self.reference, expected))
        self.assertEqual(COUNTS, assert_profile_inventory_equivalent(
            self.bundled, self.reference, APK_SHA256, expected_counts=expected))
        self.assertEqual(before, (self.bundled, self.reference, expected))

    def test_each_method_identity_and_flag_field_is_compared(self):
        for side in (0, 1):
            for field, value in ((0, "Lcom/immortal/launcher/Other;"),
                                 (1, "onStart"), (2, "()I"), (4, "HPS")):
                with self.subTest(side=side, field=field):
                    pair = self.pair()
                    row = list(pair[side][2][METHOD_KEY])
                    row[field] = value
                    pair[side][2][METHOD_KEY] = tuple(row)
                    self.assert_rejected(*pair)

    def test_self_consistent_changed_method_flags_still_reject(self):
        for side in (0, 1):
            with self.subTest(side=side):
                pair = self.pair()
                row = list(pair[side][2][METHOD_KEY])
                row[3:5] = [1, "H"]
                pair[side][2][METHOD_KEY] = tuple(row)
                pair[side][0]["profileMethodFlagCounts"]["S"] = 1
                pair[side][0]["appMethodFlagCounts"]["S"] = 0
                self.assert_rejected(*pair)

    def test_changed_class_descriptor_or_app_membership_rejects(self):
        for side in (0, 1):
            for row in (("Lcom/immortal/launcher/Other;", True, True),
                        ("Lcom/immortal/launcher/HomeActivity;", True, False)):
                with self.subTest(side=side, row=row):
                    pair = self.pair()
                    pair[side][3][CLASS_KEY] = row
                    self.assert_rejected(*pair)

    def test_removed_method_with_updated_proof_count_rejects(self):
        pair = self.pair()
        del pair[1][2][("classes2.dex", 2)]
        pair[1][0]["profile_method_entries"] = 2
        pair[1][0]["profileMethodFlagCounts"]["S"] = 1
        self.assert_rejected(*pair)

    def test_removed_class_with_updated_proof_count_rejects(self):
        pair = self.pair()
        del pair[1][3][("classes2.dex", 3)]
        pair[1][0]["profile_class_entries"] = 2
        self.assert_rejected(*pair)

    def test_changed_dex_crc_count_or_name_rejects(self):
        for side in (0, 1):
            for crc in ({"classes.dex": ("1234abce", 128),
                         "classes2.dex": ("5678ef01", 64)},
                        {"classes.dex": ("1234abcd", 129),
                         "classes2.dex": ("5678ef01", 64)},
                        {"renamed.dex": ("1234abcd", 128),
                         "classes2.dex": ("5678ef01", 64)}):
                with self.subTest(side=side, crc=crc):
                    pair = list(self.pair())
                    audit = list(pair[side])
                    audit[1] = crc
                    pair[side] = tuple(audit)
                    self.assert_rejected(*pair)

    def test_wrong_apk_or_decoder_mapping_proof_rejects_on_either_side(self):
        for side in (0, 1):
            for key, value in (("apkSha256", "d" * 64),
                               ("decoder_status", "failed"),
                               ("dex_mapping_status", "unvalidated")):
                with self.subTest(side=side, key=key):
                    pair = self.pair()
                    pair[side][0][key] = value
                    self.assert_rejected(*pair)

    def test_bad_expected_apk_hash_rejects(self):
        for digest in (None, "", "a" * 63, "A" * 64, "z" * 64, "d" * 64):
            with self.subTest(digest=digest):
                with self.assertRaises(ValidationError):
                    assert_profile_inventory_equivalent(self.bundled, self.reference, digest)

    def test_input_kind_and_version_are_bound_to_the_correct_side(self):
        for side in (0, 1):
            wrong_kind = "provided_art_profile" if side == 0 else "apk_bundled_profile"
            for key, value in (("kind", wrong_kind), ("kind", "unknown"),
                               ("version", "015"), ("version", 10), ("version", None)):
                with self.subTest(side=side, key=key, value=value):
                    pair = self.pair()
                    pair[side][0]["profileInput"][key] = value
                    self.assert_rejected(*pair)

    def test_proof_entry_counts_must_match_and_be_strict_integers(self):
        for side in (0, 1):
            for key in ("profile_method_entries", "profile_class_entries"):
                for value in (0, 2, 4, "3", 3.0, True, None):
                    with self.subTest(side=side, key=key, value=value):
                        pair = self.pair()
                        pair[side][0][key] = value
                        self.assert_rejected(*pair)

    def test_each_profile_and_app_flag_count_must_match_rows(self):
        for side in (0, 1):
            for key in ("profileMethodFlagCounts", "appMethodFlagCounts"):
                for flag in "HSP":
                    with self.subTest(side=side, key=key, flag=flag):
                        pair = self.pair()
                        pair[side][0][key][flag] += 1
                        self.assert_rejected(*pair)

    def test_proof_flag_counts_reject_non_integer_values(self):
        for key in ("profileMethodFlagCounts", "appMethodFlagCounts"):
            for value in (True, 1.0, "1"):
                with self.subTest(key=key, value=value):
                    pair = self.pair()
                    pair[1][0][key]["P"] = value
                    self.assert_rejected(*pair)

    def test_missing_required_proof_fields_reject(self):
        for key in ("apkSha256", "decoder_status", "dex_mapping_status", "profileInput",
                    "profile_method_entries", "profile_class_entries",
                    "profileMethodFlagCounts", "appMethodFlagCounts"):
            with self.subTest(key=key):
                pair = self.pair()
                del pair[1][0][key]
                self.assert_rejected(*pair)

    def test_malformed_outer_audit_rejects(self):
        for malformed in (None, {}, (), self.reference[:4], self.reference + ({},)):
            for side in (0, 1):
                with self.subTest(side=side, malformed=malformed):
                    pair = list(self.pair())
                    pair[side] = malformed
                    self.assert_rejected(*pair)

    def test_empty_or_wrong_type_inventory_components_reject(self):
        for index in (0, 1, 2, 3, 4):
            for value in ({}, [], None):
                with self.subTest(index=index, value=value):
                    reference = list(copy.deepcopy(self.reference))
                    reference[index] = value
                    self.assert_rejected(self.bundled, tuple(reference))

    def test_raw_hashes_require_exact_keys_and_lowercase_sha256(self):
        for side in (0, 1):
            for key in ("all-profile-methods.tsv", "all-profile-classes.tsv"):
                for value in ("a" * 63, "a" * 65, "A" * 64, "g" * 64, "a" * 64 + "\n", None, 0):
                    with self.subTest(side=side, key=key, value=value):
                        pair = self.pair()
                        pair[side][4][key] = value
                        self.assert_rejected(*pair)
                with self.subTest(side=side, missing=key):
                    pair = self.pair()
                    del pair[side][4][key]
                    self.assert_rejected(*pair)
            with self.subTest(side=side, extra=True):
                pair = self.pair()
                pair[side][4]["extra.tsv"] = "a" * 64
                self.assert_rejected(*pair)

    def test_malformed_method_and_class_tuple_shapes_reject(self):
        for side in (0, 1):
            for index, key in ((2, METHOD_KEY), (3, CLASS_KEY)):
                original = self.bundled[index][key]
                for row in (original[:-1], original + (True,), list(original), None):
                    with self.subTest(side=side, index=index, row=row):
                        pair = self.pair()
                        pair[side][index][key] = row
                        self.assert_rejected(*pair)

    def test_undefined_or_non_boolean_row_metadata_rejects(self):
        for side in (0, 1):
            for index, key, defined, app in ((2, METHOD_KEY, 5, 6), (3, CLASS_KEY, 1, 2)):
                for field, value in ((defined, False), (defined, 1), (defined, "true"),
                                     (app, 1), (app, "true"), (app, None)):
                    with self.subTest(side=side, index=index, field=field, value=value):
                        pair = self.pair()
                        row = list(pair[side][index][key])
                        row[field] = value
                        pair[side][index][key] = tuple(row)
                        self.assert_rejected(*pair)

    def test_malformed_crc_rows_reject_even_when_both_inventories_match(self):
        for row in (("short", 128), ("zzzzzzzz", 128),
                    ("1234abcd", -1), ("1234abcd", True), ("1234abcd", "128"),
                    ("1234abcd",), ("1234abcd", 128, 0), ["1234abcd", 128], None):
            with self.subTest(row=row):
                pair = self.pair()
                for audit in pair:
                    audit[1]["classes.dex"] = row
                self.assert_rejected(*pair)

    def test_identical_malformed_method_fields_reject_on_both_sides(self):
        cases = ((4, None), (4, "HP"), (3, True), (3, 0), (3, 8),
                 (3, 3.0), (0, ""), (1, ""), (2, ""))
        for field, value in cases:
            with self.subTest(field=field, value=value):
                pair = self.pair()
                for audit in pair:
                    row = list(audit[2][METHOD_KEY])
                    row[field] = value
                    audit[2][METHOD_KEY] = tuple(row)
                self.assert_rejected(*pair)

    def test_no_application_methods_rejects_even_with_consistent_proof(self):
        pair = self.pair()
        for audit in pair:
            for key, row in audit[2].items():
                audit[2][key] = row[:-1] + (False,)
            audit[0]["appMethodFlagCounts"] = {"H": 0, "S": 0, "P": 0}
        self.assert_rejected(*pair)

    def test_all_application_methods_must_be_hot(self):
        pair = self.pair()
        for audit in pair:
            row = list(audit[2][METHOD_KEY])
            row[3:5] = [2, "S"]
            audit[2][METHOD_KEY] = tuple(row)
            audit[0]["profileMethodFlagCounts"]["H"] = 1
            audit[0]["appMethodFlagCounts"]["H"] = 1
        self.assert_rejected(*pair)

    def test_each_wrong_expected_count_rejects(self):
        for key in COUNTS:
            with self.subTest(key=key):
                expected = dict(COUNTS)
                expected[key] += 1
                self.assert_rejected(self.bundled, self.reference, expected_counts=expected)

    def test_expected_counts_require_exact_keys_and_strict_nonnegative_integers(self):
        for expected in ({}, [], "3", {**COUNTS, "extra": 0},
                         {key: value for key, value in COUNTS.items() if key != "appClasses"}):
            with self.subTest(expected=expected):
                self.assert_rejected(self.bundled, self.reference, expected_counts=expected)
        for key in COUNTS:
            for value in (-1, True, float(COUNTS[key]), str(COUNTS[key]), None):
                with self.subTest(key=key, value=value):
                    expected = dict(COUNTS)
                    expected[key] = value
                    self.assert_rejected(self.bundled, self.reference, expected_counts=expected)


if __name__ == "__main__":
    unittest.main()

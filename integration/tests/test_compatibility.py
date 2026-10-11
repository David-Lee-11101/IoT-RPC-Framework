import json
import re
import sys
import unittest
from pathlib import Path


INTEGRATION_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(INTEGRATION_ROOT))

from compatibility import (  # noqa: E402
    _json_stdout,
    build_capability_matrix,
    canonical_json_bytes,
    fixture_sha256,
    normalize_analyzer,
    normalize_composer,
    normalize_scheduler,
    parse_args,
    validate_fixture,
    validate_manifest,
)


class CompatibilityContractTest(unittest.TestCase):
    def setUp(self):
        self.fixture = json.loads(
            (INTEGRATION_ROOT / "contracts" / "v0" / "rulespec.fixture.json").read_text(encoding="utf-8")
        )
        self.manifest = json.loads(
            (INTEGRATION_ROOT / "repositories.json").read_text(encoding="utf-8")
        )

    def test_fixture_is_valid_unique_and_canonical(self):
        schema = json.loads(
            (INTEGRATION_ROOT / "contracts" / "v0" / "rulespec.schema.json").read_text(encoding="utf-8")
        )
        self.assertEqual("urn:smart-rule:rulespec:v0", schema["$id"])
        self.assertEqual(sorted(["actions", "conditions", "description", "id", "triggers"]),
                         sorted(schema["items"]["required"]))
        self.assertFalse(schema["items"]["additionalProperties"])
        validate_fixture(self.fixture)
        self.assertEqual(3, len(self.fixture))
        self.assertEqual(3, len({rule["id"] for rule in self.fixture}))
        self.assertEqual(
            canonical_json_bytes(self.fixture),
            canonical_json_bytes(json.loads(canonical_json_bytes(self.fixture))),
        )
        self.assertRegex(fixture_sha256(self.fixture), r"^[0-9a-f]{64}$")

    def test_cli_defaults_are_repository_portable(self):
        args = parse_args([])
        self.assertIsNone(args.java_home)
        self.assertEqual(INTEGRATION_ROOT / "maven-settings.xml", Path(args.maven_settings))

    def test_maven_json_extraction_is_narrow_and_fails_without_json(self):
        self.assertEqual({"status": "ok"}, _json_stdout("[INFO] build output\n{\"status\":\"ok\"}\n"))
        with self.assertRaisesRegex(ValueError, "did not contain JSON"):
            _json_stdout("[INFO] build output only")

    def test_scheduler_normalization_states_unapplied_conditions(self):
        normalized = normalize_scheduler(
            {
                "mode": "deterministic-simulation",
                "ruleCount": 3,
                "completedRuleIds": ["notify-cooling", "warm-office", "cool-office"],
                "conditionsApplied": False,
            },
            self.fixture,
        )
        self.assertEqual(["cool-office", "notify-cooling", "warm-office"], normalized["completedRuleIds"])
        self.assertFalse(normalized["conditionsApplied"])

    def test_analyzer_normalization_preserves_ordered_semantics(self):
        normalized = normalize_analyzer(
            {
                "rule_count": 3,
                "conflicts": [{"left": "cool-office", "right": "warm-office", "devices": ["office-hvac"]}],
                "dependencies": [
                    {"source": "cool-office", "target": "warm-office", "devices": ["office-hvac"]},
                    {"source": "cool-office", "target": "notify-cooling", "devices": ["office-hvac"]},
                    {"source": "warm-office", "target": "notify-cooling", "devices": ["office-hvac"]},
                ],
            },
            self.fixture,
        )
        self.assertEqual(1, len(normalized["conflicts"]))
        self.assertEqual(3, len(normalized["dependencies"]))

    def test_composer_normalization_requires_schema_and_cardinality(self):
        rule = self.fixture[0]
        composed = dict(rule)
        composed["schema"] = "rule-spec/v0"
        self.assertEqual(rule, normalize_composer(composed, rule)["rule"])

        invalid = dict(composed)
        invalid["actions"] = []
        with self.assertRaisesRegex(ValueError, "exactly one action"):
            normalize_composer(invalid, rule)

    def test_manifest_supports_pending_and_finalized_pins_without_self_pin(self):
        validate_manifest(self.manifest)
        self.assertEqual("invocation", self.manifest["anchor"]["commitMode"])
        self.assertNotIn("commit", self.manifest["anchor"])
        for repository in self.manifest["siblings"]:
            self.assertFalse(repository["publicationPending"])
            self.assertTrue(re.fullmatch(r"[0-9a-f]{40}", repository["commit"]))

        pending = json.loads(json.dumps(self.manifest))
        for repository in pending["siblings"]:
            repository["publicationPending"] = True
        validate_manifest(pending)

        finalized = json.loads(json.dumps(pending))
        for repository in finalized["siblings"]:
            repository["publicationPending"] = False
        validate_manifest(finalized)

    def test_capability_matrix_is_explicit_about_adapter_boundaries(self):
        matrix = build_capability_matrix()
        by_id = {row["id"]: row for row in matrix}
        self.assertFalse(by_id["scheduler"]["conditionsEvaluated"])
        self.assertEqual("ordered-write-conflict-and-dependency-analysis", by_id["analyzer"]["semantics"])
        self.assertEqual({"triggers": "1", "conditions": "0..1", "actions": "1"}, by_id["composer"]["cardinality"])
        self.assertEqual("opaque-canonical-json-string", by_id["rpc"]["transport"])
        self.assertTrue(all(row["generalInteroperabilityClaimed"] is False for row in matrix))


if __name__ == "__main__":
    unittest.main()

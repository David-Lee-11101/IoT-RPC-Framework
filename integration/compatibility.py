#!/usr/bin/env python3
"""Offline RuleSpec v0 compatibility runner for the governed repository suite."""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any, Dict, List, Sequence


ROOT = Path(__file__).resolve().parent
RPC_ROOT = ROOT.parent
ANSI_ESCAPE = re.compile(r"\x1b\[[0-9;]*[A-Za-z]")
RULE_FIELDS = {"id", "description", "triggers", "conditions", "actions"}
STATE_FIELDS = {"device", "state"}


def canonical_json_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), sort_keys=True).encode("utf-8")


def fixture_sha256(fixture: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(fixture)).hexdigest()


def validate_fixture(fixture: Any) -> None:
    if not isinstance(fixture, list) or not fixture:
        raise ValueError("RuleSpec v0 fixture must be a non-empty array")
    identifiers = set()
    for rule in fixture:
        if not isinstance(rule, dict) or set(rule) != RULE_FIELDS:
            raise ValueError("each RuleSpec must contain exactly id, description, triggers, conditions, and actions")
        if not isinstance(rule["id"], str) or not rule["id"].strip():
            raise ValueError("rule id must be a non-empty string")
        if rule["id"] in identifiers:
            raise ValueError("rule ids must be unique")
        identifiers.add(rule["id"])
        if not isinstance(rule["description"], str) or not rule["description"].strip():
            raise ValueError("description must be a non-empty string")
        for field in ("triggers", "conditions", "actions"):
            values = rule[field]
            if not isinstance(values, list):
                raise ValueError(field + " must be an array")
            if field in ("triggers", "actions") and not values:
                raise ValueError(field + " must not be empty")
            for state in values:
                if not isinstance(state, dict) or set(state) != STATE_FIELDS:
                    raise ValueError("each device state must contain exactly device and state")
                if any(not isinstance(state[key], str) or not state[key].strip() for key in STATE_FIELDS):
                    raise ValueError("device and state must be non-empty strings")


def validate_manifest(manifest: Any) -> None:
    if manifest.get("schema") != "rulespec-repository-manifest/v0":
        raise ValueError("unsupported repository manifest")
    anchor = manifest.get("anchor", {})
    if anchor.get("commitMode") != "invocation" or "commit" in anchor:
        raise ValueError("anchor must use invocation commit mode without a self-pinned commit")
    siblings = manifest.get("siblings")
    if not isinstance(siblings, list) or {item.get("id") for item in siblings} != {"scheduler", "analyzer", "composer"}:
        raise ValueError("manifest must describe scheduler, analyzer, and composer")
    for item in siblings:
        if not isinstance(item.get("repository"), str) or not item["repository"]:
            raise ValueError("sibling repository name is required")
        if not re.fullmatch(r"[0-9a-f]{40}", str(item.get("commit", ""))):
            raise ValueError("sibling commits must be concrete 40-character hashes")
        if not isinstance(item.get("publicationPending"), bool):
            raise ValueError("publicationPending must be a boolean")


def build_capability_matrix() -> List[Dict[str, Any]]:
    document = json.loads((ROOT / "capability-matrix.json").read_text(encoding="utf-8"))
    if document.get("generalInteroperabilityClaimed") is not False:
        raise ValueError("capability matrix must not claim general interoperability")
    return document["capabilities"]


def normalize_scheduler(payload: Any, fixture: List[Dict[str, Any]]) -> Dict[str, Any]:
    expected_ids = sorted(rule["id"] for rule in fixture)
    normalized = {
        "mode": payload.get("mode"),
        "ruleCount": payload.get("ruleCount"),
        "completedRuleIds": sorted(payload.get("completedRuleIds", [])),
        "conditionsApplied": payload.get("conditionsApplied"),
    }
    if normalized != {
        "mode": "deterministic-simulation",
        "ruleCount": len(fixture),
        "completedRuleIds": expected_ids,
        "conditionsApplied": False,
    }:
        raise ValueError("scheduler output does not match its documented adapter semantics")
    return normalized


def _expected_analysis(fixture: List[Dict[str, Any]]) -> Dict[str, Any]:
    conflicts = []
    dependencies = []
    for left_index, left in enumerate(fixture):
        left_actions = {item["device"]: item["state"] for item in left["actions"]}
        for right in fixture[left_index + 1:]:
            right_actions = {item["device"]: item["state"] for item in right["actions"]}
            conflicting = sorted(device for device in left_actions.keys() & right_actions.keys()
                                 if left_actions[device] != right_actions[device])
            if conflicting:
                conflicts.append({"devices": conflicting, "left": left["id"], "right": right["id"]})
            target_devices = {item["device"] for item in right["triggers"] + right["actions"]}
            dependent = sorted(left_actions.keys() & target_devices)
            if dependent:
                dependencies.append({"devices": dependent, "source": left["id"], "target": right["id"]})
    return {"conflicts": conflicts, "dependencies": dependencies, "rule_count": len(fixture)}


def normalize_analyzer(payload: Any, fixture: List[Dict[str, Any]]) -> Dict[str, Any]:
    normalized = {
        "conflicts": payload.get("conflicts"),
        "dependencies": payload.get("dependencies"),
        "rule_count": payload.get("rule_count"),
    }
    if normalized != _expected_analysis(fixture):
        raise ValueError("analyzer output does not match ordered conflict/dependency semantics")
    return normalized


def normalize_composer(payload: Any, expected_rule: Dict[str, Any]) -> Dict[str, Any]:
    if payload.get("schema") != "rule-spec/v0":
        raise ValueError("composer must add schema=rule-spec/v0")
    rule = {key: payload.get(key) for key in RULE_FIELDS}
    if len(rule["triggers"] or []) != 1:
        raise ValueError("composer requires exactly one trigger")
    if len(rule["conditions"] or []) > 1:
        raise ValueError("composer permits zero or one condition")
    if len(rule["actions"] or []) != 1:
        raise ValueError("composer requires exactly one action")
    if rule != expected_rule:
        raise ValueError("composer changed RuleSpec semantics during normalization")
    return {"schema": "rule-spec/v0", "rule": rule}


def _json_stdout(stdout: str) -> Any:
    cleaned = ANSI_ESCAPE.sub("", stdout).strip()
    try:
        return json.loads(cleaned)
    except json.JSONDecodeError:
        for line in reversed(cleaned.splitlines()):
            try:
                return json.loads(line)
            except json.JSONDecodeError:
                continue
    raise ValueError("command stdout did not contain JSON")


def _run(identifier: str, argv: Sequence[str], cwd: Path, timeout: int,
         env: Dict[str, str] = None, display_argv: Sequence[str] = None) -> Dict[str, Any]:
    completed = subprocess.run(
        list(argv), cwd=str(cwd), env=env, text=True, stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, timeout=timeout, check=False,
    )
    execution = {
        "id": identifier,
        "argv": list(display_argv or argv),
        "cwd": cwd.name,
        "exitCode": completed.returncode,
        "stdoutSha256": hashlib.sha256(completed.stdout.encode("utf-8")).hexdigest(),
        "stderrSha256": hashlib.sha256(completed.stderr.encode("utf-8")).hexdigest(),
    }
    if completed.returncode != 0:
        raise RuntimeError(identifier + " failed\n" + completed.stdout[-2000:] + completed.stderr[-2000:])
    execution["stdout"] = completed.stdout
    return execution


def _git_state(repository: Path) -> Dict[str, Any]:
    head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=str(repository), text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout.strip()
    status = subprocess.run(["git", "status", "--porcelain"], cwd=str(repository), text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout
    return {"commit": head, "workingTreeDirty": bool(status.strip())}


def run_suite(args: argparse.Namespace) -> Dict[str, Any]:
    fixture_path = Path(args.fixture).resolve()
    fixture = json.loads(fixture_path.read_text(encoding="utf-8"))
    validate_fixture(fixture)
    manifest = json.loads(Path(args.manifest).read_text(encoding="utf-8"))
    validate_manifest(manifest)

    scheduler = Path(args.scheduler_repo).resolve()
    analyzer = Path(args.analyzer_repo).resolve()
    composer = Path(args.composer_repo).resolve()
    rpc = Path(args.rpc_repo).resolve()
    maven = [args.maven, "-s", str(Path(args.maven_settings).resolve()), "-B", "-ntp"]
    maven_display = ["mvn", "-s", "<maven-settings>", "-B", "-ntp"]
    environment = os.environ.copy()
    if args.java_home:
        environment["JAVA_HOME"] = str(Path(args.java_home).resolve())
        environment["PATH"] = str(Path(args.java_home).resolve() / "bin") + os.pathsep + environment.get("PATH", "")

    executions = []
    scheduler_run = _run(
        "scheduler", maven + ["-q", "exec:java", "-Dexec.mainClass=concurrency.demo.SmartRuleSimulationDemo",
                               "-Dexec.args=" + str(fixture_path)], scheduler, args.timeout, environment,
        display_argv=maven_display + ["-q", "exec:java",
                                      "-Dexec.mainClass=concurrency.demo.SmartRuleSimulationDemo",
                                      "-Dexec.args=<fixture>"],
    )
    scheduler_result = normalize_scheduler(_json_stdout(scheduler_run.pop("stdout")), fixture)
    scheduler_run["normalized"] = scheduler_result
    executions.append(scheduler_run)

    analyzer_run = _run(
        "analyzer", [args.python, "-m", "smart_rule_analyzer.cli", str(fixture_path)],
        analyzer, args.timeout, display_argv=["python3", "-m", "smart_rule_analyzer.cli", "<fixture>"],
    )
    analyzer_result = normalize_analyzer(_json_stdout(analyzer_run.pop("stdout")), fixture)
    analyzer_run["normalized"] = analyzer_result
    executions.append(analyzer_run)

    composed = []
    with tempfile.TemporaryDirectory(prefix="rulespec-compatibility-") as temporary:
        temporary_root = Path(temporary)
        for index, rule in enumerate(fixture):
            rule_path = temporary_root / (rule["id"] + ".json")
            rule_path.write_bytes(canonical_json_bytes(rule) + b"\n")
            composer_run = _run(
                "composer:" + rule["id"],
                [args.python, "-m", "applet_executor.offline_composer", str(rule_path)],
                composer, args.timeout,
                display_argv=["python3", "-m", "applet_executor.offline_composer", "<temporary>/" + rule_path.name],
            )
            composer_run["normalized"] = normalize_composer(_json_stdout(composer_run.pop("stdout")), rule)
            executions.append(composer_run)
            composed.append(composer_run["normalized"])

    with tempfile.TemporaryDirectory(prefix="rulespec-rpc-") as temporary:
        rpc_fixture = Path(temporary) / "rulespec-v0.canonical.json"
        rpc_fixture.write_bytes(canonical_json_bytes(fixture))
        rpc_run = _run(
            "rpc", maven + ["-pl", "rpc-common-protocol", "-am",
                             "-Dtest=integration.RpcLoopbackIntegrationTest",
                             "-Drulespec.fixture=" + str(rpc_fixture),
                             "-Drulespec.fixture.sha256=" + fixture_sha256(fixture),
                             "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
            rpc, args.timeout, environment,
            display_argv=maven_display + ["-pl", "rpc-common-protocol", "-am",
                                          "-Dtest=integration.RpcLoopbackIntegrationTest",
                                          "-Drulespec.fixture=<canonical-fixture>",
                                          "-Drulespec.fixture.sha256=" + fixture_sha256(fixture),
                                          "-Dsurefire.failIfNoSpecifiedTests=false", "test"],
        )
    rpc_run.pop("stdout")
    rpc_run["normalized"] = {
        "proof": "canonical-shared-fixture-loopback-echo-passed",
        "transport": "opaque-canonical-json-string",
        "schemaAware": False,
        "fixtureCanonicalSha256": fixture_sha256(fixture),
        "transportInterpretation": "none",
    }
    executions.append(rpc_run)

    source_states = {
        "anchor": _git_state(rpc),
        "scheduler": _git_state(scheduler),
        "analyzer": _git_state(analyzer),
        "composer": _git_state(composer),
    }
    manifest_commits = {item["id"]: item["commit"] for item in manifest["siblings"]}
    for identifier, pinned in manifest_commits.items():
        if source_states[identifier]["commit"] != pinned:
            raise ValueError(identifier + " checkout does not match the manifest commit")

    return {
        "schema": "rulespec-compatibility-proof/v0",
        "status": "pass",
        "fixture": {
            "schema": "rule-spec/v0",
            "sha256": fixture_sha256(fixture),
            "ruleCount": len(fixture),
        },
        "repositories": {
            "anchorCommitMode": "invocation",
            "sourceStates": source_states,
            "publicationPending": any(item["publicationPending"] for item in manifest["siblings"]),
        },
        "assertions": [
            "scheduler completed every rule and explicitly did not evaluate conditions",
            "analyzer matched ordered action-conflict and dependency semantics",
            "composer preserved every rule while adding the schema marker under 1/0..1/1 cardinality",
            "RPC loopback echoed the exact canonical shared fixture and passed concurrency, timeout, and disconnect cleanup",
        ],
        "semanticComparison": {
            "fixtureRuleIds": [rule["id"] for rule in fixture],
            "schedulerCompletedSameRuleIds": True,
            "analyzerMatchedReferenceSemantics": True,
            "composerPreservedSameRules": True,
            "rpcPreservedOpaqueJsonString": True,
        },
        "observations": {
            "scheduler": scheduler_result,
            "analyzer": analyzer_result,
            "composer": composed,
            "rpc": rpc_run["normalized"],
        },
        "capabilityMatrix": build_capability_matrix(),
        "executions": executions,
        "generalInteroperabilityClaimed": False,
    }


def parse_args(argv: Sequence[str] = None) -> argparse.Namespace:
    repositories_root = RPC_ROOT.parent
    parser = argparse.ArgumentParser(description="Run the offline RuleSpec v0 repository compatibility suite")
    parser.add_argument("--fixture", default=str(ROOT / "contracts" / "v0" / "rulespec.fixture.json"))
    parser.add_argument("--manifest", default=str(ROOT / "repositories.json"))
    parser.add_argument("--scheduler-repo", default=str(repositories_root / "IoT-Event-Proxy"))
    parser.add_argument("--analyzer-repo", default=str(repositories_root / "IoT-Event-Detector"))
    parser.add_argument("--composer-repo", default=str(repositories_root / "IoT-CRI-Interpreter"))
    parser.add_argument("--rpc-repo", default=str(RPC_ROOT))
    parser.add_argument("--maven-settings", default=str(ROOT / "maven-settings.xml"))
    parser.add_argument("--java-home", help="optional JDK 8 home; otherwise use JAVA_HOME/PATH")
    parser.add_argument("--maven", default="mvn")
    parser.add_argument("--python", default=sys.executable)
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--proof", type=Path, default=ROOT / "proof.json")
    return parser.parse_args(argv)


def main(argv: Sequence[str] = None) -> int:
    args = parse_args(argv)
    proof = run_suite(args)
    rendered = json.dumps(proof, indent=2, sort_keys=True) + "\n"
    args.proof.parent.mkdir(parents=True, exist_ok=True)
    args.proof.write_text(rendered, encoding="utf-8")
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

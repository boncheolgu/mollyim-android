"""Run the same functional PIN regression against .10 and the current fix."""
import json
import shutil
import subprocess
import xml.etree.ElementTree as ET
from pathlib import Path


BASELINE = "52d530cebbd8e5ad178a4ededbe7fc3ac458c61b"
PIN_SOURCE = Path("app/src/main/java/org/thoughtcrime/securesms/screenlock/ScreenLockPinDialogFragment.kt")
AUTH_SOURCE = Path("app/src/main/java/org/thoughtcrime/securesms/screenlock/ScreenLockAuthentication.kt")
TEST = "org.thoughtcrime.securesms.screenlock.ScreenLockPinCompletionRaceTest"
METHOD = "freshUnlockRequestAfterSessionChangeAcceptsOneCorrectPin"
CONTROL = "ordinaryBackgroundResumeWithFreshRequestAcceptsOneCorrectPin"
MARKER = "One correct PIN must unlock the current request"
RESULTS = Path("app/build/test-results/testProdStoreDebugUnitTest")
OUTPUT = Path("pin-race-proof")


def check_baseline(result_file, exit_code):
    root = ET.parse(result_file).getroot()
    cases = root.findall("testcase")
    assert exit_code != 0, "Legacy source unexpectedly passed; hypothesis not demonstrated"
    assert len(cases) == 2 and {case.get("name") for case in cases} == {METHOD, CONTROL}, "Unexpected baseline test selection"
    control = next(case for case in cases if case.get("name") == CONTROL)
    assert not list(control), "Ordinary background/resume control must pass"
    case = next(case for case in cases if case.get("name") == METHOD)
    assert not case.findall("error") and case.find("skipped") is None, "A fixture/infrastructure error is not reproduction"
    failures = case.findall("failure")
    assert len(failures) == 1, "Expected exactly the functional assertion to fail"
    message = failures[0].get("message", "")
    assert MARKER in message and "expected:<1> but was:<0>" in message, message
    return message


def gradle(log_name, tests):
    with (OUTPUT / log_name).open("w") as log:
        return subprocess.run(
            ["./gradlew", "-PciVerification=true", "-PbuildVariants=^prodStoreDebug$",
             ":app:testProdStoreDebugUnitTest", *[arg for test in tests for arg in ("--tests", test)]],
            stdout=log, stderr=subprocess.STDOUT
        ).returncode


def show_failure_tail(log_name):
    lines = (OUTPUT / log_name).read_text(errors="replace").splitlines()
    print("\n".join(lines[-45:]), flush=True)


def main():
    OUTPUT.mkdir(exist_ok=True)
    current = {source: source.read_bytes() for source in (PIN_SOURCE, AUTH_SOURCE)}
    current_sha = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    baseline = subprocess.check_output(["git", "show", f"{BASELINE}:{PIN_SOURCE}"])
    # Scheduling seam only: the verifier, generation checks and callbacks remain .10's.
    assert baseline.count(b"import kotlinx.coroutines.Dispatchers\n") == 1
    assert baseline.count(b"withContext(Dispatchers.IO)") == 1
    baseline = baseline.replace(b"import kotlinx.coroutines.Dispatchers\n", b"import org.signal.core.util.concurrent.SignalDispatchers\n")
    baseline = baseline.replace(b"withContext(Dispatchers.IO)", b"withContext(SignalDispatchers.IO)")
    try:
        PIN_SOURCE.write_bytes(baseline)
        AUTH_SOURCE.write_bytes(subprocess.check_output(["git", "show", f"{BASELINE}:{AUTH_SOURCE}"]))
        shutil.rmtree(RESULTS, ignore_errors=True)
        print("Running functional regression against .10 PIN dialog source", flush=True)
        code = gradle("baseline.log", [f"{TEST}.{METHOD}", f"{TEST}.{CONTROL}"])
        report = RESULTS / f"TEST-{TEST}.xml"
        try:
            message = check_baseline(report, code)
        except Exception:
            show_failure_tail("baseline.log")
            raise
        shutil.copy2(report, OUTPUT / "baseline.xml")
    finally:
        for source, content in current.items():
            source.write_bytes(content)

    print("Legacy request reproduced: correct PIN produced zero success callbacks", flush=True)
    shutil.rmtree(RESULTS, ignore_errors=True)
    code = gradle("fixed.log", [TEST, "org.thoughtcrime.securesms.screenlock.ScreenLockPromptTransitionTest"])
    if code != 0:
        show_failure_tail("fixed.log")
    assert code == 0, "Fixed-source regressions failed; inspect fixed.log"
    cases = []
    for report in RESULTS.glob("TEST-*.xml"):
        root = ET.parse(report).getroot()
        assert not root.findall(".//failure") and not root.findall(".//error"), str(report)
        cases.extend(root.findall("testcase"))
        shutil.copy2(report, OUTPUT / report.name)
    assert len(cases) >= 15 and all(case.find("skipped") is None for case in cases)
    assert any(case.get("name") == METHOD for case in cases)
    receipt = {"baseline": BASELINE, "fixed": current_sha, "expected_legacy_failure": message,
               "fixed_tests": len(cases), "result": "legacy failure reproduced; fixed regressions passed"}
    (OUTPUT / "verification.json").write_text(json.dumps(receipt, indent=2) + "\n")
    print(json.dumps(receipt), flush=True)


if __name__ == "__main__":
    main()

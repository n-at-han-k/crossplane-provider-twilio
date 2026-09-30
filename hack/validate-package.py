#!/usr/bin/env python3
"""Validate the BUILT xpkg in _output/, not just the source that produced it.

There are two distinct ways the SafeStart capability has been lost from this
provider, and a source-level test only catches the first:

  1. package/crossplane.yaml declared `safe-start`, which is not the enum value
     (`SafeStart`). Crossplane pruned it on install.
     -> caught by TestPackageDeclaresSafeStartCorrectly.

  2. The source said `SafeStart` correctly, but the build submodule pinned
     CROSSPLANE_CLI_VERSION to v1.20.0 -- a Crossplane 1.x CLI, which parsed the
     meta into its own 1.x Provider struct and SILENTLY DROPPED the field when
     re-serialising into the package. The published artifact declared no
     capabilities at all while the source looked perfect.
     -> only catchable here, by opening the artifact.

Both produce the same symptom on a real cluster: the RBAC manager never grants
the customresourcedefinitions get/list/watch a safe-start provider needs, and
the container CrashLoopBackOffs with "failed to wait for crd-gate caches to
sync". Nothing else in the build fails.

Run via `make package.check` after `make build`.
"""

import glob
import io
import os
import sys
import tarfile

try:
    import yaml
except ImportError:
    sys.exit("PyYAML is required: it is provided by the nix devshell")

EXPECTED_CAPABILITIES = {"SafeStart"}
EXPECTED_META_API = "meta.pkg.crossplane.io/v1"

# Not a hardcoded number: the count is whatever controller-gen wrote into
# package/crds, and what this checks is that every one of them SURVIVED
# packaging. A literal here would just be a second thing to bump whenever the
# API description grows a resource.
def expected_crd_count():
    return len(glob.glob(os.path.join(
        os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
        "package", "crds", "*.yaml")))


def package_yaml(xpkg_path):
    """Return the parsed package.yaml documents from inside an xpkg."""
    with tarfile.open(xpkg_path) as outer:
        for name in outer.getnames():
            member = outer.extractfile(name)
            if member is None:
                continue
            blob = member.read()
            if blob[:2] != b"\x1f\x8b":  # not gzipped, not a layer
                continue
            try:
                layer = tarfile.open(fileobj=io.BytesIO(blob), mode="r:gz")
            except tarfile.TarError:
                continue
            if "package.yaml" not in layer.getnames():
                continue
            raw = layer.extractfile("package.yaml").read().decode()
            return list(yaml.safe_load_all(raw))
    return None


def main():
    candidates = glob.glob("_output/xpkg/*/*.xpkg")
    if not candidates:
        sys.exit("no xpkg in _output/; run `make build` first")

    failures = []
    for path in sorted(candidates):
        docs = package_yaml(path)
        if docs is None:
            failures.append(f"{path}: no package.yaml inside the artifact")
            continue

        meta = next(
            (
                d
                for d in docs
                if d
                and d.get("kind") == "Provider"
                and str(d.get("apiVersion", "")).startswith("meta.pkg.")
            ),
            None,
        )
        if meta is None:
            failures.append(f"{path}: no meta.pkg.crossplane.io Provider document")
            continue

        api = meta.get("apiVersion")
        if api != EXPECTED_META_API:
            failures.append(
                f"{path}: meta apiVersion is {api}, expected {EXPECTED_META_API}. "
                "Crossplane 2.x reads capabilities from v1."
            )

        got = set(meta.get("spec", {}).get("capabilities") or [])
        if got != EXPECTED_CAPABILITIES:
            failures.append(
                f"{path}: capabilities in the BUILT package are {sorted(got) or '[]'}, "
                f"expected {sorted(EXPECTED_CAPABILITIES)}. If the source declares them "
                "correctly, the packaging CLI is stripping them -- check "
                "CROSSPLANE_CLI_VERSION is 2.x."
            )

        crds = [d for d in docs if d and d.get("kind") == "CustomResourceDefinition"]
        if len(crds) != expected_crd_count():
            failures.append(
                f"{path}: package carries {len(crds)} CRDs, expected {expected_crd_count()} "
                f"-- package/crds has that many, so packaging dropped some"
            )

        annotations = meta.get("metadata", {}).get("annotations", {}) or {}
        for required in (
            "meta.crossplane.io/maintainer",
            "meta.crossplane.io/description",
            "meta.crossplane.io/readme",
            "meta.crossplane.io/source",
            "meta.crossplane.io/license",
        ):
            if not annotations.get(required):
                failures.append(f"{path}: missing {required} (it is the Marketplace listing)")

        print(
            f"  ok  {os.path.basename(path)}: {api}, "
            f"capabilities={sorted(got)}, {len(crds)} CRDs"
        )

    print()
    if failures:
        for f in failures:
            print(f"  FAIL {f}")
        sys.exit(1)
    print(f"  {len(candidates)} built package(s) validated")


if __name__ == "__main__":
    main()

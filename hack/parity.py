#!/usr/bin/env python3
"""Does this provider cover what terraform-provider-twilio covers?

Three catalogs of the same shape, and the differences between them:

  spec        what Twilio's OWN rules select from an OpenAPI document.
              TwilioTerraformGenerator.postProcessOperationsWithModels,
              Utility.populateCrudOperations, PathUtils and
              StringHelper.toSnakeCase, transcribed from
              github.com/twilio/twilio-oai-generator.
  terraform   what terraform-provider-twilio ACTUALLY registers, read out of
              its generated Go.
  crossplane  what this repo generates, read out of apis/ and
              internal/controller/. NOT IMPLEMENTED: there is nothing to read
              until bin/generate has run once, and a mode that reports an
              empty catalog as a clean diff is worse than one that is absent.

`spec vs terraform` validates the transcription: it must be exact, or a
difference between us and Twilio cannot be told from a mistake in reading
Twilio. At twilio-oai 1.56.1 against terraform-provider-twilio 0.18.46 it is
164 of 164 with no misses -- see PARITY.md.

`spec vs crossplane` is the actual parity check, and the differences are meant
to be few, deliberate and listed in PARITY.md.

  hack/parity.py spec       reference/twilio-oai/spec/json
  hack/parity.py terraform  ../terraform-provider-twilio
  hack/parity.py diff       reference/twilio-oai/spec/json ../terraform-provider-twilio
"""
import json
import os
import re
import sys
from collections import defaultdict
from glob import glob

# ---------------------------------------------------------------- Twilio's rules

# PathUtils, one function per regex.
def remove_extension(p):
    return re.sub(r"\.[^/]+$", "", p)


def remove_trailing_path_param(p):
    return re.sub(r"/\{[^}]+\}[^/]*$", "", p, count=1)


def remove_path_param_ids(p):
    return re.sub(r"\{[^}]+\}", "{}", p)


def clean_path_and_remove_first_element(p):
    return re.sub(r"/\{[^}]+\}", "", remove_extension(re.sub(r"/[^/]+", "", p, count=1)))


def to_snake(s):
    """StringHelper.toSnakeCase -- FOUR passes.

    NOT terraform-provider-twilio's own two-pass core.ToSnakeCase. The third
    and fourth passes are what split a digit or an acronym from the word after
    it, and without the fourth `SIPDomains` snake-cases to `sipdomains`
    instead of `sip_domains` -- 11 resources named wrongly, which reads as 11
    missing and 11 extra.
    """
    s = re.sub(r"[^a-zA-Z\d]+", "_", s)
    s = re.sub(r"([a-z])([A-Z])", r"\1_\2", s)
    s = re.sub(r"(\d[A-Z]*)([A-Z])", r"\1_\2", s)
    s = re.sub(r"([A-Z])([A-Z][a-z])", r"\1_\2", s)
    return s.lower()


# Utility.populateCrudOperations: the verb is the operationId's PREFIX, not the
# HTTP method and not the pathType. `POST .../Addresses` is a create because it
# is called CreateAddress, and `POST .../Addresses/{Sid}` is an update because
# it is called UpdateAddress.
VERBS = ("CREATE", "FETCH", "UPDATE", "PATCH", "DELETE", "READ")


def verb_of(operation_id):
    for v in VERBS:
        if operation_id.lower().startswith(v.lower()):
            return v
    return "READ"


# TwilioTerraformGenerator.removeNonCrudResources: keep a resource only if it
# has all of these. UPDATE and READ are deliberately not required.
REQUIRED = ("CREATE", "FETCH", "DELETE")

HTTP_METHODS = ("get", "post", "put", "delete", "patch")


def product_version(spec_file):
    base = os.path.basename(spec_file)
    base = base[len("twilio_"):-len(".json")]
    head, _, tail = base.rpartition("_")
    if head and re.fullmatch(r"v\d+", tail):
        return head, tail
    return base, None


def terraform_name(spec_file, resource_name):
    """`twilio_api_accounts_addresses`, `twilio_accounts_credentials_aws_v1`.

    The version is suffixed except for v2010, which processOpenAPI strips.
    """
    product, version = product_version(spec_file)
    name = "twilio_%s_%s" % (product, to_snake(resource_name))
    return name if version in (None, "v2010") else "%s_%s" % (name, version)


def catalog_spec(spec_dir):
    """What Twilio's rules select, per spec document."""
    out = {}

    for spec_file in sorted(glob(os.path.join(spec_dir, "*.json"))):
        doc = json.load(open(spec_file))
        grouped = defaultdict(
            lambda: {"verbs": {}, "params": {}, "path_params": [], "create_answers": set()})

        for path, item in (doc.get("paths") or {}).items():
            for method, operation in item.items():
                if method not in HTTP_METHODS:
                    continue

                operation_id = operation.get("operationId", "")
                # opList.removeIf(co -> co.nickname.startsWith("List"))
                if operation_id.startswith("List"):
                    continue

                key = remove_path_param_ids(remove_trailing_path_param(remove_extension(path)))
                resource = grouped[key]
                resource["name"] = clean_path_and_remove_first_element(path).replace("/", "")
                verb = verb_of(operation_id)
                resource["verbs"][verb] = operation_id

                if verb in ("CREATE", "UPDATE", "FETCH"):
                    resource["params"].setdefault(verb, sorted(body_properties(operation)))
                if verb == "FETCH":
                    resource["path_params"] = [
                        p["name"] for p in all_params(item, operation) if p.get("in") == "path"
                    ]
                if verb == "CREATE":
                    resource["create_answers"] = response_properties(doc, operation)

        for key, resource in grouped.items():
            if not all(v in resource["verbs"] for v in REQUIRED):
                continue
            if not identifiable(resource):
                continue
            out[terraform_name(spec_file, resource["name"])] = {
                "spec": os.path.basename(spec_file),
                "name": resource["name"],
                "resource_id": key,
                "verbs": sorted(resource["verbs"]),
                "operations": resource["verbs"],
                "params": resource["params"],
                "path_params": resource["path_params"],
            }

    return out


def all_params(item, operation):
    """An operation's parameters, including the ones declared on the path."""
    return (item.get("parameters") or []) + (operation.get("parameters") or [])


def resolve(doc, schema, hops=8):
    """A schema, following $ref into the document's components."""
    while schema is not None and "$ref" in schema and hops > 0:
        name = schema["$ref"].rsplit("/", 1)[-1]
        schema = ((doc.get("components") or {}).get("schemas") or {}).get(name)
        hops -= 1
    return schema


def response_properties(doc, operation):
    """The property names of what an operation answers, following allOf."""
    names = set()

    for code, response in (operation.get("responses") or {}).items():
        if not code.startswith("2"):
            continue
        for media in (response.get("content") or {}).values():
            schema = resolve(doc, media.get("schema"))
            if not schema:
                continue
            names |= set(schema.get("properties") or {})
            for member in schema.get("allOf") or []:
                member = resolve(doc, member)
                names |= set((member or {}).get("properties") or {})

    return names


def identifiable(resource):
    """Whether the CREATE answers the identifier the fetch is addressed by.

    TwilioTerraformGenerator's fifth rule, and the least visible: it takes the
    fetch's last path parameter as x-resource-id and then drops the resource
    unless that name is a property of what the CREATE answers -- Create is
    where an external name gets recorded, so a create whose answer omits the id
    leaves nothing to record.

    This is why the provider has no twilio_api_accounts_outgoing_caller_ids:
    fetched at /OutgoingCallerIds/{Sid}, but created by CreateValidationRequest,
    whose answer is a validation request with no sid in it. Before this rule
    was implemented here it was the one difference this script could not
    explain.
    """
    path_params = resource.get("path_params") or []
    if not path_params:
        # Addressed by a query parameter; the generator keeps those.
        return True

    return to_snake(path_params[-1]) in (resource.get("create_answers") or set())


def body_properties(operation):
    """The request body's property names, whichever content type it uses."""
    content = (operation.get("requestBody") or {}).get("content") or {}
    for media in content.values():
        return list(((media.get("schema") or {}).get("properties") or {}))
    return []


# ------------------------------------------------- reading the terraform provider

# The resource files are 100% openapi-generator output and completely uniform,
# so these read them with regexes rather than a Go parser.
# ponytail: regex over generated Go; use go/ast if a hand-edited file ever
# appears in twilio/resources/.
RESOURCE_MAP_ENTRY = re.compile(r'^\s+"(twilio_[a-z0-9_]+)":\s+(\w+)\.(\w+)\(\)', re.M)
# The registry refers to a resource by package alias and function, and the
# function name alone is NOT unique: ResourceServices exists in chat/v1,
# chat/v2, messaging/v1, notify/v1 and more. Keyed on the function alone, 41
# of 164 resources collapse onto each other and read as missing.
RESOURCE_IMPORT = re.compile(r'^\s+(\w+)\s+"[^"]*/twilio/resources/([^"]+)"', re.M)
RESOURCE_FUNC = re.compile(r"^func (Resource\w+)\(\) \*schema\.Resource \{(.*?)^\}", re.M | re.S)
# `"city": AsString(SchemaRequired),` and also
# `"permission": AsList(AsString(SchemaRequired), SchemaRequired),` -- an
# array field's own flag is the LAST one on the line, not the element's, so
# this reads the flag before the closing paren rather than the first one it
# finds.
SCHEMA_FIELD = re.compile(r'"([a-z0-9_]+)":\s+As(\w+)\(.*Schema(\w+)\),')
CONTEXT_FUNC = re.compile(r"(Create|Read|Update|Delete)Context:")
IMPORT_SET = re.compile(r'd\.Set\("([a-z0-9_]+)", importParts\[')


def catalog_terraform(provider_dir):
    """What terraform-provider-twilio actually registers."""
    registry_path = os.path.join(provider_dir, "twilio", "resources", "resources.go")
    registry_source = open(registry_path).read()

    # alias -> "api/v2010", so a function can be keyed on the directory it
    # lives in as well as its name.
    where = dict(RESOURCE_IMPORT.findall(registry_source))
    registry = {}
    for name, alias, func in RESOURCE_MAP_ENTRY.findall(registry_source):
        registry[(where.get(alias, alias), func)] = name

    out = {}
    root = os.path.join(provider_dir, "twilio", "resources")
    for go_file in glob(os.path.join(root, "*", "*", "*.go")):
        source = open(go_file).read()
        package = os.path.relpath(os.path.dirname(go_file), root)

        for func, body in RESOURCE_FUNC.findall(source):
            name = registry.get((package, func))
            if name is None:
                continue

            fields = {field: flag for field, _kind, flag in SCHEMA_FIELD.findall(body)}
            parser = re.search(
                r"func parse%sImportId\(.*?^\}" % func[len("Resource"):], source, re.M | re.S
            )
            out[name] = {
                "func": func,
                "verbs": sorted(set(CONTEXT_FUNC.findall(body))),
                "fields": fields,
                "import_parts": IMPORT_SET.findall(parser.group(0)) if parser else [],
            }

    return out


# ------------------------------------------------ reading this generated provider

# apis/<pkg>/<version>/<pkg>_types.go, the two structs that are the CRD.
PARAMS_STRUCT = re.compile(r"type (\w+)Parameters struct \{(.*?)\n\}", re.S)
OBSERVATION_STRUCT = re.compile(r"type (\w+)Observation struct \{(.*?)\n\}", re.S)
GO_FIELD = re.compile(r'^\s*(\w+)\s+([\w\[\]\.]+)\s+`json:"([^",]+)', re.M)
# internal/controller/<pkg>/<pkg>.go: which twilio-go calls it actually makes.
SDK_CALL = re.compile(r"c\.service\.Rest\.\w+\.(\w+)\(")


def catalog_crossplane(repo):
    """What this repo generates, read out of the generated tree.

    Deliberately read from the OUTPUT rather than from the generator: the
    question is what the provider does, and a catalog built from the same
    rules that produced it could only ever agree with itself.
    """
    out = {}
    apis = os.path.join(repo, "apis")

    if not os.path.isdir(apis):
        return out

    for pkg in sorted(os.listdir(apis)):
        directory = os.path.join(apis, pkg)
        if not os.path.isdir(directory) or re.fullmatch(r"v\d+\w*", pkg):
            continue

        types = None
        for root, _dirs, files in os.walk(directory):
            for name in files:
                if name.endswith("_types.go"):
                    types = os.path.join(root, name)
        if types is None:
            continue

        source = open(types).read()
        spec = PARAMS_STRUCT.search(source)
        observed = OBSERVATION_STRUCT.search(source)

        controller = os.path.join(repo, "internal", "controller", pkg, pkg + ".go")
        calls = sorted(set(SDK_CALL.findall(open(controller).read()))) if os.path.isfile(controller) else []

        out[pkg] = {
            "kind": spec.group(1) if spec else None,
            # The CRD spells a request field the way Twilio takes it
            # (PascalCase), so it is snake-cased to compare with anything else.
            "spec": {to_snake(f[2]) for f in GO_FIELD.findall(spec.group(2))} if spec else set(),
            "observation": {f[2] for f in GO_FIELD.findall(observed.group(2))} if observed else set(),
            "calls": calls,
        }

    return out


def diff_providers(tf_catalog, cp_catalog, product):
    """This provider against terraform-provider-twilio, field by field.

    The comparison has to account for one structural difference rather than
    report it 22 times: Terraform keeps one flat schema per resource, with
    server-assigned fields marked Computed, while Crossplane splits the same
    fields across spec.forProvider and status.atProvider. So Terraform's
    Computed fields are compared against the observation and the rest against
    the spec.
    """
    problems = 0
    mapped = {}

    for pkg in cp_catalog:
        mapped["twilio_%s_%s" % (product, pkg)] = pkg

    ours, theirs = set(mapped), {k for k in tf_catalog if k.startswith("twilio_%s_" % product)}

    print("this provider: %d Kinds   terraform-provider-twilio (%s): %d resources   in both: %d"
          % (len(ours), product, len(theirs), len(ours & theirs)))

    for label, names in (("terraform has, we do not", theirs - ours),
                         ("we have, terraform does not", ours - theirs)):
        print("\n--- %s (%d) ---" % (label, len(names)))
        for name in sorted(names):
            print("   ", name)
        problems += len(names)

    print("\n--- field differences ---")
    for name in sorted(ours & theirs):
        pkg = mapped[name]
        fields = tf_catalog[name]["fields"]

        writable = {f for f, flag in fields.items() if flag != "Computed"}
        computed = {f for f, flag in fields.items() if flag == "Computed"}

        # The resource's own id: Terraform carries it as a schema field,
        # Crossplane as the crossplane.io/external-name annotation. Not a
        # difference in what the provider can do.
        external = set(tf_catalog[name]["import_parts"])

        missing = writable - cp_catalog[pkg]["spec"] - external
        # A field Terraform lets you SET that we only observe is still a
        # difference in what the provider can do, so it is reported on its own
        # line rather than folded into the one above -- or, worse, subtracted
        # out to keep the report clean.
        readonly = {f for f in missing if f in cp_catalog[pkg]["observation"]}
        missing -= readonly
        unobserved = computed - cp_catalog[pkg]["observation"] - external

        if missing:
            print("   %s: settable in terraform, absent here: %s"
                  % (name, sorted(missing)))
            problems += 1
        if readonly:
            print("   %s: settable in terraform, only observed here: %s"
                  % (name, sorted(readonly)))
            problems += 1
        if unobserved:
            print("   %s: computed in terraform, not in status.atProvider: %s"
                  % (name, sorted(unobserved)))
            problems += 1

    print("\n--- CRUD differences ---")
    for name in sorted(ours & theirs):
        pkg = mapped[name]
        expected = set(tf_catalog[name]["verbs"])
        # Every controller reads, creates and deletes; Update is generated only
        # when the document describes one, which is also when Terraform emits
        # UpdateContext.
        actual = {"Create", "Read", "Delete"}
        if any(call.startswith("Update") for call in cp_catalog[pkg]["calls"]):
            actual.add("Update")

        if expected != actual:
            print("   %s: terraform %s, ours %s" % (name, sorted(expected), sorted(actual)))
            problems += 1

    return problems


# ---------------------------------------------------------------------- the diff

# Terraform's CRUD context names against the verbs the spec catalog reports.
VERB_EQUIVALENT = {"Create": "CREATE", "Read": "FETCH", "Update": "UPDATE", "Delete": "DELETE"}


def diff(spec_catalog, tf_catalog):
    """Set differences, then per-resource verb and field differences."""
    ours, theirs = set(spec_catalog), set(tf_catalog)
    problems = 0

    print("spec rule: %d   terraform provider: %d   in both: %d"
          % (len(ours), len(theirs), len(ours & theirs)))

    for label, missing in (("provider has, rule misses", theirs - ours),
                           ("rule produces, provider lacks", ours - theirs)):
        print("\n--- %s (%d) ---" % (label, len(missing)))
        for name in sorted(missing):
            print("   ", name)
        problems += len(missing)

    print("\n--- verb differences ---")
    for name in sorted(ours & theirs):
        expected = {VERB_EQUIVALENT[v] for v in tf_catalog[name]["verbs"]}
        actual = set(spec_catalog[name]["verbs"]) & set(VERB_EQUIVALENT.values())
        if expected != actual:
            print("   %s: provider %s, rule %s"
                  % (name, sorted(expected), sorted(actual)))
            problems += 1

    print("\n--- field differences (create body vs terraform schema) ---")
    for name in sorted(ours & theirs):
        create = spec_catalog[name]["params"].get("CREATE", [])
        want = {to_snake(p) for p in create}
        # path_ prefixed fields are Twilio's own spelling of a path parameter,
        # and a field the provider marks Computed is one it reads rather than
        # one a person writes.
        have = {f for f in tf_catalog[name]["fields"] if not f.startswith("path_")}
        have |= {f[len("path_"):] for f in tf_catalog[name]["fields"] if f.startswith("path_")}
        if want - have:
            print("   %s: in create body, not in provider schema: %s"
                  % (name, sorted(want - have)))
            problems += 1

    return problems


def kinds(spec_path, repo):
    """Every resource Twilio's rules select has a Kind in this repo.

    The generator inherits the selection rules rather than restating them, so
    this holds by construction. It is asserted because the ways it can stop
    holding are silent: the superclass calls clearTemplateFiles() when it
    rejects a resource, which empties the template list for the whole run, and
    a generator that then writes a third of a provider still exits 0.
    """
    catalog = catalog_spec(spec_path) if os.path.isdir(spec_path) else catalog_spec(os.path.dirname(spec_path))
    if not os.path.isdir(spec_path):
        only = os.path.basename(spec_path)
        catalog = {k: v for k, v in catalog.items() if v["spec"] == only}

    expected = {to_snake(v["name"]) for v in catalog.values()}
    apis = os.path.join(repo, "apis")
    found = {
        name for name in os.listdir(apis)
        if os.path.isdir(os.path.join(apis, name)) and not re.fullmatch(r"v\d+\w*", name)
    }

    missing, extra = expected - found, found - expected
    print("resources Twilio's rules select: %d   Kinds generated: %d" % (len(expected), len(found)))

    for label, names in (("selected but not generated", missing), ("generated but not selected", extra)):
        if names:
            print("--- %s (%d) ---" % (label, len(names)))
            for name in sorted(names):
                print("   ", name)

    return 1 if missing or extra else 0


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        return 2

    mode = sys.argv[1]

    if mode == "spec":
        json.dump(catalog_spec(sys.argv[2]), sys.stdout, indent=1, sort_keys=True)
        return 0
    if mode == "terraform":
        json.dump(catalog_terraform(sys.argv[2]), sys.stdout, indent=1, sort_keys=True)
        return 0
    if mode == "diff":
        return 1 if diff(catalog_spec(sys.argv[2]), catalog_terraform(sys.argv[3])) else 0
    if mode == "kinds":
        return kinds(sys.argv[2], sys.argv[3])
    if mode == "crossplane":
        json.dump({k: {kk: sorted(vv) if isinstance(vv, set) else vv for kk, vv in v.items()}
                   for k, v in catalog_crossplane(sys.argv[2]).items()},
                  sys.stdout, indent=1, sort_keys=True)
        return 0
    if mode == "providers":
        product = sys.argv[4] if len(sys.argv) > 4 else "api"
        return 1 if diff_providers(catalog_terraform(sys.argv[2]),
                                   catalog_crossplane(sys.argv[3]), product) else 0

    print("unknown mode %r" % mode)
    return 2


if __name__ == "__main__":
    sys.exit(main())

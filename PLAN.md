# crossplane-provider-twilio — plan

The repo is a copy of `crossplane-provider-rt`, which with
`crossplane-provider-orangehrm` is the second working instance of the same
pipeline: one OpenAPI document in, a Crossplane provider out, everything
generated and committed.

```
reference/<spec>.json
  → openapi-generator -g twilio-crossplane   (generators/twilio, javac'd by flake.nix)
      → apis/<res>/v1alpha1/*_types.go       spec.forProvider = create body
                                             status.atProvider = read response
      → internal/clients/twilio/model_*.go   request/response models
      → internal/clients/twilio/client.go    auth + DoRequest
      → internal/controller/<res>/<res>.go   Observe/Create/Update/Delete
      → cmd, apis/v1alpha1 ProviderConfig, Makefile, package/crossplane.yaml
  → controller-gen + angryjet                DeepCopy, methodsets, package/crds
```

What carries over unchanged: the whole scaffold (`cmd`, `ProviderConfig`,
`Makefile`, `package/`, `flake.nix`, `bin/generate`, `hack/validate-package.py`),
and the shape of every template. What is API-specific is
`generators/*/src/*/CrossplaneCodegen.java` — which operations are one
resource, and which of them is the create, the read, the update, the delete —
plus the `client.mustache` transport and `hack/check-coverage.py`.

Everything below was measured against the real documents, not remembered:
`twilio_api_v2010.json` in full, and a 12-spec sample (api, messaging,
taskrouter, serverless, verify, studio, sync, conversations, numbers, iam,
intelligence, flex) covering 440 paths.

## The source documents

[twilio/twilio-oai], `spec/json/twilio_*.json`. **60 documents**, one per
product and version, 10 KB to 1.9 MB. RT and OrangeHRM each had exactly one;
this is the single biggest structural difference and it decides the scope
question below.

It is a real upstream repository, so it goes in as a **submodule** at
`reference/twilio-oai` the way RT's spec does, not a vendored copy like
OrangeHRM's generated one. `git submodule status` then records which spec
commit the committed tree came from.

## Scope: which documents

Merging the documents into one and generating once does not work: paths
collide across products (`/v1/Services` is in conversations, messaging and
sync, told apart only by the per-path `servers` entry). Generating per
document into one tree does not work either without new machinery, because
each run rewrites the single-copy scaffold — `internal/controller/twilio.go`
registering every controller, `apis/twilio.go`, `go.mod`, `Makefile` — so the
last run wins and only its Kinds are registered.

**Phase 1 is `twilio_api_v2010.json` alone**: the core API — phone numbers,
messages, calls, keys, SIP, queues, applications, addresses. 121 paths, **41
Kinds** (enumerated below). That is between RT's 21 and OrangeHRM's 76, it is
one document so the existing single-pass pipeline works untouched, and it is
the product anyone actually wants managed declaratively.

Phase 2, if wanted, adds documents one at a time. The cheap way is one
generate pass per document with the scaffold emitted **only** on the first,
and a merge step that concatenates the controller and scheme registrations —
roughly 30 lines in `bin/generate` plus a `scaffold=false` additional
property. The expensive way is one repo per product family, which is what
upjet's provider families do and what a >400-CRD provider will eventually
force. Do not build either until phase 1 ships. The generator already has the
`resourcePaths` knob (`;`-separated allowlist) for taking a slice of a big
document in the meantime.

## What Twilio makes easier

**`x-twilio.pathType` is declared, on every path but 8 of 440.**

```json
"/2010-04-01/Accounts/{AccountSid}/Addresses/{Sid}.json": {
  "x-twilio": { "pathType": "instance", "parent": "/Accounts/{Sid}.json", … }
}
```

225 `list`, 207 `instance`. This deletes the hardest and longest part of both
existing generators: `isMember`, `collectionOf`, `canonicalCollection`,
`describesAResource`, the 201-vs-200 create/search discrimination, the
shortest-prefix keying. Read `pathType` and the question is answered by the
document. Keep a fallback for the 8 (trailing `{…}` segment, allowing a
`.json` suffix) rather than a special case; two of those 8 are the SCIM paths
in `iam_organizations` and are out of phase 1 anyway.

`x-twilio.parent` is on 119 of api v2010's 121 paths and gives the parent
resource explicitly — useful later for crossplane reference fields, not needed
for phase 1.

**The CRUD mapping is uniform and needs no heuristic at all.** Across 440
paths there is no PUT (3, all in `iam`) and no PATCH (1):

| | list path | instance path |
|---|---|---|
| POST | **create** | **update** |
| GET | a list — dropped | **read** |
| DELETE | — | **delete** |

Note the inversion both existing generators would get wrong: **a POST on an
instance path is the update.** RT and OrangeHRM both key the update to PUT.

**Creates answer 201 with the whole object** (116 of 138), so `Create` gets
the external name and the full observation from one response. No `Location`
header parsing, unlike RT.

**The request/response name asymmetry needs no new machinery.** The existing
types.go already takes `spec.forProvider` from the create body and
`status.atProvider` from the read response, so Twilio's `FriendlyName` in and
`friendly_name` out falls out for free — see `upToDate` below for the one
place it does not.

## What Twilio breaks

Each of these is a change to `CrossplaneCodegen.java` or a template. Ordered
by how badly it fails if missed.

### 1. `upToDate` silently stops diffing anything

`upToDate` pairs a spec field with an observed field **by Go field name**.
Twilio names a request property `FriendlyName` and the corresponding response
property `friendly_name`, so no pair ever matches, every `upToDate` returns
`true` unconditionally, and **the provider never detects drift on any
resource**. It compiles, it reconciles, it is silently useless.

Fix: pair a create-body property to a response property by snake-casing the
request name, with **`StringHelper.toSnakeCase` from twilio-oai-generator** —
already transcribed in `hack/parity.py`:

```java
inputWord.replaceAll("[^a-zA-Z\\d]+", "_")
         .replaceAll("([a-z])([A-Z])", "$1_$2")
         .replaceAll("(\\d[A-Z]*)([A-Z])", "$1_$2")
         .replaceAll("([A-Z])([A-Z][a-z])", "$1_$2")
         .toLowerCase();
```

Not the two-pass `ToSnakeCase` in `terraform-provider-twilio`'s own
`core/provider_marshal.go:502`. The two agree on every create-body property in
api v2010, so the measurement below holds either way — but only the four-pass
one splits an acronym from the word after it, and `SIPDomains` → `sipdomains`
instead of `sip_domains` is how 11 resources get misnamed. Use the
generator's: it is also the function that produced the response property names
being paired against.

Run over every create body in api v2010 against its own read response: of 225
create-body properties, **93 pair exactly and 0 are near-misses** — nothing is
present under a different spelling, so the rule is exactly right where a pair
exists at all. The remaining **132 are write-only** and Twilio never echoes
them back: `StatusCallback`, `SendDigits`, `Trim`, `Timeout`,
`MachineDetection`, `SipAuthPassword`. That is the case the template already
documents and handles — "a field it does not echo back cannot be diffed
without reporting drift on every reconcile, so it is left alone".

So the assertion in `hack/check-coverage.py` is per-Kind, not per-field: a
Kind whose create body has *at least one* property that snake-cases onto a
response property, and whose generated `upToDate` compares none of them, is
the item-1 bug. A Kind where genuinely nothing pairs (write-only all the way
down) is honest and must not fail the build.

### 2. Request bodies are form-encoded

239 of 261 request bodies are `application/x-www-form-urlencoded` (19 are
`application/json`, 3 `application/scim+json`, all in newer products). Both
existing `client.mustache`s marshal JSON unconditionally. Responses are JSON
throughout.

Fix: encode from the operation's declared content type. Do not write this —
copy `terraform-provider-twilio`'s `core/form_encoder.go` with its
`core/form_encoder_test.go`; see the section below. It already does repeated
keys for arrays, JSON-in-a-form-field for nested objects, and nil-pointer vs
`omitempty` for the absent-vs-empty distinction that matters because an empty
string is how Twilio is told to *clear* a field.

### 3. Path parameters other than the id

`/2010-04-01/Accounts/{AccountSid}/SIP/Domains/{DomainSid}/CredentialListMappings/{Sid}.json`
has two parent parameters. RT's paths were flat (`/queue/{id}`) and its
generator had this exact class of bug fixed once already (a nested create that
interpolated nothing and sent a literal `{id}`).

Fix: every non-final path parameter becomes a **required** `spec.forProvider`
field, and `Observe`/`Update`/`Delete` interpolate from it rather than from
the external name. `AccountSid` is on all 121 api v2010 paths and is the
credential's own account, so it should default from the ProviderConfig when
the field is empty — otherwise every single manifest repeats it.

### 4. The external name is not always `sid`

55 of 207 instance reads have no `sid` property. The id is named after the
last path parameter, snake_cased: `{ConnectAppSid}` → `connect_app_sid`,
`{CallSid}` → `call_sid`, `{CountryCode}` → `country_code`.

Fix: snake_case the instance path's last parameter; use that response property
if it exists, else `sid`. The 8 paths that end in a literal segment
(`/Configuration`, `…/Payloads/{PayloadSid}/Data.json`) are singletons with no
id of their own — external name is the owning parent's id, which is RT's
"set endpoint" precedent and is already in the templates.

### 5. Base URL comes from the document, not the ProviderConfig

Every path carries its own `servers` entry: `api.twilio.com`,
`messaging.twilio.com`, `taskrouter.twilio.com`, 12 distinct hosts in the
sample. RT and OrangeHRM both take one endpoint from the credentials secret.

Fix: the per-operation server is baked into the generated client call. The
ProviderConfig keeps an **optional** `endpoint` override (edge/region hosts,
and a test server), and carries credentials only otherwise.

### 6. Credentials are HTTP basic

`securitySchemes: { accountSid_authToken: { type: http, scheme: basic } }`.

```yaml
stringData:
  credentials: |
    { "accountSid": "AC…", "authToken": "…" }
```

API key + secret is the same basic auth with different values, so accept
`username`/`password` as aliases, which RT's client already does. `accountSid`
is also what defaults the `AccountSid` path parameter in item 3. One document
(`oauth`) uses OAuth2 client credentials and one uses
`basic_apikey_or_accountsid` — both out of phase 1.

### 7. Schema names contain dots

`api.v2010.account.address`, `messaging.v1.service`. openapi-generator will
produce Go type names from these; check what it actually emits and normalise
in `toModelName` if it is not already clean. Cheap to get right, noisy to
discover later.

### 8. Non-standard string formats

`date-time-rfc-2822` (110 uses), `uri-map` (21), `phone-number-capabilities`
(11), `http-method` (41), `currency` (15), `ice-server`, `iso-country-code`,
`phone-number`. `date-time-rfc-2822` is the dangerous one: mapped to
`time.Time` it fails to unmarshal every timestamp Twilio sends, because RFC
2822 is not RFC 3339.

Fix: map every unrecognised string format to `string` in the type mapping.
The CRD loses nothing — these are already strings on the wire.

### 9. `nullable: true` on essentially every response property

RT's generated types are value types with `omitempty`, which cannot tell
"unset" from "empty string" — and for Twilio, empty string is a command to
clear the field (item 2).

Fix: pointers for optional create-body properties. Observation fields can stay
values; nothing writes them.

### 10. Kind names are long, and the short forms collide

Full path segments give `AccountsSIPDomainsAuthRegistrationsCredentialListMappings`.
Last segment alone collides: `Recordings` appears three times (`/Recordings`,
`/Calls/{CallSid}/Recordings`, `/Conferences/{ConferenceSid}/Recordings`),
`CredentialListMappings` twice, `Transcriptions` twice.

Fix: strip a configurable leading segment (`Accounts`, which every api v2010
path shares) — OrangeHRM's generator already has the `pathPrefix` knob for
the same problem, extended here to skip templated segments. Result:
`CallsRecordings`, `SIPDomainsCredentialListMappings`, `IncomingPhoneNumbers`.
Long, and the same length Twilio's own Terraform provider accepts.

Also: strip the `.json` suffix (api v2010 only — 121 paths, no other document
uses it) and the `/2010-04-01` version prefix before naming.

### 11. Actions that look like resources

`POST /Accounts/{AccountSid}/Tokens.json`,
`…/Calls/{CallSid}/Payments.json` + `…/Payments/{Sid}.json` (POST only, no
GET), `…/Messages/{MessageSid}/Feedback.json`, `Siprec`, `Streams`,
`UserDefinedMessages`. And `IncomingPhoneNumbers/Local|Mobile|TollFree`, which
are three alternative creates for one resource that lives elsewhere.

Fix: require an instance GET. This is OrangeHRM's `observable()` rule and it
drops all of them. A resource Crossplane cannot read is one it cannot
reconcile, and 5 of those instance paths offer POST and no GET at all.

## Phase 1 coverage: 41 Kinds from 121 paths

Derived by the rule above. `-` is Twilio's limit, not the provider's.

| Kind (after stripping `Accounts`) | create | read | update | delete |
|---|---|---|---|---|
| `Accounts` | ✓ | ✓ | ✓ | — |
| `Addresses` | ✓ | ✓ | ✓ | ✓ |
| `Applications` | ✓ | ✓ | ✓ | ✓ |
| `Calls` | ✓ | ✓ | ✓ | ✓ |
| `CallsRecordings` | ✓ | ✓ | ✓ | ✓ |
| `IncomingPhoneNumbers` | ✓ | ✓ | ✓ | ✓ |
| `IncomingPhoneNumbersAssignedAddOns` | ✓ | ✓ | — | ✓ |
| `Keys`, `SigningKeys` | ✓ | ✓ | ✓ | ✓ |
| `Messages` | ✓ | ✓ | ✓ | ✓ |
| `OutgoingCallerIds` | ✓ | ✓ | ✓ | ✓ |
| `Queues` | ✓ | ✓ | ✓ | ✓ |
| `ConferencesParticipants` | ✓ | ✓ | ✓ | ✓ |
| `SIPCredentialLists`, `SIPCredentialListsCredentials` | ✓ | ✓ | ✓ | ✓ |
| `SIPDomains`, `SIPIpAccessControlLists`, `SIPIpAccessControlListsIpAddresses` | ✓ | ✓ | ✓ | ✓ |
| `SIPDomainsCredentialListMappings` and the 3 `SIPDomainsAuth*Mappings` | ✓ | ✓ | — | ✓ |
| `UsageTriggers` | ✓ | ✓ | ✓ | ✓ |
| `Conferences`, `ConnectApps`, `SMSShortCodes`, `QueuesMembers` | — | ✓ | ✓ | — |
| `ConferencesRecordings` | — | ✓ | ✓ | ✓ |
| `Recordings`, `Transcriptions`, `RecordingsTranscriptions`, `MessagesMedia`, `RecordingsAddOnResults`, `RecordingsAddOnResultsPayloads` | — | ✓ | — | ✓ |
| `AuthorizedConnectApps`, `AvailablePhoneNumbers`, `CallsNotifications`, `Notifications`, `IncomingPhoneNumbersAssignedAddOnsExtensions`, `RecordingsAddOnResultsPayloadsData` | — | ✓ | — | — |

Twilio's own rule keeps **23** of these — it requires create *and* fetch
*and* delete, which is a stricter rule than this one — and
`terraform-provider-twilio` ships 22 of those 23. Measured rather than
asserted: `hack/parity.py` transcribes Twilio's rules and reproduces the
provider's full 164 resources exactly, with zero verb and zero field
differences. Whether this provider should match that 23 or keep all 41 is the
one open decision, in PARITY.md — the read-only and no-delete Kinds are ones
RT's provider deliberately ships too.

`hack/check-coverage.py` is rewritten against these rules: every operation is
wired into a controller, or is a list GET, or is an action with no instance
GET. Anything else fails the build.

## What to take from terraform-provider-twilio

MIT licensed, `Copyright (C) 2023, Twilio, Inc.` — copyable with the notice
kept. It is itself openapi-generator output (from twilio-oai, via Twilio's own
`terraform` generator), so it is the same pipeline answering the same
questions, and its hand-written `core/` is the part worth having.

3332 lines of tests exist, but almost none of it is resource tests:

| file | lines | portable? |
|---|---|---|
| `core/form_encoder.go` + `_test.go` | 254 test | **copy** — stdlib only, solves item 2 |
| `core/query_encoder.go` + `_test.go` | 32 test | **copy** — stdlib only, query params |
| `core/tags.go`, `types.go`, `twilio_error.go` | ~60 | **copy** — trivial deps of the above |
| `core/provider_marshal.go:502` `ToSnakeCase` + its test | 6 + 20 | **port to Java** — item 1 |
| `core/sids_test.go` | 850 test | skip |
| `core/nullable_test.go` | 646 test | skip |
| `core/provider_marshal_test.go` | 1046 test | skip (bar `ToSnakeCase`) |
| `core/schema_test.go`, `types_test.go` | 155 test | skip |
| `twilio/resources_{flex,serverless}_test.go` | 306 test | skip |

Why the big ones are skipped, so nobody re-litigates it later:

- **`sids.go`** is a `Sid{Valid, Prefix[2]byte, Value[16]byte}` type per SID
  prefix — `AccountSid` tagged `prefix:"AC"`, ~40 of them — with 850 lines
  testing the parse and marshal. Crossplane does not need it: an external name
  is a plain string annotation, and the CRD gets its validation free from the
  document's own `pattern: ^AC[0-9a-fA-F]{32}$` as a kubebuilder marker. This
  is the largest test file and the least useful.
- **`nullable.go`** is `NullableString{Valid, Value}` and friends, and does
  solve item 9's absent-vs-empty. But the Kubernetes answer is `*string` with
  `+optional`: controller-gen and DeepCopy handle pointers natively, whereas
  a struct-with-Valid renders in the CRD schema as a nested object that a
  person then has to write `{valid: true, value: "x"}` into.
- **`provider_marshal.go`** and **`schema.go`** import
  `terraform-plugin-sdk/v2/helper/schema` and marshal between Terraform's
  `ResourceData` and twilio-go's params structs. There is no `ResourceData`
  here; the equivalent is the generated `desired()`/`observation()` in each
  controller. Worth reading for its field-name handling, not copying.
- **`resources_flex_test.go`, `resources_serverless_test.go`** are the only
  resource tests in the repo — 2 resources of **164**, and nothing for
  api v2010.
  They are Terraform acceptance tests: HCL strings run under `TF_ACC` against
  a live Twilio account through `resource.Test`. The Crossplane equivalent is
  applying a YAML manifest to a cluster with a real ProviderConfig, which
  shares no code with this, only the idea. There is no shortcut here to take.

So: a real win on the transport (item 2 stops being work), the exact fix for
the riskiest item (item 1), and no help at all on resource-level testing —
which was the plausible hope and is worth knowing early rather than
discovering after a `cp -r`.

## Order of work

1. **Mechanical rename.** `generators/rt` → `generators/twilio`,
   `internal/clients/rt` → `…/twilio`, module path, `flake.nix` derivation
   names, `.gitmodules`, `Makefile` (`PROJECT_NAME`, `IMAGES`, `XPKGS`,
   `XPKG_REG_ORGS`), `hack/`. Delete the generated trees (`apis/`,
   `internal/controller/`, `package/crds`, `internal/clients/twilio/model_*`)
   — they are RT's and will be regenerated. Keep `client_test.go`, it is
   hand-written and is the template for the new client's test.
2. **Spec submodule.** `reference/twilio-oai`, `bin/generate` pointed at
   `reference/twilio-oai/spec/json/twilio_api_v2010.json`.
3. **The generator**, in the order the failures bite: pathType grouping (the
   big deletion), CRUD mapping with POST-as-update, Kind naming, the
   `observable` filter, external-name derivation, path-parameter fields,
   `upToDate` name normalisation, format and nullable type mapping.
4. **`client.mustache`**: basic auth, per-operation base URL, `IsNotFound`,
   and form encoding lifted from `terraform-provider-twilio/core` — its
   `form_encoder.go`, `query_encoder.go`, `tags.go`, `types.go`,
   `twilio_error.go` and the two encoder tests, into
   `internal/clients/twilio/`, with the MIT notice. Hand-written, so
   `bin/generate` must not clear them (it already clears
   `internal/clients/twilio/` file by file for exactly this reason).
5. **`check-coverage.py`** to the new rules, including the `upToDate`
   assertion from item 1.
6. `bin/generate` → read the diff → `make build` → `make package.check`.
7. Only then phase 2, and only if a second document is actually wanted.

## Known unknowns

- Whether openapi-generator's dotted-schema-name handling needs help (item 7)
  — one generate run answers it.
- Whether any api v2010 create requires a property its update does not accept,
  which would make `Update` send a field Twilio rejects. RT's generator
  assumes the two bodies agree; here they are described separately and have
  not been diffed.
- No live-account acceptance tests, and no `examples/` — the same gap both
  existing providers have, and confirmed above that
  `terraform-provider-twilio` has nothing to lend: 2 resources of 164, neither
  in api v2010, written as Terraform `resource.Test` cases. Twilio charges for
  some of these resources, which makes an acceptance suite a decision rather
  than an oversight.

[twilio/twilio-oai]: https://github.com/twilio/twilio-oai

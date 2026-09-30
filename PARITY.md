# Parity with terraform-provider-twilio

**Does the generator produce a provider that matches
terraform-provider-twilio's functionality?** `hack/parity.py` answers it by
measurement rather than by reading both and forming an impression.

```bash
hack/parity.py diff reference/twilio-oai/spec/json ../terraform-provider-twilio
```

## The method

Three catalogs of the same shape, and the differences between them:

- **spec** — what Twilio's *own* rules select from an OpenAPI document.
- **terraform** — what `terraform-provider-twilio` *actually* registers, read
  out of its generated Go.
- **crossplane** — what this repo generates, read out of `apis/` and
  `internal/controller/`. Read from the OUTPUT rather than from the generator
  on purpose: the question is what the provider does, and a catalog built from
  the same rules that produced it could only ever agree with itself.

`spec vs terraform` comes first and has to be **exact**. Without it, a
difference between this provider and Twilio's cannot be told apart from a
mistake in reading Twilio's, and a parity report that cannot fail is not
evidence of anything.

## Twilio's rules, which are not guessed

From [twilio/twilio-oai-generator]'s `TwilioTerraformGenerator`,
`Utility.populateCrudOperations`, `PathUtils` and `StringHelper` —
transcribed into `hack/parity.py`, function for function:

1. **Drop every operation whose `operationId` starts with `List`.**
   `opList.removeIf(co -> co.nickname.startsWith("List"))`.
2. **A resource is a path with its extension, its trailing path parameter and
   its parameter names removed.** `/2010-04-01/Accounts/{AccountSid}/Addresses.json`
   and `…/Addresses/{Sid}.json` both key on `/2010-04-01/Accounts/{}/Addresses`.
3. **The CRUD verb is the `operationId`'s prefix** — `Create`, `Fetch`,
   `Update`, `Patch`, `Delete`, else `Read` — **not the HTTP method and not
   `pathType`.** `POST …/Addresses` is the create because it is called
   `CreateAddress`; `POST …/Addresses/{Sid}` is the update because it is
   called `UpdateAddress`.
4. **Keep a resource only if it has `CREATE` *and* `FETCH` *and* `DELETE`.**
   `removeNonCrudResources`, whose `EnumSet.complementOf(of(UPDATE, READ))`
   deliberately does not require an update. This single rule is the whole
   difference between 179 resources and the 41 Kinds this repo's plan
   enumerates for api v2010 — see the decision at the bottom.

Two details that look like trivia and are not:

- `StringHelper.toSnakeCase` is **four** passes, and its
  `([A-Z])([A-Z][a-z])` pass is what makes `SIPDomains` into `sip_domains`.
  The two-pass `core.ToSnakeCase` inside terraform-provider-twilio itself
  gives `sipdomains`, which misnames 11 resources and reads as 11 missing and
  11 extra.
- `PathUtils.isInstanceOperation` reads `x-twilio.pathType` and falls back to
  "does the last segment end in `}`", splitting on `.json` first. That is
  exactly the fallback PLAN.md proposes for the 8 paths that declare no
  `pathType`, and it is Twilio's own.

## Controlling for spec drift

The provider is generated from a pinned document, so comparing it against
today's twilio-oai makes every difference ambiguous — a rule difference and a
spec that has moved on look identical. Against `main` the rule misses 5
resources the provider has and produces 33 it lacks; none of that is a rule
difference.

`terraform-provider-twilio 0.18.46` was generated from **twilio-oai 1.56.1**.
Matched by identical changelog entries, not by a date guess: both record
`[2024-06-06]` with `**Api** — Mark MaxPrice as obsolete`.

```bash
git -C reference/twilio-oai checkout 1.56.1
```

## The result

At twilio-oai 1.56.1 against terraform-provider-twilio 0.18.46:

| | |
|---|---|
| resources the rule selects | 179 |
| resources the provider registers | **164** |
| in both | **164** |
| provider has, rule misses | **0** |
| verb differences across all 164 | **0** |
| create-body fields missing from the provider's schema | **0** |

So the transcription is exact: every resource, every CRUD verb, and every
field of every create body agrees. The 15 the rule produces and the provider
lacks are all accounted for:

- **14 are the whole `preview` document**, and the reason is a fifth rule,
  found later by running the generator rather than by reading it:
  `AbstractTwilioGoGenerator.processOpenAPI` calls `clearTemplateFiles()` when
  `directoryStructureService.isVersionLess()`. `twilio_preview.json` and
  `twilio_iam_organizations.json` are the only two documents whose names carry
  no version, so both are silently dropped — the generator creates
  `twilio/resources/preview/` and writes nothing into it. That also explains
  why `build_twilio_library.py` special-cases `twilio_iam_organizations.json`
  to java and csharp only.

  `hack/parity.py` does not implement this rule: it is a property of the Go
  generators rather than of resource selection, and this provider will not
  inherit it unchanged — a versionless document is a document we can still
  generate Kinds from. Listed here as a known, explained difference.
- **1 is `twilio_api_accounts_outgoing_caller_ids`**, whose create is spelled
  `CreateValidationRequest`. It satisfies rule 4 and the provider still does
  not carry it. Unexplained, and therefore the single most useful regression
  test in the file: if a change to the rules makes it disappear for a reason,
  that reason is worth knowing.

### Getting a green result honestly

Both bugs found here were in the *reader*, not the rules, and both made
Twilio's provider look smaller than it is:

- The registry refers to a resource by package alias and function, and the
  function name alone is not unique — `ResourceServices` exists in `chat/v1`,
  `chat/v2`, `messaging/v1` and `notify/v1`. Keyed on the function alone, 41
  of 164 resources collapsed onto each other and read as missing.
- An array field is `AsList(AsString(SchemaRequired), SchemaRequired)`, so a
  regex that takes the first flag on the line reads the element's flag and
  loses the field. 16 resources reported phantom missing fields.

A parity script that under-reads the thing it compares against produces a
diff full of differences that are not there, and the temptation is to explain
them. The check on that is rule 4's counterpart: the provider's 164 must all
be found, and `provider has, rule misses` must be 0.

## This provider against theirs

`spec vs terraform` above establishes that the rules are read correctly. It
says nothing about whether *this* provider implements them, which is a
separate question and the one that matters:

```bash
hack/parity.py providers ../terraform-provider-twilio . api
```

For `twilio_api_v2010`, comparing the generated tree against
terraform-provider-twilio's generated Go:

| | |
|---|---|
| Kinds here | **22** |
| resources there | **22** |
| in both | **22** |
| fields settable there and absent here | **0** |
| fields settable there and only observed here | **0** |
| fields computed there and not observed here | **0** |
| CRUD differences | **0** |

The comparison accounts for one structural difference rather than reporting it
twenty-two times: Terraform keeps one flat schema per resource with
server-assigned fields marked `Computed`, while Crossplane splits the same
fields across `spec.forProvider` and `status.atProvider`. So Terraform's
`Computed` fields are compared against the observation and the rest against
the spec, and the resource's own id is excluded on both sides -- Terraform
carries it as a schema field, Crossplane as the `crossplane.io/external-name`
annotation.

**The check can fail.** Renaming one generated field and deleting another
reports both and exits 1:

```
twilio_api_accounts_addresses: settable in terraform, only observed here: ['city', 'street']
```

That is worth demonstrating rather than asserting, because a parity report
that cannot fail is the easiest thing in this repo to produce by accident --
see the two reader bugs below, both of which made Twilio's provider look
smaller than it is.

## Twilio's tests, and which of them are usable

Asked directly: the resource-level tests are **not** reusable, and the two
things that are have been used.

**Used.** `twilio-oai-generator`'s own unit tests for the functions
`hack/parity.py` transcribes -- `StringHelperTest.toSnakeCase` and
`PathUtilsTest` -- are lifted into `hack/parity_test.py` with their inputs
intact, and `bin/generate` runs them. Their vectors are better than anything
written here would have been: `callbackURL`, `SomeA2PThing`, `AwsS3Url` and
`Psd2Enabled` are exactly the acronym and digit boundaries where the two-pass
`ToSnakeCase` inside terraform-provider-twilio disagrees with the four-pass one
in the generator.

**Also run.** `mvn test` in the generator submodule, at the pinned commit: 29
tests, one error, and the error is `launchGenerator[5]` -- the **Python**
generator. `[7]`, the Terraform generator this repo extends, passes. Worth
knowing given that the same generator's Terraform path is dead on `main`.

**Not usable, with reasons.**

- `core/form_encoder_test.go` (254 lines), `core/query_encode_test.go`,
  `core/nullable_test.go` (646), `core/sids_test.go` (850) test a hand-written
  HTTP and marshalling layer. This provider has none: it uses `twilio-go`, as
  terraform-provider-twilio itself does, so there is no form encoder, no SID
  type and no nullable wrapper here to test. PLAN.md originally proposed
  copying the form encoder; adopting the SDK made that unnecessary and these
  tests along with it.
- `core/provider_marshal_test.go` (1046) marshals between Terraform's
  `ResourceData` and the SDK's params structs. There is no `ResourceData`
  here. Its one portable part is the snake-case test, which is above.
- `twilio/resources_flex_test.go` and `resources_serverless_test.go` (306) are
  the only resource tests in the provider -- 2 resources of 164, neither in
  `api v2010` -- and they are Terraform acceptance tests: HCL run under
  `TF_ACC` against a live account through `resource.Test`. The Crossplane
  equivalent is applying a manifest to a cluster with a real ProviderConfig,
  which shares the idea and no code.

So there is no behavioural test of this provider against a live Twilio
account, from them or from us. What the table above establishes is that the
two providers expose the same resources, the same fields on the same side of
the spec/status split, and the same CRUD operations -- not that a reconcile
does the same thing an apply does.

## The decision this leaves open

Twilio's rule 4 requires create, fetch and delete. This repo's plan selects
on a different rule — an instance GET, because a resource Crossplane cannot
read is one it cannot reconcile — and that keeps Kinds Twilio drops:
read-only ones (`Notifications`, `AvailablePhoneNumbers`), read-and-delete
ones (`Recordings`, `MessagesMedia`), and read-and-update ones
(`Conferences`, `ConnectApps`).

For api v2010 that is **41 Kinds against Twilio's 23** (23, not 22: the
provider omits `outgoing_caller_ids`).

Both are defensible and the difference is not an accident:

- **Terraform owns a lifecycle.** A resource it cannot create or destroy is
  not much use to it, so requiring create+fetch+delete is right there.
- **Crossplane also observes.** `crossplane-provider-rt` deliberately ships
  `Customrole` with no create and six rights Kinds with no delete, with the
  behaviour documented — deleting such a managed resource errors rather than
  letting Crossplane forget something that still exists. A read-only Kind is
  a legitimate way to pull external state into a composition.

So "precisely matching the terraform provider" means **dropping 18 of the 41**
Kinds, and it is a choice, not a consequence. If parity is the goal, rule 4
goes into the generator and this file becomes a build gate. If coverage is the
goal, the 18 stay and this file records them as known, intended differences.
Either way the 164 must be a superset of what we ship, or something is wrong
with the rules rather than with the ambition.

[twilio/twilio-oai-generator]: https://github.com/twilio/twilio-oai-generator

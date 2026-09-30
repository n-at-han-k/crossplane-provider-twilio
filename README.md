# crossplane-provider-twilio

A Crossplane provider for [Twilio], generated from [twilio-oai] by
[twilio-oai-generator] — Twilio's own openapi-generator, with a Crossplane
generator added on top of its Terraform one.

```bash
nix develop
bin/generate        # one Twilio document in, this repo out
```

Everything under `apis/`, `internal/` and `package/crds` is generated and
**committed**: the image compiles what is in the tree, not what a regeneration
would produce. Run `bin/generate`, read the diff, commit it.

## Where it comes from

Two submodules, so the commits this tree was generated from are recorded
rather than described in prose — `git submodule status` says which:

| | |
|---|---|
| `reference/twilio-oai` | the API description: 60 documents, one per product and version |
| `reference/twilio-oai-generator` | Twilio's generator, pinned to `0150189d` |

The pin matters and is not arbitrary; **the Terraform generator on `main` is
dead**, and at that commit the pipeline reproduces terraform-provider-twilio's
committed output byte for byte. [TOOLCHAIN.md](TOOLCHAIN.md) has the evidence
and the reasoning.

## What it covers

Twenty-two Kinds, from `twilio_api_v2010` — the core API: phone numbers,
messages, calls, keys, SIP, queues, applications, addresses.

They are **exactly** the 22 resources `terraform-provider-twilio` ships for
that document, and not by coincidence: the generator *inherits* Twilio's own
resource-selection rules rather than restating them, so parity is structural.
`bin/generate` asserts it, and [PARITY.md](PARITY.md) shows the measurement —
164 of 164 resources across all 60 documents, with every difference accounted
for by a named rule.

The other 59 documents are not wired up yet; see [PLAN.md](PLAN.md) for the
scope decision and what adding one costs.

## The generator

`generators/twilio` is one Java class extending
`com.twilio.oai.TwilioTerraformGenerator`, plus the templates. It is short
because almost everything hard is already answered upstream:

| | |
|---|---|
| which operations are one resource | the path minus its extension, trailing parameter and parameter names |
| which is the create, read, update, delete | the `operationId` prefix — Twilio updates with a POST to the instance path, so the HTTP method cannot tell |
| the external name | `x-resource-id`, the fetch's last path parameter; it is not always `sid` |
| a field only the update accepts | `x-update-after-create` |
| the account | `AccountSid` is rewritten to an optional parameter, so it comes from the credentials |

What this repo adds is the Crossplane shape: one Kind per resource, the
spec/status split, and the file layout.

**The request and response spell the same field differently.** Twilio takes
`FriendlyName` and answers `friendly_name`, so `spec.forProvider` uses the
first and `status.atProvider` the second — nothing translates between them, so
nothing can drift out of a translation layer. `upToDate` pairs them through
Twilio's own snake-case function. Compared by Go field name, nothing would
pair, every comparison would be vacuous, and **the provider would never detect
drift while compiling and reconciling perfectly**.

The API client is [twilio-go], Twilio's maintained SDK, which is also what
terraform-provider-twilio uses. Nothing here builds a URL, encodes a form,
signs a request or decodes an answer. `internal/clients/twilio` holds only
what the SDK does not know about: how a ProviderConfig spells its credentials,
and how to project an SDK struct onto `status.atProvider`.

## Credentials

A `ProviderConfig`'s secret is one JSON document. Twilio authenticates with
HTTP basic, and there are two things to put in it:

```yaml
stringData:
  credentials: |
    {
      "accountSid": "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
      "authToken":  "..."
    }
```

An API key is preferable, because it can be revoked without rotating the
account:

```yaml
stringData:
  credentials: |
    {
      "accountSid": "ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
      "apiKey":     "SKxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
      "apiSecret":  "..."
    }
```

`username`/`password` are accepted as aliases for whichever pair is in use, and
`edge`/`region` reach Twilio somewhere other than the default. There is no
endpoint to set: every operation's base URL comes from the SDK, and the API
description gives a different one per product.

## What it does not do yet

- **One document of sixty.** Adding another needs the cross-document
  aggregation step — the registrations that name every Kind — which Twilio
  also does outside its generator. See PLAN.md.
- **The CRD spells its fields the way Twilio does** — `spec.forProvider.FriendlyName`,
  not `friendlyName`. The wire name is the field name, which is deliberate;
  it does read oddly next to the rest of a Kubernetes manifest.
- **Nested objects and arrays become a `string` holding JSON.** A CRD cannot
  describe an arbitrary document, and Twilio answers several
  (`subresource_uris`, `links`, any `uri-map`).
- **A resource is only covered if it has a create, a read and a delete.** That
  is Twilio's rule, inherited. It drops read-only Kinds (`Notifications`),
  read-and-delete ones (`Recordings`) and read-and-update ones
  (`Conferences`) — 19 of api v2010's 41 possible Kinds. Whether a Crossplane
  provider should keep those is the open decision in PARITY.md.
- **A write-only field is never diffed.** 132 of api v2010's 225 create-body
  properties are ones Twilio does not echo back — `StatusCallback`,
  `SendDigits`, `SipAuthPassword`. Comparing them would report drift on every
  reconcile, so they are left alone.
- No acceptance tests against a live account, and no `examples/`. Twilio
  charges for some of these resources, which makes an acceptance suite a
  decision rather than an oversight.

[Twilio]: https://www.twilio.com
[twilio-oai]: https://github.com/twilio/twilio-oai
[twilio-oai-generator]: https://github.com/twilio/twilio-oai-generator
[twilio-go]: https://github.com/twilio/twilio-go

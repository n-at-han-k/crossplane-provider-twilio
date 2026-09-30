// Not a real module, and deliberately here.
//
// reference/ holds two submodule checkouts, and both contain Go: Twilio's
// generator ships example clients, and `mvn test` inside it writes generated
// Go to codegen/. Those files are not part of this provider, but `go mod tidy`
// walks every directory of the module it is run in, so they end up deciding
// its dependency graph -- a test run in a submodule pulled
// terraform-plugin-sdk and terraform-provider-twilio into this provider's
// go.mod.
//
// A go.mod makes Go stop descending here, which is the whole point. Nothing
// builds it.
module reference

go 1.25

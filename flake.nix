{
  # The shell the generator and the provider share: Twilio's openapi-generator
  # writes the Go, controller-gen and angryjet finish it, Go builds it.
  description = "Twilio Crossplane provider, generated from Twilio's own OpenAPI generator";
  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    utils.url = "github:numtide/flake-utils";
  };
  outputs = { self, nixpkgs, utils }:
    (utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in
      {
        devShells.default = pkgs.mkShell {
          buildInputs = with pkgs; [
            go
            gopls

            # bin/generate: the generated imports are whatever the document
            # made each resource need. Twilio's pipeline needs this too --
            # their raw output is space-indented with unsorted imports, and
            # terraform-provider-twilio's committed files match it only after
            # goimports has run. See TOOLCHAIN.md.
            gotools

            # Twilio's generator is a Maven project (reference/twilio-oai-generator,
            # a submodule). bin/generate builds its shaded jar and then javac's
            # our subclass against it.
            #
            # NOTE: deliberately NOT pkgs.openapi-generator-cli. Their jar is
            # shaded and carries the openapi-generator it was compiled against
            # (7.13.0); nixpkgs tracks the latest, and the skew between the two
            # is a NoSuchMethodError at generate time rather than a build
            # failure.
            maven
            jdk17

            # hack/parity.py reads the API description and the generated tree.
            (python3.withPackages (ps: [ ps.pyyaml ]))

            # `make generate` runs controller-gen and angryjet through `go
            # tool`, and `make build` needs docker for the image; the CLI is
            # what turns the result into an xpkg.
            gnumake
            crossplane-cli
            kubectl
          ];

          # A provider binary is pure Go, and cgo only costs a C compiler --
          # which this shell does not carry, so the default would fail on
          # runtime/cgo.
          shellHook = ''
            export CGO_ENABLED=0
          '';
        };
      }));
}

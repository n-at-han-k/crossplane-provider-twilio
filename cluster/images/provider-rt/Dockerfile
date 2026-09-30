# Distroless static, pinned by digest: the provider is a single static Go
# binary and needs no libc, no shell and no package manager. Nothing in the
# image can be exec'd into, which is the point.
FROM gcr.io/distroless/static@sha256:d9f9472a8f4541368192d714a995eb1a99bab1f7071fc8bde261d7eda3b667d8

ARG TARGETOS
ARG TARGETARCH

ADD bin/$TARGETOS\_$TARGETARCH/provider /usr/local/bin/crossplane-rt-provider

# 65532 is distroless' `nonroot`. The provider talks to the API server and an
# HTTP endpoint; it has no reason to be uid 0.
USER 65532
ENTRYPOINT ["crossplane-rt-provider"]

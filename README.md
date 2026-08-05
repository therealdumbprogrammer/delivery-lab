# delivery-lab

A deliberately small Spring Boot service (`/hello` + Actuator) used to build a
**complete local GitOps delivery loop** — Checkpoint 1 of the GitOps Delivery
video series.

```text
Application PR merged to main
   → GitHub Actions tests, builds, and pushes an image
   → workflow opens a PR pinning deploy/overlays/dev to the new sha256 digest
   → PR is reviewed and merged
   → Argo CD on Docker Desktop reconciles the local cluster from Git
```

CI has Git + registry access and **never** a kubeconfig. Argo CD watches **Git,
not the registry**. The only thing crossing the CI→CD boundary is a reviewable
Git commit.

## Layout

```text
delivery-lab/
├── app/                              # Spring Boot source, Dockerfile, test
├── deploy/
│   ├── base/                         # Deployment, Service, Gateway, HTTPRoute, kustomization
│   ├── overlays/dev/                 # image digest lives here
│   └── argocd/application-dev.yaml   # the Argo CD Application
└── .github/workflows/
    └── build-and-propose-dev.yml
```

## Access (Gateway API + NGINX Gateway Fabric)

The service is exposed through **Gateway API**, not `kubectl port-forward`. Two
one-time cluster installs (full commands in [RUNBOOK.md](RUNBOOK.md) §1.6):

```bash
# Gateway API CRDs (not built into Kubernetes) — ⚠ check current version
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.3.0/standard-install.yaml

# NGINX Gateway Fabric via Helm (install-only) — ⚠ check current chart
helm install ngf oci://ghcr.io/nginx/charts/nginx-gateway-fabric \
  --create-namespace -n nginx-gateway --set service.type=LoadBalancer
```

On Docker Desktop the `LoadBalancer` publishes on `localhost`, so once deployed:

```bash
curl http://localhost/hello
```

`deploy/base/gateway.yaml` (a `Gateway` on the `nginx` GatewayClass) and
`deploy/base/httproute.yaml` (routes `/` → the `delivery-lab` Service) are
ordinary manifests — Argo CD reconciles them alongside the Deployment.

## Start here

Follow **[RUNBOOK.md](RUNBOOK.md)** top to bottom. Viewers can start a clean
checkpoint from the tags:

- `cp1-start` — service + manifests, before Argo CD and CI
- `cp1-done` — the full loop working

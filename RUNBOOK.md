# delivery-lab demo runbook

This is the command sequence for demonstrating the delivery lab end to end.
The repository already contains the Spring Boot application, Kubernetes
manifests, Argo CD Application, and GitHub Actions workflow. Do not recreate
those files during the demo.

```text
manual deployment through Gateway API
  -> add the CI workflow
  -> install Argo CD and have it reconcile Git
  -> push an application change
  -> CI builds an image and opens a deployment PR
  -> merge the PR
  -> Argo CD deploys the approved digest
```

## Starting point

Before this runbook begins, you have already:

- Pushed the repository to GitHub.
- Created Docker Hub repository secrets named `DOCKERHUB_USERNAME` and
  `DOCKERHUB_TOKEN`. The token is a Docker Hub personal access token with
  push permission.
- Replaced `<DOCKERHUB_USER>` in the deployment and dev overlay with your
  Docker Hub username.
- Updated `deploy/argocd/application-dev.yaml` so `source.repoURL` points to
  your GitHub repository.

Also make the Docker Hub `delivery-lab` repository public. This lab does not
create Kubernetes image-pull credentials.

Set GitHub Actions permissions once: **Settings → Actions → General → Workflow
permissions** → **Read and write permissions**, then allow Actions to create
pull requests. The workflow needs that permission to open the deployment PR.

Confirm Docker Desktop Kubernetes is running before the demo:

```bash
kubectl config use-context docker-desktop
kubectl get nodes
```

Expect a `Ready` node.

## 1. Manual deployment through Gateway API

This first section establishes the manual-deployment baseline. It deliberately
does not use Argo CD or GitHub Actions.

### 1.1 Install the Gateway API prerequisites

Gateway API CRDs and NGINX Gateway Fabric are cluster-level prerequisites. The
application `Gateway` and `HTTPRoute` are ordinary app manifests.

```bash
kubectl kustomize \
  "https://github.com/nginx/nginx-gateway-fabric/config/crd/gateway-api/standard?ref=v2.6.7" \
  | kubectl apply -f -

helm install ngf oci://ghcr.io/nginx/charts/nginx-gateway-fabric \
  --create-namespace \
  -n nginx-gateway

kubectl wait --timeout=5m -n nginx-gateway \
  deployment/ngf-nginx-gateway-fabric --for=condition=Available
kubectl -n nginx-gateway get pods
kubectl get gatewayclass nginx
```

The first command installs the Gateway API standard-channel CRDs compatible
with the referenced Gateway Fabric release. Continue when the Gateway Fabric
pod is `Running` and the `nginx`
GatewayClass is accepted. The Helm chart installs the Gateway Fabric control
plane. When this lab's `Gateway` is later reconciled, it provisions the NGINX
data plane and its LoadBalancer Service; Docker Desktop exposes that service on
`localhost`.

### 1.2 Build and publish the initial image

The checked-in dev overlay starts with `newTag: v1`, so publish that tag:

```bash
cd app
mvn clean verify
docker build -t "docker.io/YOUR_DOCKERHUB_USER/delivery-lab:v1" .
docker push "docker.io/YOUR_DOCKERHUB_USER/delivery-lab:v1"
cd ..
```

Replace `YOUR_DOCKERHUB_USER` with the same value configured in the manifests.

### 1.3 Apply the Kustomize overlay and test it

Create the namespace, inspect the rendered image, then deploy the overlay:

```bash
kubectl create namespace dev --dry-run=client -o yaml | kubectl apply -f -
kubectl kustomize deploy/overlays/dev | grep 'image:'
kubectl apply -k deploy/overlays/dev

kubectl -n dev rollout status deployment/delivery-lab --timeout=180s
kubectl -n dev get deployment,service,gateway,httproute
curl -fsS http://localhost/hello
curl -fsS http://localhost/actuator/health
```

The greeting and an `UP` health response prove the app is reachable through
Gateway API. At this point, deployment is manual: a person builds an image and
runs `kubectl apply -k`.

## 2. Add CI, then install Argo CD

### 2.1 Commit the existing GitHub Actions workflow

The workflow is already present locally at
`.github/workflows/build-and-propose-dev.yml`. Add it to `master`; GitHub shows a
workflow only after this file exists on the default branch.

It is normal for this push not to run the workflow: it has a `paths: app/**`
filter, so only application changes start a build. This prevents a merged
deployment PR from creating another build.

### 2.2 Install Argo CD

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update
helm upgrade --install argocd argo/argo-cd \
  --namespace argocd \
  --create-namespace

kubectl -n argocd rollout status deployment/argocd-server --timeout=300s
kubectl -n argocd get pods
```

### 2.3 Open the Argo CD UI

In one terminal, get the initial password and start the local UI tunnel:

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d; echo
kubectl -n argocd port-forward svc/argocd-server 8080:443
```

Open `https://localhost:8080`, accept the local certificate, and sign in as
`admin` using the printed password.

### 2.4 Give Argo CD responsibility for dev

Apply the already-committed Application manifest:

```bash
kubectl apply -f deploy/argocd/application-dev.yaml
kubectl -n argocd get application delivery-lab-dev -w
```

Argo CD immediately renders the dev overlay from `master` and reconciles it.

## 3. CI-to-CD handoff

### 3.1 Change the application and push

Edit app code and push. CI tests the application, builds an image, pushes it to Docker Hub, and
opens a deployment PR. It does **not** deploy to Kubernetes and it does **not**
write directly to `master`.

### 3.2 Review and merge the deployment proposal

The proposed change should only replace the dev overlay's `newTag`/`digest`
with an immutable `sha256:` digest. Merge it after reviewing:

### 3.3 Watch Argo CD deploy the approved image

```bash
kubectl -n dev get deployment delivery-lab \
  -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
curl -fsS http://localhost/hello
```

The image now has an `@sha256:...` reference and `/hello` returns the updated
message.

## Normal change path

For every later application change:

1. Commit and push the `app/**` change to `master`.
2. Let GitHub Actions test, build, push, and open the deployment PR.
3. Review and merge the PR.
4. Let Argo CD reconcile `master` to the local cluster.
5. Verify through `http://localhost/hello`.

Avoid manually changing the dev digest or running `kubectl apply -k` for normal
updates. Those are only part of the manual-baseline section; Git and the
deployment PR are the steady-state deployment path.

## 4. Checkpoint 2: Trust and recover from an automated dev deployment

This checkpoint proves that `Synced` means Argo CD applied the Git revision; it
does not prove the new release is usable. The application exposes separate
startup, liveness, and readiness checks so that a container crash can be
distinguished from a running but unavailable Pod.

Do not repair either scenario with `kubectl`. The Deployment must recover from
a Git change so the demo follows the same control path as a real dev release.
Before starting, deploy this branch's failure-switch support as an ordinary
healthy application release through the workflow in section 3.

### 4.1 Record a known-good release

Start from the successful Checkpoint 1 release. In one terminal, record the
current immutable image reference and confirm the application is healthy:

```bash
KNOWN_GOOD_IMAGE="$(kubectl -n dev get deployment delivery-lab \
  -o jsonpath='{.spec.template.spec.containers[0].image}')"
printf '%s\n' "$KNOWN_GOOD_IMAGE"

kubectl -n argocd get application delivery-lab-dev
kubectl -n dev get deployment,pods -l app=delivery-lab
curl -fsS http://localhost/hello
curl -fsS http://localhost/actuator/health/readiness
```

The Argo CD Application should be `Synced` and `Healthy`; the Pod should be
`1/1 Ready`. Keep `KNOWN_GOOD_IMAGE` in this terminal for the rollback check.

### 4.2 Ship a deliberate startup failure

The startup-failure patch is checked in but inactive. Create a configuration
PR that adds it to the dev overlay's `kustomization.yaml`:

```yaml
patches:
  - path: checkpoint-2-startup-failure.yaml
```

The patch appends `--delivery-lab.demo.failure-mode=startup` to the existing
container entrypoint. Merge the configuration PR, then watch the release:

```bash
kubectl -n argocd get application delivery-lab-dev -w
```

Argo CD can become `Synced` even though the Deployment remains `Progressing`
or becomes `Degraded`. Stop the watch once that distinction is visible.

### 4.3 Diagnose from Argo CD to the process

Follow this order rather than starting with arbitrary commands:

```text
Argo CD Application → Deployment / ReplicaSet → Pod state → events → logs
```

```bash
kubectl -n argocd get application delivery-lab-dev -o yaml
kubectl -n dev rollout status deployment/delivery-lab --timeout=90s || true
kubectl -n dev get deployment,replicaset,pods -l app=delivery-lab
kubectl -n dev describe pod -l app=delivery-lab
kubectl -n dev logs POD_NAME --previous
```

The new container exits with `Checkpoint 2 deliberate startup failure` and
enters `CrashLoopBackOff`. Replace `POD_NAME` with that new failing Pod from
the preceding list. The prior ready replica may remain serving traffic while
the rolling update cannot make the bad replacement available.

### 4.4 Roll back the desired state

Use GitHub's **Revert** action on the merged configuration PR that introduced the
startup-failure patch, then merge the resulting rollback PR. This removes the
patch and restores the exact known-good desired state without rebuilding an
image.

```bash
kubectl -n argocd get application delivery-lab-dev -w
```

After the Application is `Synced` and `Healthy`, verify that it returned to the
same digest captured before the incident:

```bash
CURRENT_IMAGE="$(kubectl -n dev get deployment delivery-lab \
  -o jsonpath='{.spec.template.spec.containers[0].image}')"
test "$CURRENT_IMAGE" = "$KNOWN_GOOD_IMAGE" && echo "rolled back to known-good image"
kubectl -n dev get pods -l app=delivery-lab
curl -fsS http://localhost/hello
```

### 4.5 Distinguish a readiness failure from a crash

Repeat the same flow, but create a configuration PR that adds this patch to
the dev overlay's `kustomization.yaml` instead:

```yaml
patches:
  - path: checkpoint-2-readiness-failure.yaml
```

After merging, the process remains running and its liveness endpoint stays
`UP`, but its readiness endpoint reports unavailable. Observe the difference:

```bash
kubectl -n dev get pods -l app=delivery-lab -w
kubectl -n dev describe pod -l app=delivery-lab
```

The replacement Pod is `Running` but `0/1 Ready`; it is not a
`CrashLoopBackOff`. Kubernetes keeps it out of Service endpoints. Revert that
configuration PR, then verify the known-good image and a ready Pod.

## Quick diagnosis

| Symptom | Check |
| --- | --- |
| Pod cannot pull or start | `kubectl -n dev describe pod -l app=delivery-lab` |
| App is unhealthy | `kubectl -n dev logs deployment/delivery-lab` |
| Argo is Synced but not Healthy | Follow section 4.3 from Application to logs |
| Pod is running but unavailable | `kubectl -n dev describe pod -l app=delivery-lab` |
| Gateway returns 502 | `kubectl -n dev describe httproute delivery-lab` |
| `localhost` refuses connections | `kubectl -n nginx-gateway get svc` |
| Argo is not synced | `kubectl -n argocd get application delivery-lab-dev -o yaml` |
| CI cannot push | Verify both repository secrets and Docker Hub repository visibility |
| CI cannot open a PR | Recheck the GitHub Actions workflow-permissions setting |

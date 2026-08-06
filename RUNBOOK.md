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

## Starting point: already completed

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

```bash
git add .github/workflows/build-and-propose-dev.yml
git commit -m "ci: build image and propose a dev deployment"
git push

gh workflow list
```

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

Argo CD immediately renders the dev overlay from `master` and reconciles it. In
the UI, show that the Application is `Synced` and `Healthy`; it is now watching
Git and waiting for a future approved desired-state change.

Stop the watch with `Ctrl-C`. The Application resource is in `argocd`; the app
resources are in `dev`. The Application's `CreateNamespace=true` setting also
makes the namespace creation safe on a clean cluster.

## 3. Demonstrate the CI-to-CD handoff

### 3.1 Change the application and push

Edit `app/src/main/java/dev/deliverylab/HelloController.java` so `/hello`
returns a visibly different greeting. Then commit and push only that app change:

```bash
git add app
git commit -m "feat: update greeting"
git push

gh run watch
```

The run tests the application, builds an image, pushes it to Docker Hub, and
opens a deployment PR. It does **not** deploy to Kubernetes and it does **not**
write directly to `master`.

### 3.2 Review and merge the deployment proposal

List the pull request created by CI and inspect its diff:

```bash
gh pr list
gh pr view NUMBER
```

The proposed change should only replace the dev overlay's `newTag`/`digest`
with an immutable `sha256:` digest. Merge it after reviewing:

```bash
gh pr merge --squash --delete-branch NUMBER
```

Replace `NUMBER` with the deployment PR number. The merge changes
`deploy/**`, so the workflow does not build again.

### 3.3 Watch Argo CD deploy the approved image

```bash
kubectl -n argocd get application delivery-lab-dev -w
```

When the Application returns to `Synced` and `Healthy`, stop the watch and
verify the result through the same Gateway API route:

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

## Quick diagnosis

| Symptom | Check |
| --- | --- |
| Pod cannot pull or start | `kubectl -n dev describe pod -l app=delivery-lab` |
| App is unhealthy | `kubectl -n dev logs deployment/delivery-lab` |
| Gateway returns 502 | `kubectl -n dev describe httproute delivery-lab` |
| `localhost` refuses connections | `kubectl -n nginx-gateway get svc` |
| Argo is not synced | `kubectl -n argocd get application delivery-lab-dev -o yaml` |
| CI cannot push | Verify both repository secrets and Docker Hub repository visibility |
| CI cannot open a PR | Recheck the GitHub Actions workflow-permissions setting |

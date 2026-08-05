# delivery-lab runbook

This is an operator runbook: follow it to run the application end to end. It
assumes the application, Kubernetes manifests, Argo CD Application, and GitHub
workflow already in this repository are the desired implementation. It does not
ask you to recreate them.

The completed flow is:

```text
push an app change to main
  -> GitHub Actions tests, builds, and pushes an image
  -> Actions opens a deployment PR containing the image digest
  -> merge that PR
  -> Argo CD syncs the dev namespace on Docker Desktop
  -> curl http://localhost/hello
```

## 1. Before you start

You need Docker Desktop with Kubernetes enabled, `kubectl`, `helm`, `docker`,
`gh`, Java 21+ (or a compatible JDK), and a Docker Hub account. Log in to GitHub
CLI and Docker Hub:

```bash
gh auth login
docker login
kubectl config use-context docker-desktop
kubectl get nodes
```

The final command must show a `Ready` node. If the `docker-desktop` context is
missing, enable Kubernetes in Docker Desktop first and wait for it to finish
starting.

Choose a GitHub repository and Docker Hub namespace. Replace both example
values before running the commands below:

```bash
export GITHUB_REPO="YOUR_GITHUB_USER/delivery-lab"
export DOCKERHUB_USER="YOUR_DOCKERHUB_USER"
```

The Docker Hub repository must be public, because this lab does not create an
image-pull secret for the cluster.

## 2. Configure this copy of the repository

Run these commands from the repository root. They replace the checked-in image
placeholder and point Argo CD at *your* GitHub repository:

```bash
cd /Users/vivek/work/k8s-workshop/Advance/delivery-lab

sed -i '' "s|<DOCKERHUB_USER>|${DOCKERHUB_USER}|g" \
  deploy/base/deployment.yaml deploy/overlays/dev/kustomization.yaml
sed -i '' "s|https://github.com/thecodealchemist/delivery-lab|https://github.com/${GITHUB_REPO}|" \
  deploy/argocd/application-dev.yaml

rg -n '<DOCKERHUB_USER>|thecodealchemist/delivery-lab' deploy
```

The final search should print no matches. On Linux, use `sed -i` instead of
macOS's `sed -i ''`.

### Create or connect the GitHub repository

If this directory is not already a clone with an `origin` remote, initialize it
and create the GitHub repository. The current workshop copy may be in this
state:

```bash
git init -b main
gh repo create "$GITHUB_REPO" --public --description "Local GitOps delivery lab"
git remote add origin "https://github.com/${GITHUB_REPO}.git"
```

If you already cloned a repository, do not run those commands. Instead verify
that its remote is the repository named in `GITHUB_REPO`:

```bash
git remote -v
```

Before the first push, add the two repository secrets. `DOCKERHUB_TOKEN` must
be a Docker Hub access token with permission to push images; do not put the
token in a file or Git commit.

```bash
gh secret set DOCKERHUB_USERNAME --repo "$GITHUB_REPO"
gh secret set DOCKERHUB_TOKEN --repo "$GITHUB_REPO"
gh secret list --repo "$GITHUB_REPO"
```

In GitHub, also open **Settings → Actions → General → Workflow permissions**,
select **Read and write permissions**, and allow GitHub Actions to create pull
requests. The workflow needs this to create the deployment proposal.

## 3. Build and publish the initial image

Build the checked-in application, publish a bootstrap tag, and obtain its
immutable digest:

```bash
cd app
./mvnw -B verify
docker build -t "docker.io/${DOCKERHUB_USER}/delivery-lab:bootstrap" .
docker push "docker.io/${DOCKERHUB_USER}/delivery-lab:bootstrap"

export DIGEST="$(docker buildx imagetools inspect \
  "docker.io/${DOCKERHUB_USER}/delivery-lab:bootstrap" \
  --format '{{.Manifest.Digest}}')"
printf '%s\n' "$DIGEST"
```

After the first push, ensure the `delivery-lab` repository is **public** in
Docker Hub. The last line must begin with `sha256:`. Pin the dev overlay to that
digest:

```bash
cd ..
sed -i '' "s|    newTag: v1|    digest: ${DIGEST}|" \
  deploy/overlays/dev/kustomization.yaml

kubectl kustomize deploy/overlays/dev | rg "image:.*@sha256:"
```

The render must contain one digest-pinned image. This bootstrap edit is the
only time you set the digest by hand; subsequent digest updates are proposed by
CI in a pull request.

## 4. Push the desired state to GitHub

Commit and push the repository, including the image configuration, Argo CD
Application, and workflow:

```bash
git add .
git commit -m "chore: configure delivery lab"
git push -u origin main
```

The push changes `app/**`, so GitHub Actions may start immediately. It can open
a deployment PR after the image build; that is expected. Check it with:

```bash
gh run list --repo "$GITHUB_REPO" --limit 3
gh pr list --repo "$GITHUB_REPO"
```

After the run passes, merge that initial deployment PR before continuing. Its
branch changes only the generated digest, and merging it means `main` contains
the exact image CI built before Argo CD begins watching it:

```bash
gh pr merge --repo "$GITHUB_REPO" --squash --delete-branch NUMBER
```

Replace `NUMBER` with the PR number shown by `gh pr list`.

## 5. Install the cluster prerequisites

Install Gateway API CRDs and NGINX Gateway Fabric. These are cluster-level
dependencies; the application `Gateway` and `HTTPRoute` remain Git-managed
manifests.

```bash
kubectl apply -f \
  https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.3.0/standard-install.yaml

helm upgrade --install ngf oci://ghcr.io/nginx/charts/nginx-gateway-fabric \
  --namespace nginx-gateway \
  --create-namespace \
  --set service.type=LoadBalancer

kubectl -n nginx-gateway get pods
kubectl get gatewayclass nginx
```

Wait until the Gateway Fabric pod is `Running` and the `nginx` GatewayClass is
accepted before continuing.

Install Argo CD:

```bash
helm repo add argo https://argoproj.github.io/argo-helm
helm repo update
helm upgrade --install argocd argo/argo-cd \
  --namespace argocd \
  --create-namespace

kubectl -n argocd rollout status deployment/argocd-server --timeout=300s
kubectl -n argocd get pods
```

`upgrade --install` makes these installation commands safe to run again.

## 6. Bootstrap Argo CD and deploy dev

Create the Argo CD `Application` in the local cluster:

```bash
kubectl apply -f deploy/argocd/application-dev.yaml
kubectl -n argocd get application delivery-lab-dev -w
```

When the application reports `Synced` and `Healthy`, stop the watch with
`Ctrl-C` and verify the workload and route:

```bash
kubectl -n dev rollout status deployment/delivery-lab --timeout=180s
kubectl -n dev get deployment,service,gateway,httproute
curl -fsS http://localhost/hello
curl -fsS http://localhost/actuator/health
```

Expected results are the greeting and a health response containing `UP`.

Argo CD creates the `dev` namespace itself because the Application specifies
`CreateNamespace=true`. The Application resource lives in `argocd`; the app
resources live in `dev`.

## 7. Prove the CI-to-CD path

Make a small application-only change, commit it, and push it. For example,
change the greeting in `app/src/main/java/dev/deliverylab/HelloController.java`.

```bash
git add app
git commit -m "feat: update greeting"
git push

gh run watch --repo "$GITHUB_REPO"
gh pr list --repo "$GITHUB_REPO"
```

Wait for the workflow to pass. It tests the app, pushes an image tagged with
the source commit, and opens a PR that changes only
`deploy/overlays/dev/kustomization.yaml`'s digest. Review the PR diff, then
merge it:

```bash
gh pr merge --repo "$GITHUB_REPO" --squash --delete-branch NUMBER
kubectl -n argocd get application delivery-lab-dev -w
```

Replace `NUMBER` with the deployment PR number. Once Argo reports `Synced` and
`Healthy`, stop the watch and confirm the new deployment:

```bash
kubectl -n dev get deployment delivery-lab \
  -o jsonpath='{.spec.template.spec.containers[0].image}'; echo
curl -fsS http://localhost/hello
```

The deployment image should use `@sha256:...`, never `:latest`.

## 8. Normal operation

For each application change:

1. Commit and push only the application change to `main`.
2. Wait for the `build-and-propose-dev` workflow to pass.
3. Review and merge its deployment PR.
4. Verify Argo CD becomes `Synced`/`Healthy` and test the route.

Do not manually edit the dev digest during normal operation, and do not deploy
application changes with `kubectl apply`. Git and the deployment PR are the
change path; `kubectl` is for verification and diagnosis.

## Quick diagnosis

| Symptom | Check |
| --- | --- |
| App stays `OutOfSync` | `kubectl -n argocd get application delivery-lab-dev -o yaml` |
| Pod cannot start or pull | `kubectl -n dev describe pod -l app=delivery-lab` |
| App is unhealthy | `kubectl -n dev logs deployment/delivery-lab` |
| Gateway returns 502 | `kubectl -n dev describe httproute delivery-lab` |
| `localhost` refuses connections | `kubectl -n nginx-gateway get svc` and `kubectl -n dev get gateway delivery-lab-gateway` |
| CI cannot push an image | `gh secret list --repo "$GITHUB_REPO"`; replace the Docker Hub token if needed |
| CI cannot create a PR | Recheck the GitHub Actions workflow-permissions setting in step 2 |

## Optional: view Argo CD

```bash
kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath='{.data.password}' | base64 -d; echo
kubectl -n argocd port-forward svc/argocd-server 8080:443
```

Open `https://localhost:8080`, accept the local certificate, and sign in as
`admin` with the printed password.

# Secure Google Play deployment

The release workflow builds the `gmsRelease` bundle from a version tag, signs it
with the Play **upload key**, verifies that certificate, then publishes through
the official Android Publisher API. Google Play App Signing still signs the APKs
delivered to users. The upload key is only the identity of an accepted upload.

No Google service-account JSON is stored in GitHub. GitHub Actions exchanges its
OIDC identity for a 10-minute Google access token through Workload Identity
Federation. The only long-lived secrets are the upload keystore and its
passwords, held in protected GitHub Environments.

## 1. Create the Google identities

Use a dedicated Google Cloud project. Replace the placeholders, then run:

```bash
export GCP_PROJECT_ID="your-project-id"
export GCP_PROJECT_NUMBER="$(gcloud projects describe "${GCP_PROJECT_ID}" --format='value(projectNumber)')"
export WIF_POOL_ID="github-actions"
export WIF_PROVIDER_ID="observation-companion"

gcloud services enable androidpublisher.googleapis.com iamcredentials.googleapis.com sts.googleapis.com \
  --project "${GCP_PROJECT_ID}"

gcloud iam service-accounts create github-play-internal \
  --project "${GCP_PROJECT_ID}" \
  --display-name "Observation Companion internal releases"
gcloud iam service-accounts create github-play-production \
  --project "${GCP_PROJECT_ID}" \
  --display-name "Observation Companion production releases"

gcloud iam workload-identity-pools create "${WIF_POOL_ID}" \
  --project "${GCP_PROJECT_ID}" \
  --location global \
  --display-name "GitHub Actions"

gcloud iam workload-identity-pools providers create-oidc "${WIF_PROVIDER_ID}" \
  --project "${GCP_PROJECT_ID}" \
  --location global \
  --workload-identity-pool "${WIF_POOL_ID}" \
  --issuer-uri "https://token.actions.githubusercontent.com" \
  --attribute-mapping "google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.repository_owner_id=assertion.repository_owner_id,attribute.ref=assertion.ref,attribute.workflow_ref=assertion.workflow_ref,attribute.environment=assertion.environment" \
  --attribute-condition "assertion.repository_id=='1253416570' && assertion.repository_owner_id=='37419029' && assertion.ref.startsWith('refs/tags/v') && assertion.workflow_ref.startsWith('Ret2Me/observation-companion/.github/workflows/play-release.yml@refs/tags/v') && (assertion.environment=='google-play-internal' || assertion.environment=='google-play-production')"
```

The condition uses immutable GitHub repository and owner IDs, the tag ref,
workflow path and selected environment. Renaming or recreating a repository
cannot silently inherit this trust.

Allow each GitHub Environment to impersonate only its matching service account:

```bash
gcloud iam service-accounts add-iam-policy-binding \
  "github-play-internal@${GCP_PROJECT_ID}.iam.gserviceaccount.com" \
  --project "${GCP_PROJECT_ID}" \
  --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/${GCP_PROJECT_NUMBER}/locations/global/workloadIdentityPools/${WIF_POOL_ID}/attribute.environment/google-play-internal"

gcloud iam service-accounts add-iam-policy-binding \
  "github-play-production@${GCP_PROJECT_ID}.iam.gserviceaccount.com" \
  --project "${GCP_PROJECT_ID}" \
  --role roles/iam.workloadIdentityUser \
  --member "principalSet://iam.googleapis.com/projects/${GCP_PROJECT_NUMBER}/locations/global/workloadIdentityPools/${WIF_POOL_ID}/attribute.environment/google-play-production"
```

Do not grant either account Google Cloud `Owner` or `Editor`.

## 2. Grant Google Play permissions

In Play Console, open **Users and permissions**, invite both service-account
email addresses and grant access only to `pl.put.observationcompanion`.

For the internal account grant **Release apps to testing tracks**. For the
production account grant **Release to production, exclude devices, and use Play
App Signing**. Avoid global account permissions.

The Android Publisher API must also be enabled for the Cloud project. Modern
Play Console access does not require linking the project under API access.

## 3. Protect GitHub Environments

Create `google-play-internal` and `google-play-production` under **Settings >
Environments**. On both:

* restrict deployment branches and tags to selected tags matching `v*`;
* disable administrator bypass;
* add a required reviewer, especially for production;
* enable prevention of self-review only when another trusted maintainer can
  approve releases.

Add these environment variables to both environments:

| Variable | Value |
| --- | --- |
| `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/github-actions/providers/observation-companion` |
| `GCP_SERVICE_ACCOUNT` | the matching internal or production account email |
| `PLAY_UPLOAD_KEY_ALIAS` | upload-key alias, commonly `upload` |
| `PLAY_UPLOAD_CERT_SHA256` | SHA-256 fingerprint of the upload certificate shown in Play Console |

Add these environment secrets to both environments:

* `PLAY_UPLOAD_KEYSTORE_B64`
* `PLAY_UPLOAD_STORE_PASSWORD`
* `PLAY_UPLOAD_KEY_PASSWORD`

Upload them without putting secret values in shell history:

```bash
base64 -w 0 /absolute/path/to/upload-key.jks \
  | gh secret set PLAY_UPLOAD_KEYSTORE_B64 --env google-play-internal
base64 -w 0 /absolute/path/to/upload-key.jks \
  | gh secret set PLAY_UPLOAD_KEYSTORE_B64 --env google-play-production

gh secret set PLAY_UPLOAD_STORE_PASSWORD --env google-play-internal
gh secret set PLAY_UPLOAD_KEY_PASSWORD --env google-play-internal
gh secret set PLAY_UPLOAD_STORE_PASSWORD --env google-play-production
gh secret set PLAY_UPLOAD_KEY_PASSWORD --env google-play-production
```

Keep an encrypted, offline backup of the upload keystore. Never add it, its
base64 representation or a Google credential JSON to this repository.

## 4. Protect the release source

Create a repository ruleset for `main` and tags matching `v*`:

* require pull requests and status checks on `main`;
* require CODEOWNERS review for workflow, publisher script and Gradle changes;
* block force pushes and deletion of release tags;
* restrict creation of `v*` tags to maintainers;
* require signed commits or signed tags if the team already has a signing policy.

Actions settings should allow only selected actions or verified creators. The
workflow pins every action to a full commit SHA. Dependabot is configured to
propose future action updates for review.

## 5. Release

First update `versionCode`, `versionName` and
`fastlane/metadata/android/en-US/changelogs/VERSION_CODE.txt`, then merge to
`main`. Create the version tag on that exact commit:

```bash
git switch main
git pull --ff-only
git tag -a v1.3 -m "Observation Companion 1.3"
git push origin v1.3
```

A tag push publishes a completed release to the internal track after the
`google-play-internal` environment gate. No signed bundle is retained as a
public Actions artifact.

After testing, open **Actions > Publish to Google Play > Run workflow**, select
the same version tag, choose `production`, choose a rollout fraction and enter
the exact tag in `confirm_tag`. Approve the protected production environment.

The publisher refuses to overwrite an existing draft, halted or in-progress
release. Resolve such a release in Play Console first. It also validates the edit
before committing and refuses to cancel changes that are already in review.

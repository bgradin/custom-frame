# Custom Frame host API

This guide describes the HTTP interface a custom host must provide for the Custom Frame Android app. You can implement it with any server framework or hosting platform that can serve authenticated JSON and public APK files over HTTPS.

The host supplies photo-provider credentials and an app release catalog. Photos are downloaded directly from Dropbox; the host does not serve or proxy photos. Dropbox is currently the only supported photo provider. Configuration is shared by frames authorized to use the host; the interface does not define device accounts, enrollment, or administration endpoints.

The behavior below reflects the app code in this repository. In particular, update checks are initiated from Settings, read only the first release-history page, and open Android's confirmation UI for installation.

## Connect a frame

1. Deploy the two endpoints below and publish any APKs you want to offer.
2. Create an auth key on your host and approve it for configuration access.
3. In the app's Settings, enter **Server domain** (for example, `https://frames.example.com`) and **Auth key**, then refresh or save.
4. Set the Dropbox folder locally on the frame. The host supplies the Dropbox access token, while the folder and slideshow settings remain on the device.

The server setting accepts an HTTPS origin with an optional port, such as `https://frames.example.com:8443`. A trailing `/` is accepted; paths, query strings, fragments, and embedded credentials are rejected. The app normalizes hostnames to lowercase and removes the default port `443`.

The app appends these fixed paths:

| Method | Path | Authentication | Purpose |
| --- | --- | --- | --- |
| GET | `/api/custom-frame/v1/config` | `AuthKey` header | Photo-provider configuration and recommended app version |
| GET | `/api/custom-frame/v1/releases` | Public | Release history and APK metadata |
| GET | Absolute URL from release metadata | Public | APK download; may use a separate host |

Use certificates trusted by the Android devices you support. **Redirects are not followed**, including redirects to another HTTPS URL. Serve each endpoint and APK directly at its advertised URL. Do not require cookies, browser sign-in, or additional authorization for release history or APKs.

A blank server domain selects manual Dropbox configuration instead. A new server configuration is activated only after a successful fetch and validation.

## Configuration

Request:

```http
GET /api/custom-frame/v1/config HTTP/1.1
Host: frames.example.com
AuthKey: EXAMPLE_LONG_LIVED_AUTH_KEY
```

Successful response:

```http
HTTP/1.1 200 OK
Content-Type: application/json; charset=utf-8
Cache-Control: no-store
```

```json
{
  "v1": {
    "appVersion": "1.1.0",
    "appVersionCode": 2,
    "sources": {
      "dropbox": {
        "name": "Dropbox",
        "accessToken": "EXAMPLE_READ_ONLY_ACCESS_TOKEN"
      }
    }
  }
}
```

| Field | Type | Requirement |
| --- | --- | --- |
| `v1` | Object | Required configuration group read by the current app |
| `v1.appVersion` | Nonblank string | Recommended release's Android version name |
| `v1.appVersionCode` | Positive integer | Recommended release's Android version code, at most 2,147,483,647 |
| `v1.sources` | Object | Provider configurations keyed by stable provider identifier |
| `v1.sources.dropbox` | Object | Optional; omit when Dropbox is unavailable |
| `v1.sources.dropbox.name` | Nonblank string | Required when Dropbox is present; shown in Settings |
| `v1.sources.dropbox.accessToken` | Nonblank string | Required when Dropbox is present; used directly with Dropbox |

The version name and code must match the same published release record exactly. The app orders releases using the integer code, never the display name. Version names need not follow semantic versioning. Do not publish configuration referencing a release that is missing from the catalog.

An empty `sources` object is valid and offers no photo source. Unknown provider keys are ignored; adding one does not add provider support to the app. A malformed Dropbox entry invalidates the response. Dropbox is selected automatically when first available, using the stable key `dropbox`; changing its display name does not change that selection.

Provide a Dropbox token with the metadata/content read permissions and folder access needed by the frame. Return access tokens only, never refresh tokens or application secrets. Your host is responsible for renewing or rotating credentials. The app does not perform OAuth sign-in or token renewal.

### Refresh and offline behavior

The app fetches configuration on startup, when server settings are saved or explicitly refreshed, before photo sync, and when an update check is requested. Background photo sync is scheduled roughly hourly, subject to Android scheduling. After a failed background refresh, normal refresh attempts wait 15 minutes; explicit refreshes and update checks bypass that delay. The current client does not use `Retry-After` to calculate this delay.

Validated configuration is stored in app-private preferences, scoped to the normalized server origin along with its auth key and source selection. On network failure or invalid configuration, the app retains the previous valid snapshot. A failed first setup leaves the previous mode active. Removing Dropbox from a successfully fetched configuration stops new photo downloads while preserving cached photos.

A `401` marks the auth key as rejected and suppresses normal background configuration refreshes until an explicit attempt succeeds. **Already delivered Dropbox tokens remain cached and may still be used for photo sync.** Revoking an auth key prevents future configuration retrieval; to revoke photo access too, revoke or rotate the Dropbox token separately. If Dropbox rejects a token, the app forces one configuration refresh and retries the folder listing once.

Every update check requires a fresh, successful authenticated configuration fetch. Cached configuration alone cannot authorize an update download. Cached photos remain available for offline playback.

## Release catalog

Request:

```http
GET /api/custom-frame/v1/releases HTTP/1.1
Host: frames.example.com
```

Return `200 OK` with `Content-Type: application/json`. This illustrative record describes a hypothetical release; replace the URL, size, hash, and device rules with values for your actual APK:

```json
{
  "releases": [
    {
      "appVersion": "1.1.0",
      "appVersionCode": 2,
      "publishedAt": "2026-11-07T18:00:00Z",
      "releaseNotes": "Adds server configuration and update checks.",
      "applicationId": "com.bgradin.customframe",
      "minSdk": 25,
      "supportedDevices": [
        {
          "manufacturer": "EXAMPLE_MANUFACTURER",
          "model": "EXAMPLE_MODEL",
          "minSdk": 25
        }
      ],
      "download": {
        "url": "https://frames.example.com/downloads/custom-frame/2/custom-frame.apk",
        "sizeBytes": 12345678,
        "sha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
      }
    }
  ],
  "nextCursor": null
}
```

Publish all illustrated fields with these meanings:

| Field | Requirement |
| --- | --- |
| `appVersion` | Nonblank string matching the APK's version name |
| `appVersionCode` | Unique positive integer matching the APK's version code; increase for each new release; maximum 2,147,483,647 |
| `publishedAt` | Publication timestamp in UTC RFC 3339 format |
| `releaseNotes` | String describing the release |
| `applicationId` | APK package name; `com.bgradin.customframe` for the standard app |
| `minSdk` | Positive integer representing the APK's minimum Android API level |
| `supportedDevices` | Array of tested device rules, described below |
| `download.url` | Absolute HTTPS URL serving the APK directly, without embedded credentials |
| `download.sizeBytes` | Positive integer containing the exact APK byte count |
| `download.sha256` | SHA-256 of those bytes, as 64 lowercase hexadecimal characters |
| `nextCursor` | Opaque continuation string, or `null` when there are no further pages |

Only include published releases, ordered by descending `appVersionCode`. An empty catalog is `{"releases": [], "nextCursor": null}`. The current updater does not use `publishedAt`, `releaseNotes`, or `nextCursor`, but hosts should include them for a consistent catalog format.

### Pagination and current client limits

The catalog contract supports `?limit=20&cursor=...`: `limit` defaults to 20 and accepts 1–100; omit `cursor` for the first page. Invalid limits or cursors return `400`.

**The current app requests the bare releases URL once and does not follow `nextCursor`.** Its first response must contain the recommended release with the exact name/code from configuration, plus any older compatible releases you want it to consider. If the recommended release is absent, the update check fails even if another suitable release is present. For a small catalog, a single page with `nextCursor: null` is sufficient.

Both configuration and release JSON bodies are limited to 1 MiB each by the app. Keep the first catalog page within that limit. The client uses 15-second connection, 30-second read, and three-minute total call timeouts, including for APK downloads.

### Device compatibility

Each `supportedDevices` rule requires nonblank `manufacturer` and `model` strings and a positive integer `minSdk`. The app matches manufacturer and model exactly, including case, against Android's `Build.MANUFACTURER` and `Build.MODEL`. A device must meet both the release's top-level `minSdk` and the rule's `minSdk`. Any one matching rule approves the device.

There are no wildcards or implicit approvals for unknown hardware. An empty, missing, malformed, or unmatched policy makes the release ineligible. Record values from tested hardware; do not publish the example placeholders. These rules describe APK compatibility and are separate from the app's vendor power-control profiles.

Keep rule semantics stable. Do not add fields intended to impose extra restrictions within this schema: the current client ignores unknown fields and would not enforce them.

## APK publication and update behavior

Publish one complete APK per release. Split APKs, build channels, and staged rollouts are not defined by this interface.

1. Build and sign the APK using a signing identity compatible with the installed app.
2. Calculate its exact byte size and SHA-256, and record its package name, version name/code, minimum SDK, and tested device rules.
3. Upload it to a stable public HTTPS URL. Return `200 OK` and `Content-Type: application/vnd.android.package-archive` (an optional charset parameter is accepted). Verify the downloaded bytes before publishing metadata.
4. Publish its immutable release record.
5. Update configuration's recommended version name and code together. Generate both endpoints from one catalog where possible to avoid mismatches.

Keep published artifacts and metadata immutable. Avoid expiring download URLs, and retain older artifacts for frames that need them. Immutable APKs can use long-lived public caching. If you reuse [releases.example.json](releases.example.json), treat it as a debug-build snapshot: its hash and size describe only the original APK bytes, its timestamp is not proof of publication, and its placeholder URL must be replaced. Recalculate metadata for every new build.

When the user checks for updates, the app:

1. Fetches configuration with the auth key. If the recommended code is no higher than the installed code, it reports that the app is up to date.
2. Fetches the first catalog page and confirms the recommended name/code exists.
3. Selects the highest compatible code above the installed version and at or below the recommendation. This can be an older release than the recommendation if that release better matches the device.
4. Requires enough free storage for the declared APK size plus more than 32 MiB of reserve, then downloads into app-private temporary storage.
5. Verifies the exact byte count and SHA-256, then checks the APK package, version name/code, minimum SDK, and signing certificates against the installed app and selected record.
6. Opens Android's installer for user confirmation. Newer Android versions may first require permission to install apps from this source.

Use the same signing key across updates. The current app compares the installed and downloaded certificate sets exactly; do not assume signing-key rotation will be accepted. A release APK signed with a different key from an installed debug APK will fail verification. Hosts distributing their own builds must provide a compatible installation and signing path for their frames.

The host cannot trigger unattended installation. Failed update checks or verification leave the installed version, settings, and photos intact. The app does not automatically downgrade, uninstall, or rerun provisioning. ADB remains available as an alternative update path.

## Authentication, caching, and errors

Require the `AuthKey` header on every configuration request and validate it against your approved-key list. Keys are long lived and valid until manually revoked. Missing, invalid, and revoked keys all return `401` with a generic error. Auth keys are distinct from Dropbox tokens; the app sends the key only to the configuration endpoint, never to release or APK URLs.

Your implementation owns key generation, storage, approval, revocation, and any reload/restart mechanism. No key-management API is required by the app. Keep keys and provider credentials out of source control, URLs, logs, and diagnostics. Exclude full configuration responses from request/response logging.

Return `Cache-Control: no-store` on configuration responses, including authentication errors, and exclude the route from shared/CDN caches. The app's private saved snapshot is separate from HTTP caching. Release history can use a short public cache lifetime, such as `max-age=300`; account for that delay when advancing the recommended version. The current app does not implement conditional catalog requests, so its requests must receive `200` with a body rather than an unsolicited `304`.

Return JSON errors using this stable shape:

```json
{
  "error": {
    "code": "UNAUTHORIZED",
    "message": "Configuration access requires a valid auth key."
  }
}
```

| Status | Use |
| --- | --- |
| `400` | Invalid query parameters; for example, `INVALID_CURSOR` or `INVALID_LIMIT` |
| `401` | Missing, invalid, or revoked configuration auth key (`UNAUTHORIZED`) |
| `404` | Unknown route/API version or missing APK |
| `429` | Request throttled |
| `503` | Configuration or catalog temporarily unavailable |

Use `Content-Type: application/json` for API errors. Include `Retry-After` on `429`/`503` where useful for other clients. The current app reports generic failures rather than displaying error-body messages. Do not expose secrets or stack traces. Missing configuration is an error, not a successful empty object. Unknown API versions must not silently fall back to a different contract.

## Evolving your host

The `/v1` path versions the HTTP API; the `v1` JSON property independently identifies a configuration group. The current app reads only the `v1` group and ignores unknown properties, groups, and source identifiers. You can add optional fields or additional configuration groups while continuing to supply valid `v1` values for existing frames.

Preserve existing required fields, types, and meanings. Incompatible configuration changes need a new group; incompatible HTTP or device-matching changes need a new API version. There is no configuration revision counter: clients refresh the supported values directly. Keep older groups current, including recommended versions and rotated credentials, while older clients still use them.

## Verify your implementation

Before connecting frames, check:

- A valid key receives JSON configuration; absent, invalid, and revoked keys receive `401`, with no configuration cached publicly or credentials logged.
- Both endpoints and APK URLs work directly over HTTPS without redirects or browser authentication.
- The recommended name/code appears on the first catalog page, and both JSON bodies are within 1 MiB.
- APK bytes match the published size/hash, package/version metadata, and installed signing identity.
- Device rules approve tested hardware and reject unknown models; a compatible fallback release is on the first page when needed.
- Token rotation, temporary outages, and removed sources behave as described while cached photos remain available.
- A real frame can download and confirm installation of an update without losing settings or photos.

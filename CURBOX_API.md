# Curbox API

Curbox exposes an API that lets other apps control it, much like Shizuku does: your app binds to a
service, the user approves your app once, and after that you can run commands and read state. Access
is per app and revocable, so the user stays in control.

This document is the integration guide. A complete working client lives in the `:apitester` module
of this repo; read its `MainActivity.kt` alongside this doc.

---

## At a glance

- **Transport:** an exported AIDL bound service (`ICurboxApi`).
- **Identity:** the calling app's verified UID, resolved to its package. No tokens.
- **Permission:** the user approves your app from a dialog Curbox shows. They can remove access any
  time under **Reducers → Curbox API → Connected apps**, or turn the whole API off with one switch.
- **API version:** `5` (call `apiVersion()` to check). Existing clients need renewed approval for this capability version.

### Bounded managed targets (API 4)

`execute("REPLACE_MANAGED_POLICIES", Bundle)` takes a `policies` JSON array, for example:

```json
[{"id":"video","kind":"keyword_group","name":"Video","members":["example.test"],"expiresAtMs":1790000000000}]
```

Use a future epoch-millisecond deadline, at most 26 hours from application time. Up to 32 unique targets and 100 distinct nonempty members per target are accepted. Supported kinds are `app_group` (package names) and `keyword_group` (matching keywords). An empty array releases this caller's targets only. Caller identity comes from Binder; a supplied owner field cannot impersonate another client.

These targets are an independent effective blocking layer, not edits to personal groups. Their all-day zero-usage configuration and active state cannot be weakened by editing/deleting personal groups, global keyword toggles or ordinary group cooldowns. Local expiry stops applying the layer; it does not change a user's original groups. `list("MANAGED_POLICIES")` returns retained records for receipt verification; clients must check owner, contents and deadline, and ignore expired records. Group lists include members and derived managed groups while active.

This is bounded cooperative enforcement, not Android device-owner management. Revoked accessibility access, OS force-stop/data removal, device clock manipulation and administrative control are not made tamper-proof by this API. Ordinary client permission revocation does not mutate a currently retained target; the target still expires locally. No media or administrator permissions are granted by this command.

Managed app and trusted blocked-website targets also pause only the matching active media session when
the owner has enabled Curbox's **notification access** in Android system settings. Curbox uses the
official active-session API and sends only a targeted pause command; it does not read or store
notification content, use global media keys, or claim audio focus. The `STATUS` object reports
`mediaControlGranted` as a boolean readiness flag and `managedMediaControl` as `available` or `permission_required`. If permission is missing or revoked,
managed blocking remains active and media control is a documented no-op. Curbox never resumes media
when a target expires or becomes allowed. `browserMediaUriPresent` is a nullable, payload-free
diagnostic: it is `null` when notification access is missing, no active supported-browser session
is available, or session enumeration fails; `false` when supported-browser sessions expose no
valid media-URI host; and `true` when at least one does. It does not identify a site or session,
and does not prove that a blocked session was paused.

---

## 1. Add the contract to your project

Copy [`ICurboxApi.aidl`](app/src/main/aidl/neth/iecal/curbox/api/ICurboxApi.aidl) into your project,
keeping the exact path and package:

```
src/main/aidl/neth/iecal/curbox/api/ICurboxApi.aidl
```

```aidl
package neth.iecal.curbox.api;

interface ICurboxApi {
    int apiVersion();
    boolean isGranted();
    String execute(String command, in Bundle args);
    String query(String state);
    String list(String kind);
    String queryData(String dataset, in Bundle args);
}
```

Enable AIDL in your module's `build.gradle.kts`:

```kotlin
android {
    buildFeatures { aidl = true }
}
```

## 2. Declare package visibility

On Android 11 (API 30) and above you must declare that you talk to Curbox, or you will not be able
to see or bind the service. Add this to your `AndroidManifest.xml`:

```xml
<queries>
    <intent>
        <action android:name="neth.iecal.curbox.api.BIND" />
    </intent>
    <intent>
        <action android:name="neth.iecal.curbox.api.REQUEST_PERMISSION" />
    </intent>
</queries>
```

---

## Intents and actions

| Purpose | Action |
| --- | --- |
| Bind the service | `neth.iecal.curbox.api.BIND` |
| Ask the user for permission | `neth.iecal.curbox.api.REQUEST_PERMISSION` |

> **Find Curbox by action, not by a hardcoded package.** Debug builds of Curbox use the application
> id `neth.iecal.curbox.debug`, release builds use `neth.iecal.curbox`. Resolve the package at
> runtime with `PackageManager.resolveService(Intent(BIND_ACTION), 0)` and reuse that package name
> for both binding and the permission request.

---

## 3. Bind the service

```kotlin
private var api: ICurboxApi? = null

private val connection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        api = ICurboxApi.Stub.asInterface(service)
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        api = null
    }
}

fun connect() {
    val resolved = packageManager.resolveService(Intent("neth.iecal.curbox.api.BIND"), 0)
        ?: return // Curbox not installed
    val curboxPackage = resolved.serviceInfo.packageName
    val intent = Intent("neth.iecal.curbox.api.BIND").setPackage(curboxPackage)
    bindService(intent, connection, Context.BIND_AUTO_CREATE)
}
```

## 4. Request permission

A binder cannot show UI, so the permission request goes through an activity instead. Launch it
**for result** so Curbox can read your package reliably:

```kotlin
private val permissionLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val allowed = result.resultCode == Activity.RESULT_OK
    }

fun requestPermission(curboxPackage: String) {
    val intent = Intent("neth.iecal.curbox.api.REQUEST_PERMISSION").setPackage(curboxPackage)
    permissionLauncher.launch(intent)
}
```

The user sees a dialog naming your app and finishes with Allow or Deny. You can check the current
state any time with `api.isGranted` (false when the user has not allowed you, has removed you, or
has turned the whole API off).

> Binder calls run on a pool thread and may block briefly while Curbox writes its settings. Call
> `execute`, `query`, `list` and `queryData` off your main thread.

---

## 5. Run actions: `execute(command, args)`

Returns one of `OK`, `DENIED`, `UNKNOWN_COMMAND`, or `FAILED`.

| Command | Args | Effect |
| --- | --- | --- |
| `START_FOCUS` | `target` (focus group id), `minutes` (int, default 25) | Starts a focus session for that group |
| `STOP_FOCUS` | none | Ends all running focus sessions |
| `SET_APP_BLOCKER_GROUP` | `target` (group id), `enable` (bool) | Turns an app blocker group on or off |
| `SET_KEYWORD_BLOCKER` | `enable` (bool) | Turns the keyword blocker on or off |
| `SET_KEYWORD_GROUP` | `target` (group id), `enable` (bool) | Turns a keyword group on or off |
| `SET_REEL_BLOCKER` | `enable` (bool) | Turns the reel blocker on or off |
| `SET_UI_HIDER` | `enable` (bool) | Turns the UI hider on or off |
| `SET_GRAYSCALE_GROUP` | `target` (group id), `enable` (bool) | Turns a grayscale group on or off |
| `SET_REEL_COUNTER` | `enable` (bool) | Turns the reel counter on or off |
| `SET_DND` | `enable` (bool) | Turns Do Not Disturb on or off |

Argument keys: `target`, `enable`, `minutes`.

```kotlin
val args = Bundle().apply {
    putString("target", focusGroupId)
    putInt("minutes", 5)
}
val status = api?.execute("START_FOCUS", args) // "OK"
```

## 6. Read state: `query(state)`

Returns a JSON object, or `null` when not allowed or the state is unknown.

| State | JSON fields |
| --- | --- |
| `FOCUS_ACTIVE` | `active` ("true"/"false"), `focus_group`, `focus_remaining` (minutes) |
| `SCREENTIME_TODAY` | `screentime` (minutes today) |
| `REELS_TODAY` | `reels` (count today) |

```kotlin
api?.query("SCREENTIME_TODAY") // {"screentime":"73"}
```

## 7. Discover things: `list(kind)`

Returns a JSON array (an object for `STATUS`), or `null` when not allowed or the kind is unknown.
Use the group lists to find the `id` you pass back as `target` in `execute`.

| Kind | Each item |
| --- | --- |
| `FOCUS_GROUPS` | `id`, `name`, `apps`, `websites`, `blockMode`, `exitable`, `autoTurnOnDnd` |
| `APP_BLOCKER_GROUPS` | `id`, `name`, `isActive`, `apps` |
| `KEYWORD_GROUPS` | `id`, `name`, `isActive`, `keywords` |
| `GRAYSCALE_GROUPS` | `id`, `name`, `isActive`, `apps` |
| `AUTO_DND_GROUPS` | `id`, `name`, `autoTurnOnDnd` |
| `UI_HIDER_SCRIPTS` | `id`, `label`, `packageName`, `isEnabled` |
| `STATUS` | object: `focusActive`, `focusGroup`, `focusRemainingMinutes`, `keywordBlocker`, `reelBlocker`, `reelCounter`, `uiHider`, `mediaControlGranted`, `managedMediaControl`, `browserMediaUriPresent` |

```kotlin
api?.list("FOCUS_GROUPS")
// [{"id":"a1b2...","name":"Study","apps":12,"websites":3,"blockMode":"BLOCK_SELECTED","exitable":true,"autoTurnOnDnd":false}]
```

## 8. Read bounded data: `queryData(dataset, args)`

API version 3 adds bounded access to aggregated usage data. It returns a JSON object, or `null`
when the caller is not allowed, the data set is unknown, or the arguments are invalid.

| Dataset | Args | Result |
| --- | --- | --- |
| `APP_USAGE` | optional inclusive `start_date` and `end_date` in ISO `YYYY-MM-DD` format | Package-level daily and hourly usage rows |
| `WEBSITE_USAGE` | optional inclusive `start_date` and `end_date` in ISO `YYYY-MM-DD` format | Domain-only daily and hourly usage rows |

The default range is today and yesterday. A request may cover at most 31 days. Curbox aggregates
all browser packages and URL paths to their normalized domain before returning data. It never
returns paths, query parameters, page titles, or address-bar search text through this API.

Because API v3 adds these sensitive read capabilities, grants created by an older API version do
not carry over silently. The user must approve the client again after upgrading Curbox.

`APP_USAGE` rows contain `date`, `packageName`, `durationMs`, 24 `hourlyMs` buckets,
`unbucketedDurationMs`, `launchCount`, and `lastUsedMs`. `WEBSITE_USAGE` rows contain the same
time fields plus `domain` and `lastVisitedMs`. Both data sets require the user's explicit Curbox
API grant.

```kotlin
val usage = api?.queryData("WEBSITE_USAGE", Bundle().apply {
    putString("start_date", "2026-09-01")
    putString("end_date", "2026-09-05")
})
// {"schemaVersion":1,"privacyMode":"domain_only","startDate":"2026-09-01",
//  "endDate":"2026-09-05","timeZone":"Europe/Berlin","rows":[{"date":"2026-09-05","domain":"youtube.com",
//  "durationMs":180000,"hourlyMs":[0,...],"unbucketedDurationMs":0,
//  "lastVisitedMs":1788600000000}]}
```

---

## End to end example

```kotlin
// 1. resolve + bind (see connect() above)
// 2. if (api?.isGranted != true) requestPermission(curboxPackage)
// 3. once granted:
val groups = api?.list("FOCUS_GROUPS")            // discover a group id
val firstId = JSONArray(groups).getJSONObject(0).getString("id")

api?.execute("START_FOCUS", Bundle().apply {      // act on it
    putString("target", firstId)
    putInt("minutes", 25)
})

api?.query("FOCUS_ACTIVE")                         // confirm: {"active":"true",...}
```

---

## Notes

- **Effects vs writes.** Commands always write Curbox's settings, but a visible effect (focus mode
  actually blocking, DND flipping) only happens while Curbox's accessibility services are enabled.
- **Permission is per package.** If your app uses a shared UID, any package in that UID that the
  user allowed grants the whole UID.
- **Be ready for `DENIED`/`null`.** The user can remove your app or switch the API off at any moment.
- **Versioning.** Check `apiVersion()` if you depend on newer features. `list()` arrived in version
  2 and `queryData()` arrived in version 3.

## Try it with the bundled tester

The `:apitester` module is a runnable app that drives every call in this doc, including a
"Dump everything" button that lists all groups and status at once.

```bash
./gradlew :app:installAndGrantAccessibilityFdroidDebug :apitester:installDebug
adb shell am start -n neth.iecal.curbox.apitester/.MainActivity
```


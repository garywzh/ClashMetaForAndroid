# Tailscale integration

CMFA owns the Android VPN. An embedded tsnet node runs in userspace in the core
process; the standalone Tailscale app does not need to run alongside it.

## Use

1. Import/select an ordinary Clash subscription. No Tailscale YAML is needed.
2. Open **Settings > Tailscale** and enable it.
3. Start Clash with system traffic routing enabled and choose **Rule** mode.
   Android may ask to replace the currently active VPN; confirm this yourself.
4. Return to **Settings > Tailscale > Log In With Browser** and authorize the node.
   The page shows its connection state and assigned addresses.
5. Optionally open **Port Forwarding** and add a named TCP rule with a **Tailnet
   port** and **Local port**. For example, Tailnet port `2222` and local port
   `8022` expose a local SSH server as `<tailnet-ip>:2222`. The target is always
   `127.0.0.1`; the local service must listen on IPv4 loopback or all interfaces.

The Tailscale overview groups the enable switch, connection state and copyable
addresses under **Connection**, and links to **File Transfer**, **Subnet Routes**
and **Port Forwarding** under **Features**. Login appears only when needed;
logout and usage notes are in the overflow menu. Subpages show connection notices
only when unavailable. There is no separate Tailscale VPN lifecycle.

## Port forwarding

- Add, edit, enable, disable or delete rules in **Settings > Tailscale > Port
  Forwarding**. Rules can be prepared while the VPN is stopped; they take effect
  when Tailscale connects. Up to 32 rules are allowed, with unique Tailnet ports
  from 1 to 65535. Different rules may point to the same local port.
- Tap a rule to edit it. Its switch controls only that rule; the copy button
  copies `<tailnet-ip>:<tailnet-port>` (with brackets for IPv6), without guessing
  a protocol or path. The overview's address copy button copies the device IP.
- This is transparent TCP forwarding, not an MCP server, HTTP proxy or UDP
  forwarder. For MCP, an ordinary `8080 → 8080` rule allows clients to use
  `http://<tailnet-ip>:8080/mcp`; other TCP protocols work without special handling.
- Changes apply immediately. Unchanged rules keep their existing connections;
  changing either port, disabling or deleting a rule closes its connections.
  Per-rule bind errors do not stop other rules; save the affected rule to retry.
- No listener is opened on Wi-Fi, mobile or public interfaces. Tailnet ACLs/grants
  still apply, and each local service must retain its own authentication.
  Stopping the VPN, disabling Tailscale or logging out closes all forwards.
- An existing enabled MCP setting is read as a single ordinary `8080 → 8080`
  rule. New saves contain only generic forwarding rules, not an MCP setting.

## Routing

The node is named `android-clash`. It has its own Tailscale identity and IP, not
the standalone Tailscale app's IP. Tailnet ACLs/grants still apply. Do not enable
the public forwarding/Funnel features of another app for this integration.

Routing covers `100.64.0.0/10`, `fd7a:115c:a1e0::/48`, `*.ts.net` MagicDNS and,
when explicitly enabled, approved subnet routes. IPv6 traffic additionally
requires CMFA's network IPv6 option. Short hostnames, publishing routes from the
phone, exit nodes, and automatic routing in Global/Direct modes are not part of
this implementation. Existing per-app VPN access controls still apply.

## Subnet routes

1. Configure another Tailnet device as a subnet router (for example, advertise
   `192.168.31.0/24` from a device on your home network).
2. Approve its routes in the Tailscale admin console, or via your Tailnet's
   auto-approvers. Ensure ACLs/grants allow this phone to access the destination.
3. Open **Settings > Tailscale > Subnet Routes** and enable **Accept Subnet Routes**. This is off
   by default, including for installations upgrading from the initial integration.
4. Keep Clash in **Rule** mode. **Accepted Subnets** shows the control-plane
   routes used by CMFA. You can now access e.g. `192.168.31.10` without installing
   Tailscale on that destination.

- Only control-plane `AllowedIPs` are eligible, never unapproved subnet
  advertisements from a peer's Hostinfo. Node host routes and default/exit routes
  are excluded; expired routers are ignored. Tailscale still enforces ACLs and
  chooses the underlying router, including HA failover.
- Routes are watched in the background, even when the settings page is closed.
  A live rule precedes subscription rules without rewriting subscription files.
  Domain destinations resolving to an accepted subnet use the same rule and the
  existing DNS configuration; this does not add split DNS or short-name support.
- **Overlapping local networks use the Tailnet route**, even when the phone is
  on a Wi-Fi network with the same subnet. Turn off Accept Subnet Routes to
  restore ordinary subscription/local routing. This option does not enable an
  exit node or send unrelated private networks to Tailscale.
- With **Bypass Private Networks** enabled, CMFA also adds the accepted prefixes
  to Android's VPN routes. Changes are picked up from the native snapshot within
  about a second, without polling the control plane. Android requires replacing
  the VPN interface to change those routes; tsnet's identity/server is retained,
  but existing app connections may need to reconnect. With private bypass off,
  the VPN already captures these destinations and needs no interface replacement.
- Disabling the option, logging out, stopping the VPN, or a watcher failure clears
  the live subnet selection. UDP sockets revalidate the destination on every
  datagram, including after route withdrawal. Outside a selected subnet, original
  Clash rules/Android routing apply; this is not a subnet kill switch.
- Accepted means selected for routing, not proven reachable: the router must have
  working LAN forwarding and return traffic. A successful node login is not a
  subnet connectivity test.

## Taildrop

Open **Settings > Tailscale > File Transfer** while the VPN is running and the node is
logged in. The backend uses Tailscale's Taildrop PeerAPI, not an unrelated file
server. The pinned MetaCubeX fork omits the implementation; CMFA ports the
matching upstream v1.102.3 module without replacing its network adaptations.

- **Send** in the toolbar opens Android's document picker, then asks for an online
  receiving device. Other apps can also share one or multiple content-URI files
  to **File Transfer** using Android's share sheet. Bytes stream through owned file
  descriptors, not JSON or a whole-file memory buffer. There are two concurrent
  outgoing/export workers, at most 32 outstanding files and 100 recent results.
  If the queue fills, unsent selections remain on the page for retry.
- Every incoming file requires **Receive** or **Decline** in the Taildrop page
  or its individual request notification. The request shows the authenticated
  sending device, filename and size (when known). Declining or dismissing the
  notification rejects only that file. Requests expire after two minutes and
  are cancelled when the VPN/node stops or the login changes. Approval is never
  reused for another request, including a retry of the same filename. Up to 32
  requests may await approval; pending requests do not consume the four active
  receive slots.
  This keeps the official PeerAPI protocol: the receiver waits before reading
  the PUT body or creating a file. Senders may already have placed data in
  bounded network buffers, so approval does not guarantee zero prior network
  traffic. Official senders can show generic failure or time out earlier; no
  sender-side consent handshake or batch identifier is required.
- On Android 10+, completed received files automatically appear in
  **Download/Taildrop** through MediaStore, without broad storage permission.
  A hidden pending download is published only after the copy closes and its size
  is verified. The private staging original is then removed. Atomic journaling
  reconciles interrupted copies/publication without creating duplicate downloads;
  stable source identities prevent cleanup from deleting a later same-name file.
  Name conflicts never overwrite existing downloads. Saving failures retain the
  original and expose **Retry saving to Downloads**. Android 9 and earlier use
  the manual SAF **Save as** fallback instead.
- Taildrop uses one file list without separate transfer/inbox sections or duplicate
  completed Downloads jobs, ordered by awaiting approval, active and finished.
  Refresh and clearing finished transfer records are in the overflow menu;
  clearing records does not delete saved files. The save directory appears once
  for the page, not on every row. Tap a saved file for **Open**, **Save as**, **Share**
  and **Delete**, including with the VPN stopped. Unsaved/failed files open their
  appropriate actions on tap. Delete removes the actual download;
  Save as is optional, not a required second step. Saved files use durable
  MediaStore URIs with read grants. Opening APKs uses the system installer;
  Android asks the user to allow this installation source before installing.
  Unexported staging files instead use private
  read-only streaming grants, which expire after an hour or a VPN/login session
  change. Deleting a staging file invalidates its share tokens.
- The page and active notification show transferred bytes / total bytes, sampled
  transfer speed and progress bars for receiving, sending and saving. Unknown
  totals use indeterminate progress; speed stays unknown until enough samples
  exist, rather than counting resumed bytes as new traffic. Tap an active
  row to choose **Cancel transfer** from its menu. The existing VPN service posts transfer and
  inbox notifications when Android permits them; a single saved-file notification
  opens that file's actions, otherwise it opens Taildrop. Active transfer
  notifications are ongoing and do not auto-dismiss on tap. Android 14+ still
  permits individual swipes, but excludes ongoing notifications from Clear all.
  Completed notifications are dismissible. Dismissed inbox notifications return only for new or changed files,
  not peer changes, transfer progress or a temporary backend reconnect. Restarting
  the VPN starts a new notification session and can remind about existing files.
  A time-limited, renewed wake lock is held only during transfers or pending
  receive requests.
- Closing the page does not stop transfers. Stopping the VPN, logging out or
  disabling Tailscale cancels outgoing/export operations. There is no separate
  always-on service. Resumable partial files and files still awaiting successful
  saving stay in private, per-login staging outside Android backup.
  A failed send can be retried by selecting the source again; resumption uses
  the original Taildrop checksum protocol when the receiver supports it. Jobs
  themselves do not automatically restart after the VPN or app process stops;
  interrupted automatic Downloads copies retry from staging on the next VPN run.
- Receive authorization remains upstream's signed-peer, same-user/untagged or
  explicit file-send capability check, plus the administrator's file-sharing
  capability. Filenames are validated; conflicts are renamed/deduplicated by
  upstream rules. Receiving is limited to four simultaneous uploads and stops
  before consuming the last 64 MiB of storage. Failed partial uploads expire
  using upstream's one-hour cleanup delay. Saved downloads are retained until
  deleted. Automatic publication temporarily needs space for both the staging
  original and the pending download; failed saves keep the original for retry.

## Lifecycle and storage

- Settings: `files/clash/tailscale-settings.json`, mode 0600, atomic replacement.
- Node keys: private `files/clash/tailscale-native/`, outside subscription folders
  and outside the existing Android backup allowlist.
- Subscription updates operate on an in-memory copy. Original files are untouched.
- Profile validation uses a direct placeholder without starting tsnet. Live
  configuration application swaps the adapter in place, retaining proxy-group
  references. The adapter rejects destinations outside Tailnet and the currently
  accepted subnets.
- Profile reloads reuse the server. VPN stop/reset or disabling the integration
  closes the server and forwarded connections but preserves its identity on disk.
- Port forwarding defaults to no rules, binds only on the tsnet network, and
  passes TCP bytes unchanged. It neither stores nor bypasses service credentials.

## Validation

Run the host-side tests from `core/src/main/golang`:

```sh
go test -race -tags 'foss,with_gvisor,cmfa' ./native/tailnet ./native/taildrop/...
```

The tests cover subscription/DNS preservation, validation without startup,
reserved names, atomic private settings and backward compatibility, profile/server
lifetime, approved-subnet filtering, dynamic rule changes and domain resolution,
IPv4/IPv6 destination validation, Android peer-change notifications, concurrent
readers, UDP route withdrawal, transparent forwarding and cancellation, forwarding
rule validation, multiple listeners, port changes, bind failure isolation,
rule deletion, VPN-stop cleanup and preservation of previously shared services.
Taildrop tests additionally exercise the registered LocalAPI, in-process PeerAPI
permissions and file round-trips, filename safety, restart/resumption, conflicts,
receive progress, streaming jobs, cancellation, queue bounds and descriptor cleanup.
They also cover stable received-file identities and identity-checked export/cleanup.
Receive-approval tests cover no body reads or file creation before confirmation,
individual accept/decline, duplicate and stale decisions, bounded requests,
cancellation/timeout/shutdown and rejection of an unstarted HTTP upload.
Android instrumentation tests exercise real MediaStore publication, same-name
deliveries, failure/retry, empty files, size verification, stop/crash recovery,
orphan pending rows and deletion using an injected native writer. JVM tests cover
speed sampling, resumed counters and stalled transfers. UI instrumentation checks
determinate/indeterminate progress and saved-file visibility without an active VPN.
They also check per-file approval buttons and independent notification actions,
notification removal after decisions and VPN-stop cleanup.

Build with JDK 21, the SDK/NDK versions declared in Gradle, and the patched
MetaCubeX Go toolchain used by `.github/workflows`. A local development build can
use a separate package without changing source defaults; put e.g.
`custom.application.id=com.github.metacubex.clash.tailnet` in `local.properties`.
It then installs alongside the official app and needs its own profile import.

Device acceptance still requires a real Tailnet login, peer TCP/UDP access,
MagicDNS, stop/start and profile reload, plus multiple forwarded services and
service requests with both valid and invalid credentials. A successful build or
loopback relay test does not prove
real Tailnet connectivity.

Subnet acceptance additionally requires a real approved router: test TCP and UDP
to a LAN destination, an unrelated private subnet, approval/revocation while the
settings page is closed, option off/on, stop/start and profile reload, both private
bypass settings, IPv6 when enabled, overlapping Wi-Fi subnets, and ACL denial. Check
that ordinary internet routing and local port forwarding remain unaffected.

Taildrop device acceptance additionally requires bidirectional transfers with
official Tailscale clients: small/large/empty files, non-ASCII and special-character
filenames, multiple Android shares, SAF saves, receiving/share/delete actions,
screen-off and background transfers, cancellation/retry, insufficient storage,
ACL/capability denial and VPN stop/start. The in-process tests use simulated peers;
they do not prove interoperability over a real Tailnet or all Android document
provider/share-target behaviors.

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
5. Optionally enable **Share Local MCP on Tailnet**. Run the MCP app on port 8080,
   bound to localhost (or all interfaces), with Bearer authentication enabled.
   Tap the Tailnet address to copy `http://<new-tailnet-ip>:8080/mcp`.

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
3. Enable **Settings > Tailscale > Accept Subnet Routes** in CMFA. This is off
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
- MCP forwarding is off by default, binds only on the tsnet network, and passes
  HTTP through unchanged. It neither stores nor bypasses the MCP Bearer token.

## Validation

Run the host-side tests from `core/src/main/golang`:

```sh
go test -race -tags 'foss,with_gvisor,cmfa' ./native/tailnet
```

The tests cover subscription/DNS preservation, validation without startup,
reserved names, atomic private settings and backward compatibility, profile/server
lifetime, approved-subnet filtering, dynamic rule changes and domain resolution,
IPv4/IPv6 destination validation, Android peer-change notifications, concurrent
readers, UDP route withdrawal, transparent forwarding and cancellation.

Build with JDK 21, the SDK/NDK versions declared in Gradle, and the patched
MetaCubeX Go toolchain used by `.github/workflows`. A local development build can
use a separate package without changing source defaults; put e.g.
`custom.application.id=com.github.metacubex.clash.tailnet` in `local.properties`.
It then installs alongside the official app and needs its own profile import.

Device acceptance still requires a real Tailnet login, peer TCP/UDP access,
MagicDNS, stop/start and profile reload, plus MCP requests with both valid and
invalid Bearer tokens. A successful build or loopback relay test does not prove
real Tailnet connectivity.

Subnet acceptance additionally requires a real approved router: test TCP and UDP
to a LAN destination, an unrelated private subnet, approval/revocation while the
settings page is closed, option off/on, stop/start and profile reload, both private
bypass settings, IPv6 when enabled, overlapping Wi-Fi subnets, and ACL denial. Check
that ordinary internet routing and local MCP sharing remain unaffected.

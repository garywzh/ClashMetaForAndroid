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

Routing covers `100.64.0.0/10`, `fd7a:115c:a1e0::/48` and `*.ts.net` MagicDNS.
IPv6 traffic additionally requires CMFA's network IPv6 option. Short hostnames,
subnet routers, exit nodes, and automatic routing in Global/Direct modes are not
part of this implementation. Existing per-app VPN access controls still apply.

## Lifecycle and storage

- Settings: `files/clash/tailscale-settings.json`, mode 0600, atomic replacement.
- Node keys: private `files/clash/tailscale-native/`, outside subscription folders
  and outside the existing Android backup allowlist.
- Subscription updates operate on an in-memory copy. Original files are untouched.
- Profile validation uses a direct placeholder without starting tsnet. Live
  configuration application swaps the adapter in place, retaining proxy-group
  references. The adapter rejects non-Tailnet destinations.
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
reserved names, atomic private settings, profile/server lifetime, non-Tailnet
destination rejection, transparent forwarding and cancellation.

Build with JDK 21, the SDK/NDK versions declared in Gradle, and the patched
MetaCubeX Go toolchain used by `.github/workflows`. A local development build can
use a separate package without changing source defaults; put e.g.
`custom.application.id=com.github.metacubex.clash.tailnet` in `local.properties`.
It then installs alongside the official app and needs its own profile import.

Device acceptance still requires a real Tailnet login, peer TCP/UDP access,
MagicDNS, stop/start and profile reload, plus MCP requests with both valid and
invalid Bearer tokens. A successful build or loopback relay test does not prove
real Tailnet connectivity.

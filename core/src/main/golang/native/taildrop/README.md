# Taildrop backend

Ported from Tailscale **v1.102.3**, matching the base version reported by the
pinned MetaCubeX fork. Source: https://github.com/tailscale/tailscale/tree/v1.102.3/feature/taildrop

The MetaCubeX module removes the backend but retains its LocalClient APIs and
extension interfaces. Keeping this module local avoids replacing that fork's
Android dialer/netstack adaptations. The BSD-3-Clause license is in `LICENSE`
and is also shipped in the APK at `assets/licenses/tailscale-taildrop.txt`.

Local adaptations:

- Rewrite imports for MetaCubeX's HTTP and Go compatibility packages. Copy the
  missing upstream `httphdr`, `multierr`, and `progresstracking` helpers under
  `internal/`, under the same license. Adapt newer syntax to the host module's
  Go language version. Omit Windows shell hooks; CMFA is an Android app.
- Require explicit staging-root opt-in so unrelated subscription-defined tsnet
  nodes do not gain file reception. Use the filesystem implementation on Android
  for private, per-login staging.
  Android MediaStore automatic Downloads publication and SAF export/share are
  handled by the app, not direct-file mode. The app removes staging only after
  successful automatic publication; optional SAF export does not remove it.
- Report actual incoming progress; claim transfers before opening partial files
  so duplicate requests cannot corrupt an active upload. Truncate a fresh
  transfer correctly, keep received files private, and cancel blocked receives
  using a read deadline before closing their request body.
- Bound concurrent receives to four, preserve 64 MiB of free disk space, and
  bound the backend's finished outgoing-notification history. Upstream partial
  file expiry/resumption and filename conflict handling are retained.
- Normalize/escape outbound filenames consistently for both PUT and multipart
  requests, and report unsuccessful PeerAPI HTTP responses as failed sends.
- Register private LocalAPI endpoints `file-incoming`, `file-waiting` and
  `file-cancel/` for Android progress, stable staging identities and cancellation.
  Identity checks guard Android export and cleanup against later same-name
  deliveries, including across backend restarts. PeerAPI remains the upstream `/v0/put/`
  protocol, with its original identity/capability checks.
- Require per-file Android approval after the upstream authorization checks and
  before reading PUT bodies or creating partial files. Pending offers use random,
  one-shot IDs, a two-minute timeout and a 32-request bound. Private `file-offers`
  and `file-decision/` LocalAPI endpoints list and decide offers; node shutdown
  cancels pending waits. Rejection closes the HTTP/1 connection without draining
  the body. This does not prevent a sender from filling network buffers before
  approval, and does not introduce a nonstandard sender protocol.

Standalone upstream filename, send, resume, target and HTTP-header tests are
retained. Tests depending on the removed `tstest` daemon harness are not copied;
`cmfa_test.go` tests PeerAPI authorization, progress, duplicate requests,
restart/resumption, filename traversal and conflicts in-process. It also tests
LocalClient-to-PeerAPI round-trips, unknown sizes, filename escaping and HTTP
rejection using localhost-only simulated peers, with external networking blocked.

Run from `core/src/main/golang`:

```sh
go test -race -tags 'foss,with_gvisor,cmfa' ./native/taildrop/... ./native/tailnet
```

These tests do not substitute for interoperability checks with real official
Tailscale clients over a live Tailnet.

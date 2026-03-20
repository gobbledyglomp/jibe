"""Device pairing and trust management for the Jibe daemon.

This module will handle the authentication flow described in
`docs/protocol.md`:

  1. Generate and display a one-time PIN on the daemon side
  2. Receive `auth.request` messages containing the PIN + device name
  3. Verify the PIN and either accept or reject the device
  4. Persist trusted devices so they can reconnect without re-pairing

For this milestone, this module is a stub. The auth flow will be
implemented in a future milestone once the basic transport layer
(WebSocket server + message parser) is proven to work.
"""

# Security Policy

## Scope

Jibe is a **LAN-only, self-hosted** tool. It is designed for use on trusted local networks only and intentionally has no internet-facing components.

The security model:
- All Android↔daemon communication is encrypted with **self-signed TLS** (trust-on-first-use, fingerprint pinned after first pairing).
- Device pairing requires a **6-digit PIN** (120-second expiry, 5-attempt rate limit).
- The web dashboard uses **JWT session authentication** (bcrypt password hashing, local-only HTTP binding).
- No data leaves your network. No analytics, no telemetry, no cloud relay.

Out of scope:
- Vulnerabilities that require physical access to the device.
- Attacks that require the attacker to already be on your LAN with full network interception capability — this is a trusted-LAN tool.
- The self-signed TLS certificate itself (by design; fingerprint is verified on first pairing).

## Supported versions

Only the latest release on `main` receives security fixes.

## Reporting a vulnerability

Please **do not open a public GitHub issue** for security vulnerabilities.

Report privately via **[GitHub Security Advisories](https://github.com/gobbledyglomp/jibe/security/advisories/new)** or by emailing the maintainer directly (address in the commit log).

Include:
1. A description of the vulnerability and its impact.
2. Steps to reproduce.
3. Affected component (daemon, Android app, dashboard).
4. Any suggested remediation if you have one.

You can expect an acknowledgement within **72 hours** and a fix or mitigation plan within **14 days** for confirmed issues.

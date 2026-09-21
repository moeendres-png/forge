# WS231 Source Lock

WS231 evaluates the exact published WS227 successor candidate. Three SHA
identities are bound and never conflated:

- **Evidence-seal identity** `8ff3e7a48271eaba847608701f4c284c20dcdf68`
  (TREE `be5e3f3a9207c23ca6b2673a95086b718236d0c4`) — the published WS227
  seal; WS231 branch HEAD equals it (`git rev-parse HEAD` verified).
- **Provider candidate identity** `eb87b31759c2a9989a819f408c52b3da5c00301d`
  (TREE `cb4e5dbd54f55490308379a32de94dd859234707`) — the WS227 validated
  external provider implementation surface.
- **Rules-Core identity** `d52e890538dc0380d1b26d781349312e310b3a2b`
  (TREE `302938b7f66cd1524d784f9bc85fb445beb21179`) — the Core-owned
  RNG explicit-seed binding (`forge-core/.../util/MyRandom.java`).

Read-only Lab authority: WS226 consolidated CPL authority integration
`fb156d2b4cf8c5c21d0c84e844a53160032c0992`
(TREE `52359f47eea7ddc1e4ec8187e89b67fa67d63616`), consumed read-only.

No unpublished state from siblings WS229 / WS230 was consumed.
`ARCHITECTURE_FREEZE = NOT_CLAIMED`, `PRODUCTION_PROVIDER = NOT_SELECTED`.

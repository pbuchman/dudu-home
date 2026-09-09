# Functional packages and installation identity

Local version 0.4.0 uses `com.pbuchman.duduhome` for both namespace and application ID.
This intentionally requires migration from the published `pl.piotrbuchman.dudugate`.
Radio migration was authorized and completed on 2026-09-09. The owner subsequently authorized
publication of the current development source; complete ignition/wake acceptance remains pending.

| Package suffix | Responsibility |
|---|---|
| ui | Existing menu, setup and action presentation |
| gate / gate.syu | Call safety and vendor Binder transport |
| roborock | Native HTTP, credentials and encrypted storage |
| automation | Action mapping, shared exclusion, daily limits and journey sessions |
| location | GPS subscription, local geometry and pure detectors |
| config | Atomic private import |
| startup | Boot and wake entry points |

Public Java types/methods are in-process feature boundaries, not exported Android endpoints.
Storage filenames and state keys remain unchanged to support explicit migration. Credentials
must be re-imported into the new package's own Keystore, never restored as old ciphertext.
Refactoring preserves gate protocol, routines and UI behavior. Version 0.4.0 is not a claim
of new-package hardware acceptance.

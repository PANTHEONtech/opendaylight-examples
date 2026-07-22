# testfilter — pluggable Shiro filter/realm for OpenDaylight AAA (AAA-307)

> ⚠️ **For evaluation only — not for production use.** `TestRealm` accepts any password and
> derives the user's role from the username — real credential validation is the marked TODO in
> `fetchRolesFromTestService(...)`.

Demonstrates plugging an external Shiro filter and realm into
[OpenDaylight AAA](https://github.com/opendaylight/aaa) without modifying AAA code,
using the AAA-307 mechanism: filter/realm classes are declared by fully-qualified class name in the
`shiro-configuration` datastore (seeded from `aaa-shiro`'s `initial/aaa-app-config.xml`) and
instantiated by Shiro reflection under the `org.opendaylight.aaa.shiro` bundle's classloader.

## Behavior

| Request | Token | Realm | Result |
|---|---|---|---|
| `Authorization: Basic ...` | `UsernamePasswordToken` | `TestRealm` | `allow-all` mode: authenticated, granted a single role equal to the username (password not validated); `reject-all` mode: 401 |
| `X-Forwarded-User` + `X-Forwarded-Groups` (OAuth2-Proxy) | `Oauth2ProxyHeaderToken` (from aaa) | `Oauth2ProxyHeaderRealm` (from aaa) | authenticated with the forwarded groups as roles, regardless of mode |
| neither | — | — | 401 + `WWW-Authenticate: BASIC` |

A Basic `Authorization` header takes precedence over the forwarded headers.

The forwarded headers are not parsed by custom code: `TestFilter` reads aaa's
`Oauth2ProxyHeaderParser` from `TestFilterParserHolder`, bound once (via OSGi DS `@Reference`
constructor injection — the AAA-307-sanctioned style) by `TestFilterParserBridge` in the `config`
bundle and built from the **common** OAuth2-Proxy configuration
(`etc/org.opendaylight.aaa.shiro.oauth2proxyheaderparser.cfg`) — the same validation limits that
govern ODL's own `Oauth2ProxyHeaderFilter` apply here, from a single file (see
[Configuration](#configuration)). Two consequences:

- A forwarded user or groups value violating the configured limits (`max-user-length`,
  `allowed-chars`, ...) is rejected: an invalid user means no login attempt (401), an invalid groups
  value rejects the **whole** header (the parser validates the entire value against its header
  pattern — e.g. a trailing comma yields an authenticated user with zero roles, hence 403 on
  `roles[...]`-gated URLs). This is stricter than lenient splitting.
- While the parser service is unavailable the filter fails closed: forwarded-header
  authentication is disabled and only Basic authentication works.

`TestRealm.fetchRolesFromTestService(...)` is the marked integration point where a real external
call to the Test service will replace the simulation.

## Modules

### `config/` — `testfilter-config` (ordinary bundle)

The OSGi `.cfg` machinery, modeled on aaa's `Oauth2ProxyHeaderParserConfigImpl`:

- `TestFilterConfig` — configuration interface: `Mode` enum (`ALLOW_ALL`/`REJECT_ALL`).
- `TestFilterConfigHolder` — static volatile snapshot bridging OSGi to the Shiro-instantiated
  classes. Fail-closed default before activation: `reject-all`.
- `TestFilterConfigImpl` — OSGi DS component (`configurationPid tech.pantheon.testfilter`,
  metatype via `@Designate`/`@ObjectClassDefinition`) that parses the cfg and publishes an
  immutable snapshot to the holder on activate, resetting it on deactivate. It must be declared
  `immediate = true`: with only `service = ...` it is a *delayed* component that nothing consumes,
  so SCR would never activate it and the holder would silently stay fail-closed.

The same static-holder bridge pattern also carries aaa's own `Oauth2ProxyHeaderParser` service
across the reflection boundary, since AAA-307's public integration API (Blueprint `<reference>` or
DS `@Reference`) only injects into a container-managed bean — and `TestFilter` is not one:

- `TestFilterParserHolder` — static volatile snapshot of the currently bound
  `Oauth2ProxyHeaderParser`. Unbound default: `null`, which `TestFilter` treats as fail-closed.
- `TestFilterParserBridge` — OSGi DS component that binds `Oauth2ProxyHeaderParser` via
  constructor `@Reference` (the same idiom aaa's own `Oauth2ProxyHeaderParserImpl` uses for
  `Oauth2ProxyHeaderParserConfig`) and publishes it to the holder on activate, resetting it on
  deactivate. A static reference is the only policy constructor injection supports: if aaa's own
  configuration changes and the parser service is re-registered, this component is itself
  deactivated and reactivated to rebind.

### `fragment/` — `testfilter-fragment` (OSGi fragment, `Fragment-Host: org.opendaylight.aaa.shiro`)

**Why a fragment?** An OSGi fragment is a bundle that does not run on its own: at resolve time the
framework attaches it to its host bundle, and its classes are loaded by the *host's* classloader as
if they were part of the host jar. That is exactly what the AAA-307 mechanism needs, twice over:

1. The `shiro-configuration` wiring names `TestFilter`/`TestRealm` as plain strings, and the
   `Class.forName` happens inside the `org.opendaylight.aaa.shiro` bundle — which, under OSGi
   classloader isolation, cannot see classes from an ordinary third-party bundle. As a fragment,
   our classes land directly in the host's class space, so the reflective lookup works with zero
   AAA code changes.
2. `TestRealm` builds an `ODLPrincipalImpl` from `org.opendaylight.aaa.shiro.principal`, a package
   the host does **not** export. A normal bundle could never import it; a fragment sees it because
   it shares the host's classloader.

The module (and its artifact `testfilter-fragment`) is named after this packaging form — the name
signals the unusual bundle type to anyone deploying it. Contents:

- `TestFilter` — extends `BasicHttpAuthenticationFilter` (the `authcBasic` class; the intermediate
  `HttpAuthenticationFilter` is package-private in the repackaged Shiro 1.13). Overrides
  `isLoginAttempt` (Basic header **or** a forwarded user the parser accepts) and `createToken`
  (Basic → stock `UsernamePasswordToken`, no custom token type needed; otherwise → aaa's
  `Oauth2ProxyHeaderToken` produced by `Oauth2ProxyHeaderParser.parseToken(...)`). It contains no
  header-parsing code of its own — validation, `role:` prefix stripping and all limits come from
  aaa's parser, bound once by the config bundle's `TestFilterParserBridge` and read from
  `TestFilterParserHolder` at request time; an absent parser fails closed. A `parserSupplier` seam
  lets unit tests substitute a parser built from a known configuration.
- `TestRealm` — extends `AuthorizingRealm`. Routes via
  `setAuthenticationTokenClass(UsernamePasswordToken.class)` so only Basic-auth tokens reach it,
  while `Oauth2ProxyHeaderToken` goes to aaa's existing `Oauth2ProxyHeaderRealm`. Reads the config
  holder at authentication time (start ordering irrelevant, cfg edits live) and builds an
  `ODLPrincipalImpl` on success. `fetchRolesFromTestService(...)` is the marked TODO where the
  real external call to the Test service will replace the simulation; it currently grants a
  single role equal to the username (so `admin:<anything>` gets role `admin`).

Constraints that shape the fragment:

- SCR does not process DS components in fragments — hence the separate config bundle and the
  static holders (`TestFilterConfigHolder`, `TestFilterParserHolder`) read at request time.
- `Import-Package` must exclude `org.opendaylight.aaa.shiro.*`: those packages come through the
  host classloader, and `org.opendaylight.aaa.shiro.principal` (`ODLPrincipalImpl`) is not
  exported by the host, so a generated import would make the fragment unresolvable.

### `features/` — `odl-aaa-testfilter` (Karaf feature)

Installs both bundles, `etc/tech.pantheon.testfilter.cfg`, and `odl-aaa-shiro`.

Unit tests (JUnit 5 + Mockito, 23 total): token-type selection and precedence, 401 challenge,
shared-config limit enforcement (`max-user-length`, `allowed-chars`, strict groups-header
validation), allow-all/reject-all realm behavior, fail-closed behavior without configuration or
parser service, cfg parsing, and DS bridge activation/deactivation.

## Configuration

`etc/tech.pantheon.testfilter.cfg` (Config Admin, applied live via fileinstall — no restart):

```properties
mode = allow-all        # or reject-all; unrecognized values fail closed to reject-all
```

### Shared OAuth2-Proxy header validation — `etc/org.opendaylight.aaa.shiro.oauth2proxyheaderparser.cfg`

Delivered by `odl-aaa-shiro` (not by this feature) and consumed by **both** ODL's own
`Oauth2ProxyHeaderFilter` and this example's `TestFilter` — one file governs every consumer of the
forwarded headers. Also applied live via Config Admin:

```properties
max-header-length = 4096            # max X-Forwarded-* header value length in bytes
max-role-length = 128               # max length of a single role name
max-user-length = 128               # max username length
max-roles-per-user = 200            # cap on roles parsed per user
allowed-chars = [a-zA-Z0-9_.:\\-@]  # regex character class whitelisting username/role characters
```

## AAA wiring

Requires a local checkout of [OpenDaylight AAA](https://github.com/opendaylight/aaa) — referred to
as `<aaa-checkout>` below. The wired file is
`<aaa-checkout>/aaa-shiro/impl/src/main/resources/initial/aaa-app-config.xml`:

- `main`: `testFilter = tech.pantheon.testfilter.TestFilter`,
  `testRealm = tech.pantheon.testfilter.TestRealm`,
  `oauth2ProxyHeaderRealm = org.opendaylight.aaa.shiro.realm.Oauth2ProxyHeaderRealm`
- `securityManager.realms = $testRealm, $oauth2ProxyHeaderRealm` — `tokenAuthRealm` is intentionally
  removed: it also consumes `UsernamePasswordToken` and would let e.g. admin/admin through even in
  reject-all mode
- `roles = org.opendaylight.aaa.shiro.filters.ODLRolesAuthorizationFilter` — overrides the roles
  filter to return 403 Forbidden (per the
  [AAA user guide](https://docs.opendaylight.org/projects/aaa/en/latest/user-guide.html)): Shiro's
  default `RolesAuthorizationFilter` returns 401 Unauthorized when an authenticated user lacks the
  required role, which invites re-authentication; 403 correctly says the identity is known but the
  permission is not granted. Declared in `<main>`, it applies to every `roles[<role>]` entry in
  `<urls>` — so e.g. `bob:anything` on an admin-gated URL now gets 403, not 401
- `urls`: `/**` (and the three `roles[admin]` entries) routed through `testFilter`

### aaa-app-config example

The complete wiring as it appears in `aaa-app-config.xml` (the `/token = rest` entry is stock and
shown only for placement context):

```xml
<main>
    <pair-key>testFilter</pair-key>
    <pair-value>tech.pantheon.testfilter.TestFilter</pair-value>
</main>
<main>
    <pair-key>testRealm</pair-key>
    <pair-value>tech.pantheon.testfilter.TestRealm</pair-value>
</main>
<main>
    <pair-key>oauth2ProxyHeaderRealm</pair-key>
    <pair-value>org.opendaylight.aaa.shiro.realm.Oauth2ProxyHeaderRealm</pair-value>
</main>

<!-- tokenAuthRealm intentionally absent: it also consumes UsernamePasswordToken and would
     bypass testRealm's reject-all mode -->
<main>
    <pair-key>securityManager.realms</pair-key>
    <pair-value>$testRealm, $oauth2ProxyHeaderRealm</pair-value>
</main>

<!-- Override the roles filter to return 403 Forbidden instead of Shiro's default 401 -->
<main>
    <pair-key>roles</pair-key>
    <pair-value>org.opendaylight.aaa.shiro.filters.ODLRolesAuthorizationFilter</pair-value>
</main>

<urls>
    <pair-key>/token</pair-key>
    <pair-value>rest</pair-value>
</urls>
<urls>
    <pair-key>/**/operations/cluster-admin**</pair-key>
    <pair-value>testFilter, roles[admin]</pair-value>
</urls>
<urls>
    <pair-key>/**/v1/**</pair-key>
    <pair-value>testFilter, roles[admin]</pair-value>
</urls>
<urls>
    <pair-key>/**/data/aaa*/**</pair-key>
    <pair-value>testFilter, roles[admin]</pair-value>
</urls>
<urls>
    <pair-key>/**</pair-key>
    <pair-value>testFilter</pair-value>
</urls>
```

## Build & deploy

> **Prerequisite:** `<aaa-checkout>` must contain the following AAA-307 commits, and be built
> locally — published `aaa-shiro` 0.23.3-SNAPSHOT jars predate all of them:
>
> - "Refactor Oauth2ProxyHeaderToken parsing" ([Gerrit 123005](https://git.opendaylight.org/gerrit/c/aaa/+/123005)) —
>   without it this project does not compile (`Oauth2ProxyHeaderParser` doesn't exist yet)
> - "Expose Oauth2ProxyHeaderParser as OSGi service" (Change-Id `Ia94beee2d61a2d06c3afffcf4ef666cf5be8fe8b`) —
>   without it forwarded-header authentication stays fail-closed at runtime (no service to look up)
> - "Apply Oauth2ProxyHeaderFilter config instantly" (Change-Id `Icc7d068b05b2c31b0b4acc098e5ca7b15f0380ce`)
> - "Rename OAuth2 FilterConfig to ParserConfig" (Change-Id `I2df3c95cd87690db3a78789ac662c4537500d4fc`) —
>   the one with compile impact here: `Oauth2ProxyHeaderFilterConfig` was renamed to
>   `Oauth2ProxyHeaderParserConfig`, which this project's tests reference directly
> - "Document pluggable third-party filters" (Change-Id `I3651a84da502d616bfd2a3d71d07f9cd591b7a5d`)

```bash
# 1. build this project
mvn clean install

# 2. rebuild aaa-shiro impl so the xml/aaa-app-config artifact carries the wiring
cd <aaa-checkout> && mvn clean install -pl aaa-shiro/impl -DskipTests

# 3. make the artifacts resolvable — the ODL Karaf resolves mvn: URLs only from its system/ repo
#    by default. Either copy them in:
#    system/tech/pantheon/testfilter/{odl-aaa-testfilter,testfilter-config,testfilter-fragment}/1.0.0-SNAPSHOT/
#    (refresh the aaa-shiro jar + aaa-app-config.xml there too if the assembly predates step 2),
#    or configure Karaf to resolve from ~/.m2 directly — see below.

# 4. in the Karaf shell
feature:repo-add mvn:tech.pantheon.testfilter/odl-aaa-testfilter/1.0.0-SNAPSHOT/xml/features
feature:install odl-aaa-testfilter
```

### Resolving features from `~/.m2` instead of copying into `system/`

Add the local Maven repository to `etc/org.ops4j.pax.url.mvn.cfg` in the Karaf assembly:

```properties
org.ops4j.pax.url.mvn.repositories= \
    file:${karaf.home}/${karaf.default.repository}@id=system.repository, \
    file:${karaf.data}/kar@id=kar.repository@multi, \
    file:${user.home}/.m2/repository@id=local-m2@snapshots
```

Notes:

- The `@snapshots` suffix is required, otherwise `-SNAPSHOT` artifacts (like `1.0.0-SNAPSHOT`)
  will not resolve from that repository.
- With this in place, `feature:repo-add` / `feature:install` pull `mvn:` URLs straight from
  `~/.m2/repository` after every `mvn install` — no copying into `system/` needed.
- `target/assembly` is wiped whenever the `aaa/karaf` module is `mvn clean`-ed, so the edit must
  be re-applied after a distribution rebuild.

`bundle:list` must show the fragment as `Resolved` with `Hosts: <aaa-shiro bundle id>`. If the
shiro configuration was already seeded into the MD-SAL datastore before the wiring change, wipe
`data/` or `PUT` the `aaa-app-config:shiro-configuration` container via RESTCONF — the initial XML
is only read into an empty datastore.

## Verification

```bash
curl -u admin:anything http://localhost:8181/auth/v1/users            # allow-all: 200 (role admin), reject-all: 401
curl -u bob:anything http://localhost:8181/auth/v1/users              # allow-all: authenticated with role bob only,
                                                                      #   403 on roles[admin]-gated URLs
curl http://localhost:8181/auth/v1/users                              # 401 + WWW-Authenticate: BASIC
curl -H 'X-Forwarded-User: alice' -H 'X-Forwarded-Groups: role:admin' \
     http://localhost:8181/auth/v1/users                              # 200 in both modes
```

Expected results against `/auth/v1/users` (a `roles[admin]`-gated URL; mode flipped by editing the
cfg at runtime — applied by Config Admin within seconds, no restart):

| Request | allow-all | reject-all |
|---|---|---|
| `curl -u admin:anything` | 200 (username `admin` → role `admin`) | 401 |
| `curl -u bob:anything` | authenticated, but 403 on admin-gated URLs (role `bob`, `ODLRolesAuthorizationFilter`) | 401 |
| `X-Forwarded-User: alice` + `X-Forwarded-Groups: role:admin` | 200 | 200 (fallback bypasses the mode by design) |
| no headers | 401 + `WWW-Authenticate: BASIC` | same |

### Demonstrating the shared OAuth2-Proxy configuration

Proves that the common cfg file — the one ODL's own `Oauth2ProxyHeaderFilter` uses — governs this
filter's header parsing, live (Config Admin applies the edit within seconds, no restart: aaa's
config component restarts and the `Oauth2ProxyHeaderParser` service is re-registered with the new
limits, which rebinds `TestFilterParserBridge` — deactivated and reactivated on its static
reference — so the holder carries the new limits moments later):

```bash
# defaults
curl -H 'X-Forwarded-User: alice' -H 'X-Forwarded-Groups: role:admin' \
     http://localhost:8181/auth/v1/users                              # 200
curl -H 'X-Forwarded-User: al!ce' -H 'X-Forwarded-Groups: role:admin' \
     http://localhost:8181/auth/v1/users                              # 401 — '!' not in allowed-chars

# edit etc/org.opendaylight.aaa.shiro.oauth2proxyheaderparser.cfg: max-user-length = 4
curl -H 'X-Forwarded-User: alice' -H 'X-Forwarded-Groups: role:admin' \
     http://localhost:8181/auth/v1/users                              # 401 — 5 characters > 4
curl -H 'X-Forwarded-User: bob' -H 'X-Forwarded-Groups: role:admin' \
     http://localhost:8181/auth/v1/users                              # 200 — 3 characters
curl -u admin:anything http://localhost:8181/auth/v1/users            # 200 — Basic path unaffected

# revert max-user-length = 128 → alice gets 200 again

# strict groups validation: a malformed groups value (trailing comma) rejects the whole header —
# alice authenticates with zero roles, so a roles[admin]-gated URL answers 403
curl -H 'X-Forwarded-User: alice' -H 'X-Forwarded-Groups: role:admin,' \
     http://localhost:8181/auth/v1/users                              # 403
```

Sanity check in the Karaf shell: `scr:list | grep -i oauth2` must show aaa's
`Oauth2ProxyHeaderParserConfigImpl` and `Oauth2ProxyHeaderParserImpl` components, plus this
project's `TestFilterParserBridge` (from `testfilter-config`) bound to it — if any is missing or
unsatisfied, the holder stays unbound and every forwarded-header request gets 401.

Unit tests: `mvn test` (filter token selection, shared-config enforcement, realm modes, config
parsing).

## Security caveats

- **Evaluation only, not production** (see the warning at the top): replace the
  username-as-role/any-password simulation in `TestRealm` before using this as a template for real
  deployments. `TestFilter` logs one INFO line per authentication attempt naming the chosen
  mechanism (Basic vs. OAuth2-Proxy headers) — no header values or identities are logged.
- The `reject-all` mode only gates `TestRealm`. The X-Forwarded fallback trusts the proxy headers,
  so direct access to ODL bypassing OAuth2-Proxy must be blocked at the network level — otherwise
  anyone can forge the headers (same caveat as aaa's `Oauth2ProxyHeaderFilter`).
- If the shiro configuration references `TestFilter` while the fragment is not attached, AAA web
  environment initialization fails and all northbound authentication breaks. Install the feature
  before (or together with) the configuration.

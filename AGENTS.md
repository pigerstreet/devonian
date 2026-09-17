# AGENTS.md

Grounded in this repo and in the work history on it. Anything not observed as a
real pattern is marked **uncertain**.

---

## Project basics

A fork of **devonian**, a client-side Fabric mod for Hypixel Skyblock.

| | |
|---|---|
| `origin` | `https://github.com/pigerstreet/devonian.git` — the user's fork |
| `upstream` | `https://github.com/Synnerz/devonian.git` — DocilElm's project |
| Branch in use | **`26.1`** — the only branch the user cares about |
| Other local branches | `26.1-local`, `26.2`, `backup-26.1-presync` |
| License | **GPL-3.0** |

Upstream's `26.2` / `26.3` target Minecraft 26.2 / 26.3-pre-1. They are a **port**,
not an update — never rebase `26.1` onto them.

### Stack (from `gradle.properties` / `build.gradle`)

- Minecraft **26.1.2**, Fabric Loader 0.19.3, Fabric API `0.150.0+26.1.2`
- Kotlin **2.3.20**, fabric-language-kotlin `1.13.10+kotlin.2.3.20`
- Fabric Loom `1.16-SNAPSHOT`
- Java **25** (`options.release = 25`, `jvmTarget = 25`, **no toolchain block** — see Gotchas)
- Mod version `1.31.9`, group `com.github.synnerz.devonian`
- Entrypoint: `com.github.synnerz.devonian.Devonian` (Kotlin adapter, client env only)
- ~416 `.kt` files under `src/main/kotlin/com/github/synnerz/devonian/`
- Extra deps: `talium` (jar-in-jar via the `embed` configuration), `net.hypixel:mod-api:1.0.1`,
  one Modrinth artifact (`mOgUt4GM`)
- Mixins in `src/main/resources/devonian.mixins.json`; access widener `devonian.accesswidener`
- **There are no unit tests.** `src/` contains `main` and `scripts` only. "Testing" here means
  build + boot the client + compare warnings.

### Code layout

```
api/         EventBus, Event, EventListener, ChatUtils, Scheduler, WebRequests, dungeon/, events/
config/      Config, ConfigData, PersistentData, json/, ui/talium/ConfigGui
commands/    DevonianCommand (/dv)
features/    the actual features — dungeons/, diana/, garden/, kuudra/, misc/, slayers/, end/, debug/
hud/         HudManager and friends
mixin/       all the *Mixin classes (registered in devonian.mixins.json)
```

Features register listeners with `on<SomeEvent> { ... }` on the `EventBus`. Events marked
`@Threaded` (defined in `api/events/Event.kt`) fire on the **netty** thread — that annotation
is the single most important thing to check before touching shared state.

### Runtime data

Config and per-feature state live in `run/config/devonian/` (`devonianConfig.json` holds the
settings; ~12 sibling JSON files hold feature data such as `lootlogger.json`, `runslogger.json`,
`prices.json`). There is a `backups/` dir and an autosave cycle. There is **no world in
`run/saves`**, so nothing that only runs in-world can be exercised locally.

---

## Commands

Every Gradle call needs JDK 25 exported in the *same* shell invocation (shell state does not
persist between tool calls):

```bash
export JAVA_HOME="C:/Users/Administrator/.gradle/jdks/eclipse_adoptium-25-amd64-windows.2"

./gradlew build          # a healthy build prints exactly 44 warnings
./gradlew clean build    # full rebuild — never run while a dev client is live
./gradlew runClient      # see the fabric.mod.json workaround below
```

Do **not** pass `-q`: it suppresses the compiler warnings, and the warning count is the
regression signal.

The produced jar is `build/libs/devonian-<version>.jar`.

### CI

`.github/workflows/build.yml` runs on **every push to any branch** plus `workflow_dispatch`.
It uses JDK 25 (microsoft distro), runs `./gradlew build`, and uploads `build/libs/`. It is a
useful independent check that a pushed commit compiles on a clean machine.

---

## Workflow rules

**Stated explicitly by the user, standing:**

1. **Always commit and push to `26.1` on `origin` when work is done.** Do not stop to ask
   permission for each push. Stated 2026-09-04 and repeated since.
2. **"Make sure nothing has been negatively affected or no features are broken."** This is a
   verification mandate on every change, not a nice-to-have.
3. **Keep it easy to sync.** One concern per commit, minimal file touches, so any single commit
   can be dropped (`git rebase --skip`) or a conflicting hunk re-applied by hand without losing
   the rest.
4. **GPL-3.0 discipline:** keep patches in the project's own style and **never paste code from
   other mods**.

**Syncing from upstream — the model changed, do not use the old one:**

The user syncs with GitHub's **"Sync fork"** button, which creates a **merge commit** on
`origin/26.1` (authored by the user's GitHub account). Local sync is therefore:

```bash
git fetch origin
git merge --ff-only origin/26.1     # should always be a fast-forward
```

- **Never `git rebase` or `git push --force` over `origin/26.1`.** That would rewrite the
  user's merge commits. (An older memory note still says "rebase, don't fast-forward" — that
  predates the switch to Sync fork and is superseded.)
- Verify a sync rather than trusting it:
  `git merge-tree --write-tree <old-local> upstream/26.1` must equal
  `git rev-parse origin/26.1^{tree}`.
- Sync fork **refuses to merge when it would conflict**, leaving manual resolution. So before
  patching a file, check `git log upstream/26.1 -- <file>`; if upstream is actively rewriting it,
  keep the hunk tiny or leave it to upstream and report it instead.

**Fork guard — the workflow that scans syncs (`fork-guard.yml`, `.github/fork-guard/`):**

Every push to `26.1` (so every Sync fork) is reviewed by a headless Claude agent before anyone
trusts it: `scan.sh` triages the pushed range (docs/assets never reach the model), chunks the
rest, runs up to 4 read-only agent calls, and `remediate.sh` acts on the verdict — `MALICIOUS`
reverts the offending commits and opens an issue with restore instructions, `SUSPICIOUS` only
opens an issue. Both push the run red.

- **`26.2` is the trust anchor: never Sync fork it.** A push to `26.1` runs the guard scripts
  read from `origin/26.2`, not from the pushed tree, so a bad commit on `26.1` cannot neuter the
  scan it triggers. `26.2` also carries the daily scheduled run (`23 5 * * *`), which re-scans
  anything since the `guard-last-scan-26.1` tag. Syncing upstream into `26.2` would let an
  upstream commit edit the guard scripts and the workflow file — the one path that defeats the
  whole design.
- **When editing the guard, change `26.2` first, then `26.1`, and keep the three
  `.github/fork-guard/` files byte-identical on both** (the workflow file too — only the guard
  scripts are read cross-branch, so drift there is silent).
- **`guard-last-scan-26.1`** is the incremental bookmark; it only ever moves forward, and a
  non-`CLEAN` run never advances it, so flagged ranges get re-examined.
- Repo variable `FORK_GUARD_DRY_RUN=true` makes every run report-only (no revert, push, cancel or
  issue). Delete it to arm real remediation. `SCAN_MODEL` / `SCAN_SMALL_MODEL` pin the models;
  the key lives in the `ORCAROUTER_API_KEY` secret.
- Guard scripts are ordered/quoting-sensitive bash run under `set -uo pipefail`; test a change
  with `workflow_dispatch` on a small range before trusting it.

**Commit messages** (observed style, all local commits follow it):

- Lowercase conventional prefix: `fix:`, `feat:`, `internal:`, `build:`
- Subject states the *user-visible symptom*, not the mechanism
  (e.g. `fix: SafariUniqueTracker disconnected you when handing mobs to a teammate`)
- Body explains the mechanism, shows the offending snippet, and says what changed and why it is
  safe. Bodies are several paragraphs — this level of detail is the norm here.
- End with the attribution trailer the harness reminder currently specifies
  (`Co-Authored-By: Claude Code <noreply@anthropic.com>`). Older commits carry
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>` plus a `Claude-Session:` line —
  don't retro-fit history.
- Commit author is `pigerstreet <whuazemc@gmail.com>`, set repo-locally.

---

## Verification (mandatory, in this order)

A green build proves almost nothing here. See the `EventBus.kt` story under Gotchas.

1. **Build twice** — before and after — and diff the warning set. Baseline is **44 warnings**;
   the set should be identical, with only line numbers shifting where lines were added.
2. **Boot the dev client** on the committed tree. Requires the `fabric.mod.json` workaround
   below. Check for: mixin failures, any Devonian exception, and that it reaches the title screen.
3. **Confirm the build actually recompiled.** `compileKotlin` can print
   `BUILD SUCCESSFUL in 2s / UP-TO-DATE` even after a real edit. Check the `.class` file mtime
   against `date` instead of trusting the BUILD line.
4. **Config round-trip.** Let an autosave happen, then compare `run/config/devonian/`:
   no keys lost, no values changed, all JSON files parse, no stray `.tmp` files.
   (New keys appearing is expected when upstream adds settings.) Last observed baseline:
   41 keys / 976 entries / 12 feature files — treat these as *approximate*, they move with
   upstream.
5. **Reproduce the bug before fixing it.** Every fix shipped so far was demonstrated first,
   usually with a small standalone Java program run against the same shapes (e.g. proving that
   `forEach` + `remove` throws on a `LinkedHashSet` with 2 elements but not 1). Keep doing this;
   it is what makes the claims in the write-up trustworthy.
6. **Check the fork's own CI run** for the push succeeded.

**`runClient` workaround:** `fabric.mod.json` declares `"hypixel-mod-api": ">=1.0.1"` as a hard
`depends`, but `build.gradle` supplies it as plain `implementation` (not `modImplementation`),
so the dev client fails with `HARD_DEP_NO_CANDIDATE`. This is **pre-existing and upstream's to
fix.** Temporarily delete that one `depends` line to launch, then `git checkout --` the file and
confirm `git diff` on it is empty. Verify the **built jar** still carries the real dependency
list.

**Finding a running dev client:** filtering `Win32_Process` on a command line containing
`devonian\run` does **not** work — loom passes the run directory inside
`build/loom-cache/argFiles/runClient`, so the visible command line only shows
`-Dfabric.dli.env=client`. Match on that. Always confirm the previous client is dead before
launching another, or two clients fight over `run/logs/latest.log`.

---

## Known issues and gotchas

### Environment

- **JDK 25 is not the default.** `build.gradle` pins release 25 with no toolchain block, so javac
  runs on whatever JVM the Gradle daemon started with. The machine default is Temurin 21 and
  `JAVA_HOME` is unset in both PowerShell and the Bash tool → `error: release version 25 not
  supported`. This looks like the rebase or a source change broke the build. It didn't.
- Windows, PowerShell 5.1 primary, Bash tool available. `grep -P` is needed for
  lookahead/lookbehind patterns; `-E` silently matches nothing.
- Heredoc scripts containing `\` inside a `%`-format string raise Python `SyntaxError` — write
  scratch scripts to a file with the Write tool instead.

### Minecraft 26.1.2 packet semantics (the big one)

`PacketProcessor$ListenerAndPacket.handle()` catches `Exception` →
`PacketUtils.makeReportedException` → `PacketListener.onPacketError` →
`ClientCommonPacketListenerImpl.onPacketError`, which logs *"Failed to handle packet,
disconnecting"* and calls `Connection.disconnect`.

Fabric's `handleSystemChat` / `handlePing` **are** internal packet handlers. So a throw in a
`ChatEvent` or `ClientThreadServerTickEvent` listener **disconnects the player from the server**.
That is why netty-path exceptions get fixed even when the visible damage looks small.

### Kotlin / collection traps confirmed in this codebase

- `Set.forEach` compiles to an `Iterator` loop (javap-confirmed). `set.remove(x)` inside it throws
  `ConcurrentModificationException` **on the second element** — with one element it does not,
  which is why such bugs survive testing. Fix: iterate to collect, then `clear()`.
- **`EnumSet` iterators are snapshot-based and not fail-fast** — `DragonSpawnTimer` clears
  `spawned` inside its own `forEach` and is *correct*. Do not "fix" it.
- `FixedIdentityMap(maxSize)` holds exactly `maxSize - 1` entries (`removeEldestEntry` returns
  `size >= maxSize`).
- `Double.toInt()` **saturates** at `Int.MAX_VALUE`; `String.toInt()` **throws** on a decimal
  point. `parseShortenedNumber` returns `Int`, so it caps at ~2.147b (known backlog item —
  Searchbar's `>5b` filter is really `>=2.147b`).
- Cross-thread state: netty writes it, the client thread reads it every tick and the **autosave
  thread** walks it in `onPreSave`. A throw in `onPreSave` loses that autosave. Use
  `CopyOnWriteArrayList` for small, rarely-written shared lists; hand work to the client thread
  via `Scheduler.scheduleTask` otherwise.
- Per-frame state (`res`, `failReason` in `EtherwarpOverlay`) must be reset at the **top** of the
  render handler. Early returns that skip the reset leak last frame's value into other features.

### Fragile / confusing areas

- `EventListener.trigger` has **no try/catch** — one throwing listener takes the rest down and,
  on a packet path, disconnects. Long-standing backlog item; not yet fixed.
- `ConnectionMixin` splits a `ClientboundBundlePacket` when a listener cancels one sub-packet,
  so an entity's spawn/metadata/equipment can straddle a frame. Cancelling the bundle itself
  doesn't actually drop it. Backlog.
- `DevonianCommand` keeps a 100-entry tab-list debug buffer for **every** user, mutated on netty
  and cleared on the client thread at world change. Narrow race; judged not worth a commit.
- Highest upstream-collision-risk files (they change often): `EventBus.kt`, `Feature.kt`,
  `StringUtils.kt`, `ChatUtils.kt`, and the render mixins.

### A past mistake worth not repeating

A Kotlin `object` property-initialiser ordering mistake in `EventBus.kt` compiled **green both
before and after** but stopped the mod loading entirely (`NullPointerException` in `<clinit>` →
*"Could not execute entrypoint stage 'client'"*). Only booting the client caught it. This is the
origin of the "never report verified on a green build alone" rule.

Also: `./gradlew clean build` was once run while a dev client was live. The client survived, but
don't.

---

## Working with this user

**Observed, not stated as rules — treat as defaults, correct me if wrong:**

- Instructions arrive short and unpunctuated ("do some more research and look again for bugs...").
  They pack a lot in; read the whole message before acting, and note that follow-up requests can
  arrive mid-turn while earlier work is still running.
- Structured multi-section summaries with commit SHAs, before/after numbers and a "Sync risk"
  note have not drawn pushback. Keep leading with **what changed and what it means for them**,
  not with process. Plain-language headers ("Fixed", "Checked", "Sync risk") work well.
- **Thoroughness is explicitly wanted.** "Look as thoroughly as possible through the entire
  codebase" — repeated passes over the whole tree, not spot fixes.
- **A running HTML report** of all findings is maintained and republished after each pass
  (currently `https://claude.ai/artifact/Hy8peVT11RdvGPMesS4wTy`, outside the repo). Update it
  when shipping fixes. **Uncertain:** it has never been explicitly requested by name each pass;
  it started as a deliverable and has been kept.
- **Bugs in code upstream is actively rewriting get reported, not patched** — that choice was made
  repeatedly this session (ProtectValuableItems' stale LSHIFT text, `parseShortenedNumber`
  saturation, CroesusProfit's cross-thread chest data) and never objected to. **Uncertain** as a
  hard rule; the reasoning is the Sync-fork conflict cost.
- A **backlog** of known-but-unfixed findings is maintained (28 items as of the ninth pass) and
  carried forward rather than dropped.
- Destructive git operations (force-push, rebase over the user's merge commits) are out —
  see the sync rules. Nothing was ever explicitly forbidden beyond that, but the Sync-fork
  switch makes them harmful.

**Do not ask before committing and pushing.** That was stated plainly and the user was annoyed at
having to confirm it twice early on.

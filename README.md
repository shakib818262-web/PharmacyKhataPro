# ফার্মেসি খাতা Pro (PharmacyKhata Pro)

Professional, **offline-first, multi-tenant** pharmacy management & POS Android app for
pharmacies in Bangladesh — built to be sold by Shakib to pharmacy owners (his customers).
Bengali-first UI (Anek Bangla), money always in **integer paisa** (no `Double` for money),
batch-based stock with **FEFO** selling, partial-payment khata ledger, and a bundled
Bangladesh medicine catalog with autocomplete.

`applicationId`: `com.shakib.pharmacykhata` · Kotlin 2.0.21 · AGP 8.7.3 · Gradle 8.9 ·
compile/target SDK 35 · min SDK 24 · Jetpack Compose + Material 3 · Room 2.6.1 ·
Hilt 2.53.1 · Firebase BOM 33.7.0 (Auth + Firestore)

## Architecture

```
UI (Compose screens) → ViewModels → Repositories → Room (offline-first) / Firebase
Domain (pure Kotlin, unit-tested): Money, FEFO, SaleCalculator, LedgerCalculator, Validators
```

- `domain/` — models, `Money` (Long paisa + Bengali-digit parse/format + lakh grouping),
  FEFO allocation, sale math (discount clamping, change, due, honest profit incl. losses),
  ledger balance derived from entries (ledger is the source of truth), BD phone &
  expiry validation (expiry must parse — never free text).
- `data/local/` — Room DB `pharmacy_pro.db`: medicines, batches, sales (+items+allocations),
  purchases (+items), parties, ledger entries. **Every table and query is scoped by
  `pharmacyId`** (= owner uid), so accounts on the same phone are fully isolated.
- `data/catalog/` — separate Room DB `catalog.db` (see below).
- `data/remote/AuthRepository` — Firebase Auth (email/password) + `pharmacy_profiles/{uid}`.
- Sales decrement stock **transactionally** (`@Transaction` via `withTransaction` +
  guarded `UPDATE … WHERE quantity >= :qty`), re-reading stock inside the transaction —
  never from a stale cart snapshot.
- No demo/seed business data anywhere. No "load demo data" button. Ever.

## Features (milestone 1)

Auth (register with pharmacy profile + 14-day trial, login, sign-out, optional PIN app lock),
Dashboard (today's sales/profit/due, collection by payment method, low-stock & near-expiry
alerts with filters), Inventory (catalog autocomplete add-stock, batch/expiry/prices,
validation), POS (search + working CameraX/ML Kit barcode scan, cart, discount, partial
payment, change/due, FEFO sale, receipt screen + share), Purchase (supplier required,
inline supplier creation, duplicate-invoice check, price/stock updates, new medicine creates
stock), Khata (customer/supplier ledgers from entries, payments with method, overpayment
becomes advance credit — never phantom income), Reports (period sales/profit incl. losses,
stock value, per-medicine breakdown), Settings (profile, subscription status, PIN,
versioned JSON backup export).

Expired subscription: new sales are blocked, data stays readable and exportable.

## Firebase setup

1. Create a Firebase project; enable **Email/Password** in Authentication and **Firestore**.
2. Register the Android app with package `com.shakib.pharmacykhata`; download
   `google-services.json` into `app/` (it is gitignored — never commit a real one).
3. Deploy `firestore.rules` (Firebase console → Firestore → Rules, or `firebase deploy --only firestore:rules`).
4. Create `app_meta/admins` doc: `{ "emails": ["shakib's admin email"] }` from the console —
   this is the super-admin allowlist (or set an `admin: true` custom claim instead).

### Tenancy & admin

- Registration writes `pharmacy_profiles/{uid}`: pharmacy name, owner, phone, address,
  district, optional drug licence, join date, last-active, and `subscription`
  `{status: TRIAL, plan, trialStartAt: SERVER timestamp, trialDays: 14, expiresAt}`.
  Trial length is anchored to the server timestamp; rules forbid owners from editing
  subscription fields — only the admin extends/activates plans (after bKash/Nagad payment).
- The admin can read **only** `pharmacy_profiles` metadata. Rules give admin **no** access
  to `pharmacies/{pharmacyId}/**` business data — that privacy boundary is a selling point.
- Admin UI (in-app/web panel) is **not** in this milestone; admin tasks are done from the
  Firebase console for now (see Known gaps).

## Medicine catalog

- Bundled seed: `app/src/main/assets/medicines_seed.csv` (~67 common BD medicines for dev/first-run).
- **The full catalog drops in by replacing that one file.** Schema (must match exactly):
  `id,brand_name,generic_name,strength,form,manufacturer,pack_size,unit_mrp_taka,barcode`
  The full set is the cleaned Kaggle "Assorted Medicine Dataset of Bangladesh" (~21,000
  brands: brand, generic, strength, form, manufacturer, pack size, unit MRP).
  `CatalogRepository.replaceCatalog(csvText)` can also swap the catalog at runtime;
  pharmacy-added custom medicines live in a separate table and survive catalog updates.
- Search: user medicines first, then indexed brand-prefix matches, then contains/generic matches.
- Prices from the catalog are **reference MRP**; the pharmacy sets its own sale price per batch.
- ⚠️ **Before commercial rollout:** the dataset was scraped from MedEx and its data licence
  is unverified. Obtain written permission (MedEx / dataset author) and/or file a formal
  DGDA data request, and keep attribution in-app. See project research notes.

## Build

Local: JDK 17 + Android SDK 35. Put your real `google-services.json` in `app/` (or the CI
placeholder will be needed — locally, generate one the same way the workflow does).

```bash
./gradlew testDebugUnitTest   # domain unit tests (money, FEFO, sale, ledger, validators)
./gradlew lintDebug
./gradlew assembleDebug
```

CI (`.github/workflows/build.yml`) runs the same three steps and uploads the debug APK.
It explicitly fixes the old app's two CI failures:
1. **Missing debug keystore** — this app uses Gradle's *default* debug signing and CI
   additionally generates `~/.android/debug.keystore` with `keytool` before building.
2. **`R.string.default_web_client_id` unresolved** — app code never references it
   (email/password auth only), and the CI placeholder `google-services.json` includes a
   web `oauth_client` (`client_type: 3`) so the resource resolves anyway. The real file
   is supplied via the `GOOGLE_SERVICES_JSON` CI secret when present.

Release signing: set `RELEASE_STORE_FILE`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
`RELEASE_KEY_PASSWORD` env vars; without them the release build is unsigned (dev only).

## Known gaps (deliberate, for later milestones)

- Firestore sync of business data: rules + schema are in place and profiles are written,
  but Room is currently the only business-data store; two-way sync is a later milestone.
- Admin panel UI (list/activate pharmacies) — console-based for now.
- Backup export covers medicines/batches/sales(+items) as versioned JSON; restore/import
  and purchases/parties/ledger export are not yet implemented.
- Receipt is a summary screen + shareable text (no PDF/Bluetooth thermal printing yet).
- Staff accounts, audit log UI, catalog over-the-air updates, payment gateway — v1.1/v2 per design.
- Rules are written carefully but have not yet been run against the Firestore emulator test suite.

## Snapshot notes (GitHub connector upload)

This repository is uploaded via a text-only channel, so a few binary assets
are intentionally not in git:

- **Launcher icons** are pure XML (`res/drawable/ic_launcher_full.xml`,
  layered background + logo vectors) instead of PNG mipmaps.
- **Font**: the UI uses the system font family — Android devices sold in
  Bangladesh render Bengali correctly out of the box.
- **Gradle wrapper jar** is not committed; CI installs Gradle 8.9 via
  `gradle/actions/setup-gradle`. To use `./gradlew` locally, run
  `gradle wrapper --gradle-version 8.9` once in a full checkout.

For a Play Store release, regenerate PNG mipmaps + the wrapper jar from a
full git client and commit them normally.

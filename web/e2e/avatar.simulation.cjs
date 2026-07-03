// avatar.simulation.cjs — headless Node simulation of the OSK-83 avatar upload flow.
//
// WHY this exists: live sign-in + a live Firebase Storage bucket cannot be exercised in
// CI/locally (needs the human apiKey — OSK-92 — firebase-scoped ADC — OSK-38 — and the
// Storage product enabled — OSK-95). But the avatar feature's core behaviour — VALIDATING
// the picked file (content-type + size), building the per-user Storage object PATH, and the
// pick → preview → upload → PUT /api/v1/me/avatar → reflect-photoURL flow with graceful
// 400/401/503/network/storage-failure handling — is all pure/DOM-shaped logic that
// web/profile.js (the controller + validator) and web/auth.js (the pure path builder) export
// for exactly this reason. This script requires those exports and drives them with a FAKE DOM
// + STUBBED fetch + a STUBBED upload seam + a fake token source, asserting that:
//   - validateAvatarFile()  accepts a real image, rejects wrong-type / oversize / empty / null,
//   - avatarObjectPath()    always lands under users/<uid>/avatar/ with an allowed extension,
//   - selectFile (valid)    => previews the file and ARMS the Upload button,
//   - selectFile (invalid)  => shows the reason and DISARMS Upload (no file retained),
//   - upload (happy)        => uploads then PUTs { objectPath, downloadUrl } with a Bearer
//                              token, adopts the returned photoURL as the shown avatar, resets,
//   - upload (no file)      => shows an error and makes NO network call,
//   - upload (400/401/503)  => renders a graceful message, no success,
//   - upload (storage fail) => the Storage upload rejecting is surfaced, NO PUT is sent,
//   - showCurrent()         => renders the caller's live photoURL into the preview.
//
// It is a plain assertion harness (no framework, no deps): prints each check and exits
// non-zero on the first failure, so it doubles as a CI-friendly gate. It is a `.cjs` file
// OUTSIDE web/e2e/tests/, so Playwright (testDir ./tests) never picks it up.
//
// Run:  node web/e2e/avatar.simulation.cjs

"use strict";

const path = require("node:path");
// Require the REAL modules under test. Both are classic browser scripts that also export
// their pure helpers + DOM-injectable controllers when required from Node (their browser
// bootstraps are skipped because `window` is undefined here), so this asserts the SAME code
// the page runs.
const profile = require(path.resolve(__dirname, "..", "profile.js"));
const auth = require(path.resolve(__dirname, "..", "auth.js"));

let passed = 0;
let failed = 0;

function assert(label, cond) {
  if (cond) {
    passed++;
    console.log("  ok  - " + label);
  } else {
    failed++;
    console.log("  FAIL- " + label);
  }
}
function assertEqual(label, actual, expected) {
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    actual === expected,
  );
}
function assertDeep(label, actual, expected) {
  assert(
    label + " (expected " + JSON.stringify(expected) + ", got " + JSON.stringify(actual) + ")",
    JSON.stringify(actual) === JSON.stringify(expected),
  );
}

// ---------------------------------------------------------------------------
// Fakes: a tiny DOM (auto-creating elements, incl. an `.src` for the <img>), a fetch stub
// that records calls + returns canned responses, an upload seam stub, a preview-URL stub, and
// a token source. None of jsdom/browser needed — the controller only touches
// .src / .value / .textContent / .hidden / .disabled.
// ---------------------------------------------------------------------------
function makeFakeDoc() {
  const store = {};
  return {
    _store: store,
    getElementById(id) {
      if (!store[id]) {
        store[id] = { id, src: "", value: "", textContent: "", hidden: false, disabled: false };
      }
      return store[id];
    },
  };
}

// handler(url, opts, callIndex) -> { ok, status, body }
function makeFetchStub(handler) {
  const calls = [];
  const fn = function (url, opts) {
    calls.push({ url, opts });
    const r = handler(url, opts, calls.length) || {};
    return Promise.resolve({
      ok: r.ok !== undefined ? r.ok : r.status >= 200 && r.status < 300,
      status: r.status,
      json: function () {
        if (r.jsonThrows) { return Promise.reject(new Error("bad json")); }
        return Promise.resolve(r.body);
      },
    });
  };
  fn.calls = calls;
  return fn;
}

// An upload seam that resolves to a storage reference, recording the files it was given.
function makeUploadStub(ref) {
  const calls = [];
  const fn = function (file) {
    calls.push(file);
    return Promise.resolve(ref);
  };
  fn.calls = calls;
  return fn;
}
// An upload seam that rejects (Storage permission/network failure).
function makeFailingUploadStub() {
  const calls = [];
  const fn = function (file) {
    calls.push(file);
    return Promise.reject(new Error("storage denied"));
  };
  fn.calls = calls;
  return fn;
}

function tokenSource(token) {
  return function () { return Promise.resolve(token); };
}

// A File-like object; the controller/validator only read .name / .type / .size.
function fakeFile(over) {
  return Object.assign({ name: "me.png", type: "image/png", size: 1024 }, over || {});
}

const UID = "firebase-uid-123";
const OBJECT_PATH = "users/" + UID + "/avatar/1720000000000.png";
const DOWNLOAD_URL =
  "https://firebasestorage.googleapis.com/v0/b/openskeleton-one.firebasestorage.app/o/" +
  "users%2Ffirebase-uid-123%2Favatar%2F1720000000000.png?alt=media&token=abc";
const STORAGE_REF = { objectPath: OBJECT_PATH, downloadUrl: DOWNLOAD_URL };

function makeController(over) {
  const doc = makeFakeDoc();
  const deps = Object.assign(
    {
      doc,
      fetchFn: makeFetchStub(function () { return { status: 200, body: { photoUrl: DOWNLOAD_URL } }; }),
      getIdToken: tokenSource("fake-token"),
      uploadFn: makeUploadStub(STORAGE_REF),
      apiBaseUrl: "http://127.0.0.1:8080",
      previewUrlFor: function () { return "blob:preview"; },
    },
    over || {},
  );
  return { doc, deps, controller: profile.createAvatarController(deps) };
}

// ===========================================================================
// 1) PURE: validateAvatarFile — content-type + size gate.
// ===========================================================================
console.log("\nvalidateAvatarFile() — content-type + size gate:");
{
  assertEqual("a real PNG is accepted", profile.validateAvatarFile(fakeFile()).ok, true);
  assertEqual("a JPEG is accepted", profile.validateAvatarFile(fakeFile({ type: "image/jpeg" })).ok, true);
  assertEqual("a WebP is accepted", profile.validateAvatarFile(fakeFile({ type: "image/webp" })).ok, true);
  assertEqual("a GIF is accepted", profile.validateAvatarFile(fakeFile({ type: "image/gif" })).ok, true);

  const wrongType = profile.validateAvatarFile(fakeFile({ type: "application/pdf" }));
  assertEqual("a non-image is rejected", wrongType.ok, false);
  assert("wrong-type error mentions the allowed formats", /PNG|JPEG|WebP|GIF/.test(wrongType.error));

  // SVG is deliberately excluded (can carry script).
  assertEqual("SVG is rejected", profile.validateAvatarFile(fakeFile({ type: "image/svg+xml" })).ok, false);

  const tooBig = profile.validateAvatarFile(fakeFile({ size: profile.OSK_AVATAR_MAX_BYTES + 1 }));
  assertEqual("an oversize image is rejected", tooBig.ok, false);
  assert("oversize error mentions the 5 MB limit", /5\s*MB/.test(tooBig.error));

  assertEqual("a 0-byte image is rejected", profile.validateAvatarFile(fakeFile({ size: 0 })).ok, false);
  assertEqual("null (cancelled picker) is rejected", profile.validateAvatarFile(null).ok, false);
  assertEqual("the max is exactly 5 MiB", profile.OSK_AVATAR_MAX_BYTES, 5 * 1024 * 1024);
}

// ===========================================================================
// 2) PURE: avatarObjectPath — always under the caller's own users/<uid>/avatar/.
// ===========================================================================
console.log("\navatarObjectPath() — per-user storage path:");
{
  const p1 = auth.avatarObjectPath(UID, fakeFile({ name: "Selfie.PNG" }), 1720000000000);
  assertEqual("path is under the caller's own avatar folder + keeps extension",
    p1, "users/firebase-uid-123/avatar/1720000000000.png");

  // No usable filename extension => fall back to the extension implied by the MIME type.
  const p2 = auth.avatarObjectPath(UID, { name: "capture", type: "image/jpeg" }, 42);
  assertEqual("extension falls back to the MIME type", p2, "users/firebase-uid-123/avatar/42.jpg");

  // Unknown filename extension but a known MIME type => use the MIME type's extension.
  const p3 = auth.avatarObjectPath(UID, { name: "x.bmp", type: "image/webp" }, 7);
  assertEqual("unknown ext falls back to MIME ext", p3, "users/firebase-uid-123/avatar/7.webp");

  // Neither a known extension nor a known MIME type => safe png default (still an allowed image).
  const p4 = auth.avatarObjectPath(UID, { name: "blob", type: "application/octet-stream" }, 9);
  assertEqual("unknown everything defaults to png", p4, "users/firebase-uid-123/avatar/9.png");

  assert("path always starts with users/<uid>/avatar/", /^users\/firebase-uid-123\/avatar\//.test(p1));
}

async function run() {
  // =========================================================================
  // 3) selectFile — valid pick previews + arms Upload.
  // =========================================================================
  console.log("\ncontroller.selectFile() — valid pick previews + arms Upload:");
  {
    const { doc, controller } = makeController();
    const out = controller.selectFile(fakeFile());
    assertEqual("selection is accepted", out.ok, true);
    assertEqual("preview image src set", doc.getElementById("pf-avatar-img").src, "blob:preview");
    assertEqual("preview image shown", doc.getElementById("pf-avatar-img").hidden, false);
    assertEqual("upload button armed", doc.getElementById("pf-avatar-upload").disabled, false);
    assertEqual("no error shown", doc.getElementById("pf-avatar-error").hidden, true);
    assert("file retained for upload", controller.getSelectedFile() !== null);
  }

  // -------------------------------------------------------------------------
  // 4) selectFile — invalid pick shows the reason + disarms Upload.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.selectFile() — invalid pick is rejected inline:");
  {
    const { doc, controller } = makeController();
    const out = controller.selectFile(fakeFile({ type: "application/pdf" }));
    assertEqual("selection rejected", out.ok, false);
    assertEqual("error shown", doc.getElementById("pf-avatar-error").hidden, false);
    assertEqual("upload button disarmed", doc.getElementById("pf-avatar-upload").disabled, true);
    assert("no file retained", controller.getSelectedFile() === null);
  }

  // -------------------------------------------------------------------------
  // 5) upload — happy path: upload then PUT, adopt photoURL, reset.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.upload() — uploads, PUTs, reflects photoURL:");
  {
    const uploadStub = makeUploadStub(STORAGE_REF);
    const fetchStub = makeFetchStub(function () {
      return { status: 200, body: { uid: UID, photoUrl: DOWNLOAD_URL } };
    });
    const { doc, controller } = makeController({ uploadFn: uploadStub, fetchFn: fetchStub });

    controller.selectFile(fakeFile());
    const out = await controller.upload();

    assertEqual("upload ok", out.ok, true);
    assertEqual("storage upload called once", uploadStub.calls.length, 1);
    assertEqual("backend PUT called once", fetchStub.calls.length, 1);
    const call = fetchStub.calls[0];
    assert("PUT hit /api/v1/me/avatar", /\/api\/v1\/me\/avatar$/.test(call.url));
    assertEqual("method is PUT", call.opts.method, "PUT");
    assertEqual("Bearer token carried", call.opts.headers.Authorization, "Bearer fake-token");
    const body = JSON.parse(call.opts.body);
    assertDeep("body carries the storage reference", body, {
      objectPath: OBJECT_PATH,
      downloadUrl: DOWNLOAD_URL,
    });
    assertEqual("avatar image adopts the returned photoURL", doc.getElementById("pf-avatar-img").src, DOWNLOAD_URL);
    assertEqual("success shown", doc.getElementById("pf-avatar-success").textContent, "Avatar updated.");
    assertEqual("file input reset", doc.getElementById("pf-avatar-file").value, "");
    assert("selection cleared after success", controller.getSelectedFile() === null);
    assertEqual("upload button disabled again after reset", doc.getElementById("pf-avatar-upload").disabled, true);
  }

  // -------------------------------------------------------------------------
  // 6) upload — nothing selected: error, NO network call.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.upload() — nothing selected => no request:");
  {
    const uploadStub = makeUploadStub(STORAGE_REF);
    const fetchStub = makeFetchStub(function () { return { status: 200, body: {} }; });
    const { doc, controller } = makeController({ uploadFn: uploadStub, fetchFn: fetchStub });

    const out = await controller.upload();
    assertEqual("no-file outcome", out.reason, "no-file");
    assertEqual("no storage upload", uploadStub.calls.length, 0);
    assertEqual("no backend call", fetchStub.calls.length, 0);
    assertEqual("error shown", doc.getElementById("pf-avatar-error").hidden, false);
  }

  // -------------------------------------------------------------------------
  // 7) upload — backend 400 renders a graceful message.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.upload() — 400 degrades gracefully:");
  {
    const fetchStub = makeFetchStub(function () {
      return {
        status: 400,
        body: { title: "Bad Request", status: 400, detail: "Avatar must be a PNG, JPEG, WebP or GIF image." },
      };
    });
    const { doc, controller } = makeController({ fetchFn: fetchStub });
    controller.selectFile(fakeFile());
    const out = await controller.upload();

    assertEqual("outcome status 400", out.status, 400);
    assert("error surfaces the ProblemDetail detail", /PNG/.test(doc.getElementById("pf-avatar-error").textContent));
    assertEqual("no success on a rejected upload", doc.getElementById("pf-avatar-success").hidden, true);
  }

  // -------------------------------------------------------------------------
  // 8) upload — 401 + 503 degrade gracefully.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.upload() — 401 / 503 degrade gracefully:");
  {
    const { doc, controller } = makeController({
      fetchFn: makeFetchStub(function () { return { status: 401, body: {} }; }),
    });
    controller.selectFile(fakeFile());
    const out = await controller.upload();
    assertEqual("outcome status 401", out.status, 401);
    assert("error mentions 401", /401/.test(doc.getElementById("pf-avatar-error").textContent));
    assertEqual("upload re-enabled after failure (file still selected)",
      doc.getElementById("pf-avatar-upload").disabled, false);
  }
  {
    const { doc, controller } = makeController({
      fetchFn: makeFetchStub(function () { return { status: 503, body: {} }; }),
    });
    controller.selectFile(fakeFile());
    const out = await controller.upload();
    assertEqual("outcome status 503", out.status, 503);
    assert("error mentions unavailable", /unavailable/i.test(doc.getElementById("pf-avatar-error").textContent));
  }

  // -------------------------------------------------------------------------
  // 9) upload — the STORAGE upload failing is surfaced, NO PUT is sent.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.upload() — a Storage failure is surfaced, no PUT:");
  {
    const failing = makeFailingUploadStub();
    const fetchStub = makeFetchStub(function () { return { status: 200, body: {} }; });
    const { doc, controller } = makeController({ uploadFn: failing, fetchFn: fetchStub });
    controller.selectFile(fakeFile());
    const out = await controller.upload();

    assertEqual("outcome reason upload-failed", out.reason, "upload-failed");
    assertEqual("storage upload was attempted", failing.calls.length, 1);
    assertEqual("backend PUT was NOT sent", fetchStub.calls.length, 0);
    assert("error tells the user the upload failed", /upload/i.test(doc.getElementById("pf-avatar-error").textContent));
    assertEqual("upload re-enabled (file still selected to retry)",
      doc.getElementById("pf-avatar-upload").disabled, false);
  }

  // -------------------------------------------------------------------------
  // 10) upload — no session token: error, NO upload.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.upload() — no token => graceful, no upload:");
  {
    const uploadStub = makeUploadStub(STORAGE_REF);
    const { doc, controller } = makeController({ uploadFn: uploadStub, getIdToken: tokenSource(null) });
    controller.selectFile(fakeFile());
    const out = await controller.upload();
    assertEqual("no-token outcome", out.reason, "no-token");
    assertEqual("no storage upload without a token", uploadStub.calls.length, 0);
    assert("error asks the user to sign in", /sign in/i.test(doc.getElementById("pf-avatar-error").textContent));
  }

  // -------------------------------------------------------------------------
  // 11) showCurrent — renders the live photoURL into the preview.
  // -------------------------------------------------------------------------
  console.log("\ncontroller.showCurrent() — renders the live photoURL:");
  {
    const { doc, controller } = makeController();
    controller.showCurrent(DOWNLOAD_URL);
    assertEqual("preview shows the current photoURL", doc.getElementById("pf-avatar-img").src, DOWNLOAD_URL);
    // A null photoURL (no avatar yet) leaves the placeholder — no src forced.
    const { doc: doc2, controller: c2 } = makeController();
    c2.showCurrent(null);
    assertEqual("no photoURL leaves the image unset", doc2.getElementById("pf-avatar-img").src, "");
  }

  console.log("\n----------------------------------------");
  console.log("avatar simulation: " + passed + " passed, " + failed + " failed");
  if (failed > 0) { process.exitCode = 1; }
}

run().catch(function (err) {
  console.error("simulation crashed:", err);
  process.exitCode = 1;
});

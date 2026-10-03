/*
 * Copyright 2026 Sriram (Sriram-Codes-SW)
 *
 * This file is part of Doorprints.
 *
 * Doorprints is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General
 * Public License as published by the Free Software Foundation, version 3 of the License.
 *
 * Doorprints is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License along with Doorprints (the file LICENSE;
 * the file NOTICE has additional permissions under section 7). If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: AGPL-3.0-only
 */

import { describe, expect, it, beforeEach, afterEach } from "vitest";
import { utf8 } from "../crypto/bytes";
import { WebCryptoProvider } from "../crypto/crypto-provider";
import { DriveDeletionAdapterImpl, InMemoryKeyValueStore } from "../drive/connect/deletion-adapter";
import { SEALED_BLOB_KEY, openWithPrf, prfInput, sealedBlobFromJson } from "./prf-seal";
import { PasskeyPrfMissingError, WebAuthnPrfAuthenticator } from "./web-authn-prf-authenticator";

// Polyfill PublicKeyCredential for testing if not available
if (typeof globalThis.PublicKeyCredential === "undefined") {
  (globalThis as any).PublicKeyCredential = class PublicKeyCredential {
    static async isUserVerifyingPlatformAuthenticatorAvailable() {
      return true;
    }
  };
}

/** SHA-256("WebAuthn PRF" ‖ 0x00 ‖ eval bytes): the client salt, not an authenticator PRF output. */
async function publicClientSalt(evalFirst: Uint8Array): Promise<Uint8Array> {
  const prefix = new TextEncoder().encode("WebAuthn PRF");
  const material = new Uint8Array(prefix.length + 1 + evalFirst.length);
  material.set(prefix, 0);
  material[prefix.length] = 0;
  material.set(evalFirst, prefix.length + 1);
  return new Uint8Array(await crypto.subtle.digest("SHA-256", material));
}

function deletionAdapter(kv: InMemoryKeyValueStore) {
  const cryptoProvider = new WebCryptoProvider();
  const auth = new WebAuthnPrfAuthenticator(
    (key) => kv.get(key),
    (key, value) => kv.set(key, value),
  );
  return new DriveDeletionAdapterImpl(
    {} as never,
    {} as never,
    {} as never,
    "root",
    null,
    undefined,
    cryptoProvider,
    auth,
    kv,
  );
}

/** Fake credentials API for testing. */
class FakeCredentialsContainer {
  private registeredCredentials: Map<string, Uint8Array> = new Map(); // Maps base64url ID to raw bytes
  private prfSecret: Uint8Array;
  private scriptError: Error | null = null;
  recordedCreateOptions: CredentialCreationOptions | null = null;
  recordedGetOptions: CredentialRequestOptions | null = null;
  /** When false, registration reports `prf.enabled: false`. That flag is not the PRF output. */
  createPrfEnabled = true;
  /** When true, create returns a 32-byte `results.first` derived the same way as get. */
  createReturnsPrf = false;
  /** Replaces a derived create output: zeros, the raw salt, or the public WebAuthn client salt. */
  createEcho: "zeros" | "raw-salt" | "public-salt" | null = null;
  /** When false, get returns no PRF output (the ceremony can still succeed). */
  getReturnsPrf = true;
  /** When set, get returns a PRF output only for this credential id. */
  getPrfOnlyFor: Uint8Array | null = null;
  /** When set, the assertion's rawId is this value instead of the requested credential. */
  getRawIdOverride: Uint8Array | null = null;
  /** When false, the assertion's UV flag is clear. */
  getUv = true;
  getCalls = 0;

  constructor(prfSecret: Uint8Array = utf8("fake-prf-secret")) {
    this.prfSecret = prfSecret;
  }

  /** Set up the fake to throw an error on the next operation. */
  setScriptError(error: Error): void {
    this.scriptError = error;
  }

  /**
   * Create a credential with PRF extension. Returns a mock PublicKeyCredential.
   * The credential ID is stored for later retrieval.
   */
  async create(options: CredentialCreationOptions): Promise<Credential | null> {
    // Record the options for assertions
    this.recordedCreateOptions = options;

    if (this.scriptError) {
      const err = this.scriptError;
      this.scriptError = null;
      throw err;
    }

    if (!options?.publicKey) return null;

    const credId = new Uint8Array(16);
    crypto.getRandomValues(credId);

    // Convert id to base64url string as required by WebAuthn
    const idString = btoa(String.fromCharCode(...credId))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=/g, "");

    // Store the raw credential ID for later retrieval by ID string
    this.registeredCredentials.set(idString, credId);

    const extensionInput = (options.publicKey as { extensions?: { prf?: { eval?: { first?: BufferSource } } } })
      ?.extensions?.prf;
    const saltFirst = extensionInput?.eval?.first
      ? new Uint8Array(extensionInput.eval.first as ArrayBuffer)
      : new Uint8Array(32);
    const derived = this.createEcho === "zeros"
      ? new Uint8Array(32)
      : this.createEcho === "raw-salt"
        ? saltFirst.slice(-32)
        : this.createEcho === "public-salt"
          ? await publicClientSalt(saltFirst)
          : this.createReturnsPrf
            ? await this.derive(credId, saltFirst)
            : null;

    const credential = {
      id: idString,
      type: "public-key",
      rawId: credId.buffer,
      response: {
        clientDataJSON: new ArrayBuffer(0),
      },
      authenticatorAttachment: "platform",
      transports: ["internal"],
      getClientExtensionResults: () => ({
        prf: derived
          ? { enabled: this.createPrfEnabled, results: { first: derived.buffer } }
          : { enabled: this.createPrfEnabled },
      }),
      toJSON: () => ({
        id: idString,
        type: "public-key",
        rawId: btoa(String.fromCharCode(...credId)),
        response: {
          clientDataJSON: "",
          attestationObject: "",
          transports: ["internal"],
        },
      } as RegistrationResponseJSON),
    } as PublicKeyCredential;

    return credential;
  }

  /**
   * Get an assertion with PRF extension. Derives the PRF output from the registered
   * credential ID and the salt provided in the PRF extension input.
   */
  private async derive(credIdUint8: Uint8Array, saltFirst: Uint8Array): Promise<Uint8Array> {
    const hashData = new Uint8Array(credIdUint8.length + saltFirst.length + this.prfSecret.length);
    hashData.set(credIdUint8, 0);
    hashData.set(saltFirst, credIdUint8.length);
    hashData.set(this.prfSecret, credIdUint8.length + saltFirst.length);
    const hash = await crypto.subtle.digest("SHA-256", hashData);
    return new Uint8Array(hash).slice(0, 32);
  }

  async get(options: CredentialRequestOptions): Promise<Credential | null> {
    this.getCalls++;
    // Record the options for assertions
    this.recordedGetOptions = options;

    if (this.scriptError) {
      const err = this.scriptError;
      this.scriptError = null;
      throw err;
    }

    if (!options?.publicKey) return null;

    const pubKeyOpts = options.publicKey as any;
    if (!pubKeyOpts.allowCredentials || pubKeyOpts.allowCredentials.length === 0) {
      return null;
    }

    const credIdBuffer = pubKeyOpts.allowCredentials[0].id;
    const credIdUint8 = new Uint8Array(credIdBuffer);
    const prfAllowed =
      !this.getPrfOnlyFor ||
      (this.getPrfOnlyFor.length === credIdUint8.length &&
        this.getPrfOnlyFor.every((b, i) => b === credIdUint8[i]));
    const idString = btoa(String.fromCharCode(...credIdUint8))
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=/g, "");

    // Derive PRF output from the salt in the extension input
    const extensionInput = pubKeyOpts.extensions?.prf;
    const saltFirst = extensionInput?.eval?.first
      ? new Uint8Array(extensionInput.eval.first)
      : new Uint8Array(32);
    const prfOutput = this.getReturnsPrf && prfAllowed ? await this.derive(credIdUint8, saltFirst) : null;
    const rawId = this.getRawIdOverride ? this.getRawIdOverride.slice().buffer : credIdUint8.slice().buffer;
    const authenticatorData = new Uint8Array(37);
    authenticatorData[32] = this.getUv ? 0x05 : 0x01;

    const assertion = {
      id: idString,
      type: "public-key",
      rawId,
      response: {
        clientDataJSON: new ArrayBuffer(0),
        authenticatorData: authenticatorData.buffer,
        signature: new ArrayBuffer(0),
      },
      authenticatorAttachment: "platform",
      getClientExtensionResults: () => ({
        prf: prfOutput
          ? { enabled: true, results: { first: prfOutput.buffer } }
          : { enabled: false },
      }),
      toJSON: () => ({
        id: idString,
        type: "public-key",
        rawId: btoa(String.fromCharCode(...credIdUint8)),
        response: {
          clientDataJSON: "",
          authenticatorData: "",
          signature: "",
        },
      } as AuthenticationResponseJSON),
    } as PublicKeyCredential;

    return assertion;
  }
}

describe("WebAuthnPrfAuthenticator", () => {
  let fakeCredentials: FakeCredentialsContainer;
  const originalNavigator = globalThis.navigator;

  beforeEach(() => {
    fakeCredentials = new FakeCredentialsContainer();
    // Install fake credentials on navigator
    Object.defineProperty(globalThis, "navigator", {
      value: {
        ...originalNavigator,
        credentials: fakeCredentials as any,
      },
      configurable: true,
    });
  });

  afterEach(() => {
    // Restore original navigator
    Object.defineProperty(globalThis, "navigator", {
      value: originalNavigator,
      configurable: true,
    });
  });

  const createStore = () => {
    const storage = new Map<string, string>();
    return {
      get: async (key: string) => storage.get(key),
      set: async (key: string, value: string) => {
        storage.set(key, value);
      },
      storage,
    };
  };

  describe("registerPasskey()", () => {
    it("does not persist the credential ID until the sealed blob is kept", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const credId = await auth.registerPasskey("Test User");

      expect(credId).not.toBeNull();
      expect(credId).toHaveLength(16);
      expect(await store.get("doorprints-webauthn-credential-id")).toBeUndefined();

      await auth.commitRegistration(credId!);
      const stored = await store.get("doorprints-webauthn-credential-id");
      expect(stored).toBeDefined();
      expect(Array.from(new Uint8Array(stored!.split(",").map(Number)))).toEqual(
        Array.from(credId!),
      );
    });

    it("puts the injected hostname on credentials.create as rp.id", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(
        store.get,
        store.set,
        () => "doorprints.web.app",
      );

      await auth.registerPasskey("Test User");

      expect(fakeCredentials.recordedCreateOptions?.publicKey?.rp).toEqual({
        name: "Doorprints",
        id: "doorprints.web.app",
      });
    });

    it("maps 127.0.0.1 to localhost as rp.id", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set, () => "127.0.0.1");

      await auth.registerPasskey("Test User");

      expect(fakeCredentials.recordedCreateOptions?.publicKey?.rp?.id).toBe("localhost");
    });

    it("can be called on a second authenticator instance that reads the stored credential ID", async () => {
      const store = createStore();
      const auth1 = new WebAuthnPrfAuthenticator(store.get, store.set);
      const credId1 = await auth1.registerPasskey("Test User");
      await auth1.commitRegistration(credId1!);

      const auth2 = new WebAuthnPrfAuthenticator(store.get, store.set);
      const supported = await auth2.isSupported();

      expect(supported).toBe(true);
      expect(credId1).not.toBeNull();
    });

    it("returns null when NotAllowedError is thrown", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const error = new DOMException("User cancelled", "NotAllowedError");
      fakeCredentials.setScriptError(error);

      const result = await auth.registerPasskey("Test User");
      expect(result).toBeNull();
    });

    it("throws for generic errors", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      fakeCredentials.setScriptError(new Error("Unknown error"));

      await expect(auth.registerPasskey("Test User")).rejects.toThrow("Unknown error");
      expect(await store.get("doorprints-webauthn-credential-id")).toBeUndefined();
    });

    it("lists ES256 then RS256 and requires a discoverable platform credential", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const credId = await auth.registerPasskey("Test User");
      expect(credId).not.toBeNull();

      const opts = fakeCredentials.recordedCreateOptions!.publicKey as {
        pubKeyCredParams?: { alg: number; type: string }[];
        authenticatorSelection?: {
          authenticatorAttachment?: string;
          residentKey?: string;
          requireResidentKey?: boolean;
          userVerification?: string;
        };
        extensions?: { prf?: { eval?: { first?: BufferSource } } };
      };
      expect(opts.pubKeyCredParams).toEqual([
        { alg: -7, type: "public-key" },
        { alg: -257, type: "public-key" },
      ]);
      expect(opts.authenticatorSelection).toEqual({
        authenticatorAttachment: "platform",
        residentKey: "required",
        requireResidentKey: true,
        userVerification: "required",
      });
      const evalFirst = opts.extensions?.prf?.eval?.first;
      expect(evalFirst).toBeDefined();
      expect(new Uint8Array(evalFirst as ArrayBuffer).length).toBe(prfInput(new Uint8Array(32)).length);
    });

    it("does not keep a credential that cannot produce a PRF output", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);
      // Windows Hello: the PIN ceremony succeeds and reports enabled false, and the assertion
      // also returns no PRF output. That must not be stored, and must not stop at the flag alone.
      fakeCredentials.createPrfEnabled = false;
      fakeCredentials.getReturnsPrf = false;

      await expect(auth.registerPasskey("Test User")).rejects.toBeInstanceOf(PasskeyPrfMissingError);
      expect(fakeCredentials.getCalls).toBe(1);
      expect(await store.get("doorprints-webauthn-credential-id")).toBeUndefined();
    });

    it("finishes setup from a Windows Hello PIN when create returns the PRF output and enabled is false", async () => {
      const kv = new InMemoryKeyValueStore();
      const crypto = new WebCryptoProvider();
      const auth = new WebAuthnPrfAuthenticator(
        (key) => kv.get(key),
        (key, value) => kv.set(key, value),
      );
      fakeCredentials.createPrfEnabled = false;
      fakeCredentials.createReturnsPrf = true;

      const adapter = new DriveDeletionAdapterImpl(
        {} as never,
        {} as never,
        {} as never,
        "root",
        null,
        undefined,
        crypto,
        auth,
        kv,
      );

      expect(await adapter.registerPasskey()).toBe("registered");
      // One PIN: the output came back from create, so no second ceremony.
      expect(fakeCredentials.getCalls).toBe(0);
      expect(await kv.get("doorprints-webauthn-credential-id")).toBeDefined();
      const stored = await kv.get(SEALED_BLOB_KEY);
      expect(stored).toBeDefined();
      const blob = sealedBlobFromJson(stored!);
      expect(blob).not.toBeNull();
      const opened = await openWithPrf(crypto, auth, blob!);
      expect(opened.ok).toBe(true);
    });

    it("does not store a sealed blob when the PIN succeeds and the PRF output is missing", async () => {
      const kv = new InMemoryKeyValueStore();
      const crypto = new WebCryptoProvider();
      const auth = new WebAuthnPrfAuthenticator(
        (key) => kv.get(key),
        (key, value) => kv.set(key, value),
      );
      fakeCredentials.createPrfEnabled = false;
      fakeCredentials.getReturnsPrf = false;

      const adapter = new DriveDeletionAdapterImpl(
        {} as never,
        {} as never,
        {} as never,
        "root",
        null,
        undefined,
        crypto,
        auth,
        kv,
      );

      expect(await adapter.registerPasskey()).toBe("no-prf");
      expect(fakeCredentials.getCalls).toBe(1);
      expect(await kv.get("doorprints-webauthn-credential-id")).toBeUndefined();
      expect(await kv.get(SEALED_BLOB_KEY)).toBeUndefined();
    });

    it("does not seal a new passkey with a PRF output from a passkey already stored", async () => {
      const kv = new InMemoryKeyValueStore();
      const oldId = crypto.getRandomValues(new Uint8Array(16));
      await kv.set("doorprints-webauthn-credential-id", Array.from(oldId).join(","));
      fakeCredentials.createReturnsPrf = false;
      fakeCredentials.getReturnsPrf = true;
      fakeCredentials.getPrfOnlyFor = oldId;

      expect(await deletionAdapter(kv).registerPasskey()).toBe("no-prf");
      const requested = new Uint8Array(
        (fakeCredentials.recordedGetOptions!.publicKey as { allowCredentials: { id: BufferSource }[] })
          .allowCredentials[0].id as ArrayBuffer,
      );
      expect(Array.from(requested)).not.toEqual(Array.from(oldId));
      expect(await kv.get("doorprints-webauthn-credential-id")).toBe(Array.from(oldId).join(","));
      expect(await kv.get(SEALED_BLOB_KEY)).toBeUndefined();
    });

    it.each(["zeros", "raw-salt", "public-salt"] as const)(
      "does not store a create result that is %s",
      async (echo) => {
        const kv = new InMemoryKeyValueStore();
        fakeCredentials.createEcho = echo;
        fakeCredentials.getReturnsPrf = false;

        expect(await deletionAdapter(kv).registerPasskey()).toBe("no-prf");
        expect(await kv.get("doorprints-webauthn-credential-id")).toBeUndefined();
        expect(await kv.get(SEALED_BLOB_KEY)).toBeUndefined();
      },
    );

    it("does not store an assertion whose credential id is not the one just created", async () => {
      const kv = new InMemoryKeyValueStore();
      fakeCredentials.createReturnsPrf = false;
      fakeCredentials.getRawIdOverride = crypto.getRandomValues(new Uint8Array(16));

      expect(await deletionAdapter(kv).registerPasskey()).toBe("failed");
      expect(await kv.get("doorprints-webauthn-credential-id")).toBeUndefined();
      expect(await kv.get(SEALED_BLOB_KEY)).toBeUndefined();
    });

    it("does not store an assertion whose user verification flag is clear", async () => {
      const kv = new InMemoryKeyValueStore();
      fakeCredentials.createReturnsPrf = false;
      fakeCredentials.getUv = false;

      expect(await deletionAdapter(kv).registerPasskey()).toBe("failed");
      expect(await kv.get("doorprints-webauthn-credential-id")).toBeUndefined();
      expect(await kv.get(SEALED_BLOB_KEY)).toBeUndefined();
    });

    it("uses userVerification 'required' in creation options", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const credId = await auth.registerPasskey("Test User");
      expect(credId).not.toBeNull();

      // Assert the recorded options
      expect(fakeCredentials.recordedCreateOptions).not.toBeNull();
      const opts = fakeCredentials.recordedCreateOptions!.publicKey as any;
      expect(opts.authenticatorSelection?.userVerification).toBe("required");
      expect(opts.extensions?.prf).toBeDefined();
    });
  });

  describe("evaluate()", () => {
    it("returns OK with PRF output when credentials are available", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      // First register a passkey
      const credId = await auth.registerPasskey("Test User");
      expect(credId).not.toBeNull();

      // Then evaluate the PRF
      const salt = new Uint8Array(32);
      crypto.getRandomValues(salt);

      const result = await auth.evaluate(credId!, salt);
      expect(result.kind).toBe("OK");
      expect((result as { kind: "OK"; output: Uint8Array }).output).toHaveLength(32);
    });

    it("returns NOT_SUPPORTED when navigator is undefined", async () => {
      Object.defineProperty(globalThis, "navigator", {
        value: undefined,
        configurable: true,
      });

      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const salt = new Uint8Array(32);
      const result = await auth.evaluate(new Uint8Array(16), salt);
      expect(result).toEqual({ kind: "NOT_SUPPORTED" });
    });

    it("returns NOT_SUPPORTED when navigator.credentials is missing", async () => {
      Object.defineProperty(globalThis, "navigator", {
        value: { ...originalNavigator, credentials: undefined },
        configurable: true,
      });

      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const salt = new Uint8Array(32);
      const result = await auth.evaluate(new Uint8Array(16), salt);
      expect(result).toEqual({ kind: "NOT_SUPPORTED" });
    });

    it("returns NOT_SUPPORTED when PRF extension is missing from results", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      // Register a passkey
      const credId = await auth.registerPasskey("Test User");

      // Mock the credential to return no PRF extension
      const originalGet = fakeCredentials.get;
      (fakeCredentials as any).get = async (options: any) => {
        const credential = await originalGet.call(fakeCredentials, options);
        if (credential && "getClientExtensionResults" in credential) {
          credential.getClientExtensionResults = () => ({});
        }
        return credential;
      };

      const salt = new Uint8Array(32);
      const result = await auth.evaluate(credId!, salt);
      expect(result.kind).toBe("NOT_SUPPORTED");
    });

    it("returns NOT_SUPPORTED when results.first is absent", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      // Register a passkey
      const credId = await auth.registerPasskey("Test User");

      // Mock the credential to return PRF without results.first
      const originalGet = fakeCredentials.get;
      (fakeCredentials as any).get = async (options: any) => {
        const credential = await originalGet.call(fakeCredentials, options);
        if (credential && "getClientExtensionResults" in credential) {
          credential.getClientExtensionResults = () => ({
            prf: { enabled: true, results: {} },
          });
        }
        return credential;
      };

      const salt = new Uint8Array(32);
      const result = await auth.evaluate(credId!, salt);
      expect(result.kind).toBe("NOT_SUPPORTED");
    });

    it("returns CANCELLED when NotAllowedError is thrown", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      // Register a passkey
      const credId = await auth.registerPasskey("Test User");

      // Create an error with name property set
      const error = new Error("User cancelled");
      (error as any).name = "NotAllowedError";
      fakeCredentials.setScriptError(error);

      const salt = new Uint8Array(32);
      const result = await auth.evaluate(credId!, salt);
      expect(result).toEqual({ kind: "CANCELLED" });
    });

    it("returns FAILED for generic errors", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      // Register a passkey
      const credId = await auth.registerPasskey("Test User");

      fakeCredentials.setScriptError(new Error("Generic error"));

      const salt = new Uint8Array(32);
      const result = await auth.evaluate(credId!, salt);
      expect(result).toEqual({ kind: "FAILED" });
    });

    it("uses userVerification 'required' in get options", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      // Register a passkey
      const credId = await auth.registerPasskey("Test User");

      // Evaluate (verification is part of the get call)
      const salt = new Uint8Array(32);
      crypto.getRandomValues(salt);
      const result = await auth.evaluate(credId!, salt);

      expect(result.kind).toBe("OK");

      // Assert the recorded get options
      expect(fakeCredentials.recordedGetOptions).not.toBeNull();
      const opts = fakeCredentials.recordedGetOptions!.publicKey as any;
      expect(opts.userVerification).toBe("required");
      expect(opts.allowCredentials).toHaveLength(1);
      expect(new Uint8Array(opts.allowCredentials[0].id)).toEqual(credId);
      expect(opts.extensions?.prf?.eval?.first).toEqual(prfInput(salt));
    });

    it("derives different outputs for different salts, sends different prfInput", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      // Register a passkey
      const credId = await auth.registerPasskey("Test User");

      const salt1 = new Uint8Array(32);
      crypto.getRandomValues(salt1);
      const salt2 = new Uint8Array(32);
      crypto.getRandomValues(salt2);

      const result1 = await auth.evaluate(credId!, salt1);
      expect(result1.kind).toBe("OK");

      const prfInput1 = (fakeCredentials.recordedGetOptions!.publicKey as any).extensions?.prf?.eval?.first;

      const result2 = await auth.evaluate(credId!, salt2);
      expect(result2.kind).toBe("OK");

      const prfInput2 = (fakeCredentials.recordedGetOptions!.publicKey as any).extensions?.prf?.eval?.first;

      // Different salts should produce different PRF inputs
      expect(new Uint8Array(prfInput1)).not.toEqual(new Uint8Array(prfInput2));

      // And different PRF outputs
      expect(Array.from((result1 as { kind: "OK"; output: Uint8Array }).output))
        .not.toEqual(Array.from((result2 as { kind: "OK"; output: Uint8Array }).output));
    });

    it("returns the same output for the same salt, sends same prfInput", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      // Register a passkey
      const credId = await auth.registerPasskey("Test User");

      const salt = new Uint8Array(32);
      crypto.getRandomValues(salt);

      const result1 = await auth.evaluate(credId!, salt);
      expect(result1.kind).toBe("OK");

      const prfInput1 = (fakeCredentials.recordedGetOptions!.publicKey as any).extensions?.prf?.eval?.first;

      const result2 = await auth.evaluate(credId!, salt);
      expect(result2.kind).toBe("OK");

      const prfInput2 = (fakeCredentials.recordedGetOptions!.publicKey as any).extensions?.prf?.eval?.first;

      // Same salt should produce same PRF input
      expect(new Uint8Array(prfInput1)).toEqual(new Uint8Array(prfInput2));

      // And same PRF output
      expect(Array.from((result1 as { kind: "OK"; output: Uint8Array }).output))
        .toEqual(Array.from((result2 as { kind: "OK"; output: Uint8Array }).output));
    });
  });

  describe("isSupported()", () => {
    it("returns true when navigator.credentials is available", async () => {
      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const supported = await auth.isSupported();
      expect(supported).toBe(true);
    });

    it("returns false when navigator is undefined", async () => {
      Object.defineProperty(globalThis, "navigator", {
        value: undefined,
        configurable: true,
      });

      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const supported = await auth.isSupported();
      expect(supported).toBe(false);
    });

    it("returns false when navigator.credentials is missing", async () => {
      Object.defineProperty(globalThis, "navigator", {
        value: { ...originalNavigator, credentials: undefined },
        configurable: true,
      });

      const store = createStore();
      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);

      const supported = await auth.isSupported();
      expect(supported).toBe(false);
    });

    it("returns true when credential ID is cached in storage", async () => {
      const store = createStore();
      await store.set("doorprints-webauthn-credential-id", "1,2,3,4,5");

      const auth = new WebAuthnPrfAuthenticator(store.get, store.set);
      const supported = await auth.isSupported();

      expect(supported).toBe(true);
    });
  });
});

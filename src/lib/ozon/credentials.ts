import crypto from "node:crypto";
import type { OzonCredentials } from "./types";

interface EncryptedSecret {
  v: 1;
  alg: "aes-256-gcm";
  iv: string;
  tag: string;
  data: string;
}

interface EncryptedOzonCredentials {
  clientId: EncryptedSecret;
  apiKey: EncryptedSecret;
}

const KEY_ENV = "OZON_CREDENTIAL_ENCRYPTION_KEY";

function encryptionKey() {
  const secret = process.env[KEY_ENV];
  if (!secret) {
    throw new Error(`${KEY_ENV} is required to store Ozon credentials`);
  }

  return crypto.createHash("sha256").update(secret).digest();
}

function encryptSecret(value: string): EncryptedSecret {
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv("aes-256-gcm", encryptionKey(), iv);
  const data = Buffer.concat([
    cipher.update(value, "utf8"),
    cipher.final(),
  ]);

  return {
    v: 1,
    alg: "aes-256-gcm",
    iv: iv.toString("base64"),
    tag: cipher.getAuthTag().toString("base64"),
    data: data.toString("base64"),
  };
}

function decryptSecret(secret: unknown): string {
  const encrypted = secret as EncryptedSecret;
  if (
    !encrypted ||
    encrypted.v !== 1 ||
    encrypted.alg !== "aes-256-gcm" ||
    !encrypted.iv ||
    !encrypted.tag ||
    !encrypted.data
  ) {
    throw new Error("Stored Ozon credentials are invalid");
  }

  const decipher = crypto.createDecipheriv(
    "aes-256-gcm",
    encryptionKey(),
    Buffer.from(encrypted.iv, "base64")
  );
  decipher.setAuthTag(Buffer.from(encrypted.tag, "base64"));

  return Buffer.concat([
    decipher.update(Buffer.from(encrypted.data, "base64")),
    decipher.final(),
  ]).toString("utf8");
}

export function encryptOzonCredentials(
  credentials: OzonCredentials
): EncryptedOzonCredentials {
  return {
    clientId: encryptSecret(credentials.clientId),
    apiKey: encryptSecret(credentials.apiKey),
  };
}

export function decryptOzonCredentials(
  ciphertext: Record<string, unknown>
): OzonCredentials {
  return {
    clientId: decryptSecret(ciphertext.clientId),
    apiKey: decryptSecret(ciphertext.apiKey),
  };
}

export function credentialHint(value: string) {
  const trimmed = value.trim();
  if (trimmed.length <= 4) return "••••";
  return `••••${trimmed.slice(-4)}`;
}

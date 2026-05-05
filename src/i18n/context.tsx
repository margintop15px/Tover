"use client";

import {
  createContext,
  useCallback,
  useContext,
  useSyncExternalStore,
  type ReactNode,
} from "react";
import { en, type Locale, type TranslationKeys } from "./en";
import { ru } from "./ru";

const dictionaries: Record<Locale, TranslationKeys> = { en, ru };
const LOCALE_STORAGE_KEY = "tover-locale";

interface I18nContextValue {
  locale: Locale;
  t: TranslationKeys;
  setLocale: (locale: Locale) => void;
}

const I18nContext = createContext<I18nContextValue>({
  locale: "en",
  t: en,
  setLocale: () => {},
});

function getStoredLocale(): Locale {
  if (typeof window === "undefined") return "en";
  const saved = window.localStorage.getItem(LOCALE_STORAGE_KEY) as Locale | null;
  return saved && dictionaries[saved] ? saved : "en";
}

function getServerLocale(): Locale {
  return "en";
}

function subscribeLocaleChange(onStoreChange: () => void) {
  if (typeof window === "undefined") return () => {};

  const onStorage = (event: StorageEvent) => {
    if (event.key === LOCALE_STORAGE_KEY) onStoreChange();
  };

  window.addEventListener("storage", onStorage);
  window.addEventListener("tover-locale-change", onStoreChange);

  return () => {
    window.removeEventListener("storage", onStorage);
    window.removeEventListener("tover-locale-change", onStoreChange);
  };
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const locale = useSyncExternalStore(
    subscribeLocaleChange,
    getStoredLocale,
    getServerLocale
  );

  const setLocale = useCallback((newLocale: Locale) => {
    if (typeof window !== "undefined") {
      window.localStorage.setItem(LOCALE_STORAGE_KEY, newLocale);
      window.dispatchEvent(new Event("tover-locale-change"));
    }
  }, []);

  return (
    <I18nContext.Provider
      value={{ locale, t: dictionaries[locale], setLocale }}
    >
      {children}
    </I18nContext.Provider>
  );
}

export function useI18n() {
  return useContext(I18nContext);
}

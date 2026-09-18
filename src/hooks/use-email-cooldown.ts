"use client";

import { useEffect, useState } from "react";

export function useEmailCooldown() {
  const [now, setNow] = useState(() => Date.now());
  const [deadlines, setDeadlines] = useState<Record<string, number>>({});
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  return {
    start(key: string, seconds = 60) {
      setDeadlines((previous) => ({ ...previous, [key]: Date.now() + seconds * 1000 }));
    },
    remaining(key: string, requestedAt?: string | null) {
      const saved = requestedAt ? Date.parse(requestedAt) + 60_000 : 0;
      return Math.max(0, Math.ceil((Math.max(deadlines[key] || 0, saved || 0) - now) / 1000));
    },
  };
}
